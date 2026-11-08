# :encrogram-core — the Secured engine

Implements `app.encrogram.core.api.SecuredEngine` over the embedded SimpleX chat core (simplex-chat
7.0.0.12, from the official v7.0.4 release). Kotlin Android library. It has **no dependency on Telegram code**
(`:TMessagesProj`): it is meant to become the shared core.

Scope (v2, text): one local profile, one-time invite links, 1:1 text chats with reply, edit, delete (for me and
for everyone), reactions, forward, paging and in-chat search, disappearing messages, pin, mute, archive, security
codes, honest delivery ticks.

## Layout

| Path | What |
|---|---|
| `api/SecuredEngine.kt` | The contract the UI uses (fixed). |
| `api/RelayConfig.kt`, `api/SecuredEngines.kt` | Config object and process-wide factory. |
| `chat/simplex/common/platform/Core.kt` | JNI declarations. Name, package and signatures must match the prebuilt `libapp-lib.so` exports (`Java_chat_simplex_common_platform_CoreKt_*`). Do not rename. |
| `internal/SimplexSecuredEngine.kt` | The engine: start-up, relay lock-down, commands, events, chat list cache. |
| `internal/ServerConfigPlanner.kt` | Pure logic that restricts the engine to our relays and verifies it. |
| `internal/LinkChecker.kt` | Offline link check against the allowlist. |
| `internal/CoreJson.kt`, `Commands.kt`, `StatusMapper.kt`, `ErrorMapper.kt` | Small JSON layer (org.json, tolerant of unknown fields), command strings, status and error mapping. |
| `internal/MessageMapper.kt` | Parsed items and chats -> `SecuredMessage` / `ChatSummary`; chat-list order. |
| `internal/InputRules.kt` | Input limits and checks: text and reply size, reaction set, search sanitising (G1), security codes, page windows. |
| `internal/ArchiveStore.kt` | The local-only archive flags (a small file in the no-backup folder). |
| `internal/DbKeyStore.kt` | Database passphrase, wrapped by an Android Keystore key; `replace()` for restores. |
| `backup/` | The 12-word backup (see "Backup with 12 words"). |
| `internal/NativeCore.kt` | Loads the native libraries once per process. |
| `internal/SecuredLog.kt` | The only logger. |

## How config is passed

The core never reads properties files. The app module builds the config and calls the factory:

```java
RelayConfig config = new RelayConfig(smpServers, xftpServers, allowedHosts); // List, List, Collection
SecuredEngine engine = SecuredEngines.create(applicationContext, config);    // process-wide singleton
engine.start();
```

- `smpServers` / `xftpServers`: full addresses `smp://<fingerprint>:<password>@host:port` (they contain relay
  passwords; `RelayConfig.toString()` prints counts only).
- `allowedHosts`: hosts a link may point at. Must contain every SMP host, otherwise our own invites are refused.
- `create` is cheap: nothing is loaded until `start()`. A bad config does not throw; `start()` goes to `FAILED`
  with a readable error.

## Threading model

- **Engine thread** (`encrogram-secured-engine`, single thread): every engine command
  (`chat_send_cmd_retry`), start-up, state changes and the chat list cache. Nothing races.
- **Receive thread** (`encrogram-secured-recv`): loops on `chat_recv_msg_wait` (2 s timeout) and hands each
  event to the engine thread.
- **Main thread**: every `SecuredCallback` and `SecuredListener` call is posted here.
- `chats()`, `state()`, `profile()` and `checkLink()` are cheap and callable from any thread.
- `addListener` immediately replays the current state (`onStateChanged`, plus `onError` when `FAILED`), so a
  screen attached late still learns why Secured is not running.
- Long network commands (creating an invite, connecting) hold the engine thread; other calls queue behind them.

## Start-up (every start, fail closed)

1. `System.loadLibrary("app-lib")` then `initHS()`, once per process, as upstream does. `pipeStdOutToSocket` is
   never called (it would capture Telegram's stdout too).
2. Passphrase from `DbKeyStore` (random 32 bytes, base64; AES-256-GCM wrapped by Keystore key
   `encrogram_secured_db_wrap`; stored in SharedPreferences `encrogram_secured_keys`; written with `commit()`
   before the database exists). Databases: `noBackupFilesDir/encrogram-secured/secured_{chat,agent}.db`.
3. `chatMigrateInit(prefix, key, "yesUp")`, `/set file paths {...}`, `/_files_encrypt on`, `/u`.
   If the file-path or file-encryption step fails, the store is closed and the next start redoes the whole open.
   No user -> `NEEDS_PROFILE` (nothing touches the network). `createProfile` first writes the marker
   `noBackupFilesDir/encrogram-secured/first_run_lockdown`, then sends `/_create user {...}`.
4. Relay lock-down, all through the engine thread, before `RUNNING`:
   - While the first-run marker exists, `/network socks=127.0.0.1:9 socks-mode=always …` (a closed local port)
     is set and verified first, so no connection can leave the phone while the new user still has preset servers;
   - otherwise `/network socks=off smp-proxy=unknown smp-proxy-fallback=no`, reply checked (`smpProxyMode=unknown`,
     `smpProxyFallback=prohibit`);
   - `/_operators` then `/_operators [<every operator with enabled:false>]`, reply checked;
   - `/_start main=on` (the engine only accepts server changes on a started chat);
   - `/_servers <userId>` then `/_servers <userId> [{"operator":null,"smpServers":[ours],"xftpServers":[ours],...}]`
     (stale custom entries deleted, ours updated in place or added);
   - `/_servers <userId>` read back and verified: no enabled operator, effective SMP and XFTP servers exactly ours,
     and no operator domain covering our hosts.
   - the preset "team" contact card is deleted (`/_delete @id full notify=off`, no network) and the chat list is
     loaded (`/_get chats <userId>`);
   - on a first run only: switch to the normal network settings above, verify them, then delete the marker.
   Any failure at any point after `/_start` (any exception, not only expected ones): `/_stop`, the first-run lock
   stays or is put back, state `FAILED`, readable error.

Link checks (`LinkChecker.check(link, allowedHosts, smpServers)`): queries with a raw `;`, an encoded or empty
parameter name, a repeated name or control characters are rejected, so the link can't be read two ways. Every
host in the link must be one of our SMP servers, with matching port (default 5223) and fingerprint when given.

Other commands: invite `/_connect <userId>` (the short `https://<our SMP host>/i#…` link is returned; the
invite is discarded if it fails our own link check), connect `/connect <checked link>`, read `/_read chat @<id>`.
Commands are made pure ASCII (non-ASCII escaped as `\uXXXX` in JSON) because the JNI glue passes Java's modified
UTF-8. Source of every shape: `research/simplex-chat-api-for-ui.md` §1, checked against the core source.

## v2 commands

| Contract call | Engine command | Reply |
|---|---|---|
| `createProfile` | `/_create user {"profile":{…,"preferences":{"fullDelete":{"allow":"yes"},"calls":{"allow":"no"}}},…}` | `activeUser` |
| `updateProfile` | `/u`, then `/_profile <userId> {whole Profile}` (read-modify-write) | `userProfileUpdated` / `userProfileNoChange` |
| `messages(chatId, page)` | `/_get chat @<id> [before=\|after=\|around=<itemId>] count=<n> [search=<text>]` | `apiChat` |
| `sendMessage` | `/_send @<id> json [{"msgContent":{"type":"text","text":…},"quotedItemId":<id>?,"mentions":{}}]` | `newChatItems` |
| `itemAction(Edit)` | `/_update item @<id> <itemId> live=off json {"msgContent":{…},"mentions":{}}` | `chatItemUpdated` / `chatItemNotChanged` |
| `itemAction(DeleteForMe)` | `/_delete item @<id> <id1>,<id2> internal` | `chatItemsDeleted` |
| `itemAction(DeleteForEveryone)` | `/_delete item @<id> <ids> broadcast` | `chatItemsDeleted` |
| `itemAction(React)` | `/_reaction @<id> <itemId> on\|off {"type":"emoji","emoji":…}` | `chatItemReaction` |
| `itemAction(ForwardTo)` | `/_forward @<to> @<from> <ids> ttl=default` | `newChatItems` (target chat) |
| `chatAction(SetPinned / SetMuted)` | `/_get chat @<id> count=1` (current settings), then `/_settings @<id> {whole ChatSettings}` | `cmdOk` |
| `chatAction(SetArchived)` | none: local only | - |
| `chatAction(ClearHistory)` | `/_clear chat @<id>` (local) | `chatCleared` |
| `chatAction(Delete)` | `/_delete @<id> full notify=on\|off` | `contactDeleted` |
| `chatAction(SetDisappearing)` | `/_set prefs @<id> {whole userPreferences, timedMessages {allow:yes, ttl?}}` | `contactPrefsUpdated` |
| `securityCode` | `/_get code @<id>` | `contactCode` |
| `verifyContact` | `/_verify code @<id> [<digits and spaces>]` | `connectionVerified` |
| `connect` (before connecting) | `/_connect plan <userId> <expanded link>`; `ownLink` → `OWN_LINK`, nothing connected | `connectionPlan` |
| Saved Messages (`chatId` -1) | same commands with `*<noteFolderId>`: `/_get chat *…`, `/_create *<id> json [...]` (not `/_send`), `/_update item *…`, `/_delete item *… internal`, `/_forward` either way, `/_clear chat *…` (only for `ClearHistory`) | as above |

### Saved Messages, own invites, short links (8 November 2026)

- **Saved Messages** is SimpleX's local note folder (`ChatInfo` type `local`), shown as chat id `SAVED_MESSAGES_CHAT_ID` (-1). Local only: nothing is sent to a relay, items never notify. Refused with NOT_ALLOWED: reactions, replies, mute, archive, disappearing, verify, pin (the core can't set `favorite` on a note folder) and `ChatAction.Delete` (a list "delete chats" must never wipe notes; only `ClearHistory` clears them).
- **Own invite:** `/connect` would silently connect you to yourself (`connectionPlanProceed` is true for `ownLink`), so `connect` asks for the plan first.
- **Short links:** `createInvite` strips `?p=<port>&c=<keyHash>` when they name exactly one of our approved relays (`LinkChecker.compact`, only if `expand` restores the identical string); `checkLink` and `connect` re-add them (`LinkChecker.expand`) before validating. Without the query the core would have no key hash for the relay. Links for other hosts are untouched.
- **Reactions:** `/_get chat around=<id>` returns the item itself without reactions (`Store/Messages.hs` `getDirectChatAround'`), so the engine doesn't pre-check own reactions; it maps the core's fixed answers (`reaction already added/removed` → ok, `too many reactions` → the limit message). Only those three strings become error tags.

`sendText` is `sendMessage(…, null, …)`; `messages(chatId, limit)` is `messages(chatId, MessagePage(limit))`.
Every action result also arrives through the listener; callbacks only say whether the action was accepted.

### Behaviour

- **Pin** is SimpleX's `favorite`, **mute** is `enableNtfs` (`none` / `all`). `/_settings` replaces the whole
  ChatSettings, so the current one is read first and only one field changes (`sendRcpts` and future fields stay).
- **Archive** is local only: a set of contact ids in `noBackupFilesDir/encrogram-secured/archived_chats`, never
  sent. Not SharedPreferences: those can be backed up (Telegram's manifest allows backup) and a restored list would
  mark the wrong chats, because contact ids start again on a new device. Ids of chats that are gone are dropped at
  every list reload.
- **Order:** `chats()` is pinned first, then newest activity, then id.
- **Profile preferences:** new profiles get `fullDelete: yes` and `calls: no` in `/_create user`. At every start,
  after the relay lock-down is verified, a profile without them gets one read-modify-write `/_profile` that keeps
  every other field (the core replaces the whole profile, gotcha G4). It is a no-op once merged and never fails the
  start. `updateProfile` uses the same merge.
- **Delete:** for me = `internal` (row removed, `onMessagesRemoved`). For everyone = `broadcast`: the core removes
  the row when `fullDelete` is on for us (`onMessagesRemoved`) or only marks it (`onMessageChanged` with
  `deleted=true`). A deleted message never carries text, a quote or reactions, even though the core keeps them in a
  marked item's JSON. A deleted last message shows no text in the chat list.
- **React:** only `SECURED_REACTIONS` (heart = U+2764 alone), else `NOT_ALLOWED` before anything is sent. Adding a
  reaction we already have, or removing one we don't, is a no-op; a fourth own reaction is `NOT_ALLOWED`.
- **Edit:** the message is read first; only our own text message with `meta.editable` (under 24 h, not forwarded,
  sent) can be edited, else `NOT_ALLOWED`. The core's `invalidChatItemUpdate` maps to `NOT_ALLOWED` as well.
- **Reply size:** the core copies the whole quoted text into the reply (`/_send` has no way to shorten it), so a
  reply is refused when new text + quoted text + 300 bytes of quote overhead exceeds the 13,000-byte text cap.
  `QuotePreview.text` is cut to 300 characters for the UI.
- **Search (G1):** `search=` is plain text, and JNI's modified UTF-8 mangles characters outside the BMP. Such
  characters and control characters split the query and the longest remaining run is searched (a wider match,
  never a missed one). An emoji-only query is `NOT_ALLOWED`. Queries are capped at 256 characters.
- **Disappearing messages:** `SetDisappearing(ttl)` sets OUR timer for the contact (`allow: yes, ttl`); `null`
  keeps `allow: yes` without a timer, so the contact can still use theirs. `ChatSummary.disappearingTtlSeconds` is
  our timer when the feature is on for both sides. Marking a chat read starts the countdown of received timed
  messages; the engine then reports them with `onMessageChanged` (`expiresAtMs` set).
- **Security code:** `securityCode` returns the core's code (digit groups). `verifyContact(code)` accepts digits and
  spaces only; `null` clears verification. Both reload the list so `verified` follows the core.
- **Network (8 November 2026):** `NetworkWatch` follows the default network (ConnectivityManager callback) from
  opening the store until `stop()` and sends each real change as `/_network info {"networkType":"wifi","online":true}`,
  as the official app does. The core hears `online:false` only when the phone has no network at all for 3 s (never
  from Android's "validated" check, which can be blocked on a working network), so it retries at once when the
  network is back. On every switch to another network, or a network coming back, the engine sends `/reconnect` once
  the network has been stable for 2 s: a relay connection opened on the old network can go silent without an error,
  and the agent would keep it (and every send and subscription on it) for 15+ minutes. `isOnline()` (screens only) is
  false without internet or behind a captive portal. No ConnectivityManager, no permission or no callback: online.
  `TELL_CORE_OFFLINE = false` turns the offline report off (the reconnect stays).
- **Link joins:** one row, `JOINED_BY_LINK`. The link owner's `invitedViaGroupLink` item is hidden and remembered
  (group and member id in `noBackupFilesDir/encrogram-secured/link_joins`, pruned with the groups); that member's
  `memberConnected` row becomes the one row. The member record never decides it (contact deletes and merges change
  it). The joiner's own row is `JOINED_BY_LINK` with no name when the GroupInfo has `viaGroupLinkUri`/`preparedGroup`.
  Other members can't tell a link join from an invitation (no marker in `x.grp.mem.new`): they keep "invited" then
  "joined".
- **Row preview:** `/_get chats` gives one newest item per chat; when that is a row we skip (members connecting
  after our join, feature or profile changes), the list reload looks back up to 3 × 50 items for the newest message
  or shown service row (`lastMessageText` or `lastEvent`). Cached per chat while its newest item stays the same (new
  skipped rows move the key along), and event-driven reloads run at most once per 500 ms.
- **Gone:** `ChatSummary.gone` is true when our membership is over (left, removed, rejected, deleted).

### Events handled

| Event | Listener |
|---|---|
| `newChatItems` | `onMessageReceived` (incoming), row update |
| `chatItemsStatusesUpdated` | `onMessageStatusChanged`, last-message ticks in the row |
| `chatItemUpdated` (contact's edit) | `onMessageChanged`; list reload when it is the row's last message |
| `chatItemReaction` | `onMessageChanged` with the full reaction list |
| `chatItemsDeleted` (contact's delete, or `timed:true` expiry) | `onMessagesRemoved` (row gone) or `onMessageChanged` (marked) |
| contact and chat-info events (`CHAT_LIST_EVENTS`) | list reload, `onChatsChanged` |

Replies to our own actions are applied the same way (the core does not echo them as events). Forwarded copies
arrive with `onMessageReceived` in the target chat.

Status mapping: `sndNew`/`sndWarning` -> SENDING, `sndSent` -> SENT_TO_SERVER, `sndRcvd` -> DELIVERED,
`sndError*` -> FAILED, `rcvNew`/`rcvRead` -> RECEIVED. Unknown states never claim progress.

Errors: `NOT_ALLOWED` is a refusal by the protocol's or our rules (edit after 24 h, delete for everyone too late,
unknown reaction, reaction limit, reply to a deleted message, emoji-only search, nothing forwardable). Malformed
input (empty, too long, bad page) is `UNKNOWN`, as before.

## Background receiving

Package `app.encrogram.core.background`. No Telegram imports; the app passes the small icon, accent colour and the tap intent through `NotificationConfig` (`SecuredBackground.install`).
- **What runs:** `SecuredService`, a foreground service of type `remoteMessaging`, keeps the process alive so the engine's relay connections stay open. It starts the engine (idempotent), never stops it, and turns incoming messages (not outgoing, not from muted chats, not the chat on screen) into notifications. `SecuredBootReceiver` restarts it after boot or an update, only if the flag file `noBackupFilesDir/encrogram-secured/background_on` exists (outside Android backups, so a restored phone never starts it by itself). Tap intents carry a random per-install token (`open_token` in the same folder); `SecuredHooks` ignores `OPEN_CHAT` intents without it, because `LaunchActivity` is exported. There is no swipe-from-Recents restart: Android refuses background service starts from an alarm, so the service relies on `START_STICKY`. `BatteryHelper` has the battery-exemption checks and request intent.
- **Channels:** group `encrogram_secured`; `encrogram_secured_messages` (high) and `encrogram_secured_service` (low, no badge). Posted with tag `encrogram_secured`, id = the local chat id's hash, so Telegram's untagged ids cannot touch them.
- **What a notification shows:** hidden mode only. Title "Encrogram", text "New Secured message" ("3 new Secured messages" for several), no sender, no text, no actions, private on the lock screen. The tap intent carries only the local chat id.
- **Known limits:** force-stop stops it until the app is opened again. No reply or mark-read actions yet. Prompt delivery while the phone is idle (Doze) needs the battery exemption. After the user swipes the app away, the restart alarm may be refused by Android 12+ and then the service returns only at the next app launch or boot. Battery cost is not measured yet.

## Backup with 12 words

Package `app.encrogram.core.backup` plus the backup section of `SimplexSecuredEngine`. Design: `docs/recovery-phrase-plan.md` (including the Fable review). No new dependency.

| File | What |
|---|---|
| `Bip39.kt` | 12 English words <-> 16 bytes of entropy with the 4-bit checksum; NFKD/lower-case input; suggestions. Word list: `src/main/resources/app/encrogram/core/backup/bip39-english.txt` (official `bip-0039/english.txt`, MIT notice next to it), SHA-256 checked on load. |
| `Kdf.kt` | BIP39 seed (platform `PBKDF2WithHmacSHA512` for ASCII, hand-written PBKDF2 otherwise); HKDF-SHA256 by hand; `backupId = hex(HKDF(seed, info "encrogram/backup/id/v1", 32))`, `backupKey = HKDF(seed, "encrogram/backup/key/v1", 32)`, salt none. A storage format: iOS must match (known answers in `KdfTest`). |
| `Envelope.kt` | `"ENCB" \| 0x01 \| nonce(12) \| AES-256-GCM(key, createdAt u64 \| counter u64 \| dbKeyLen u16 \| dbKey \| zip, aad "ENCB"\|0x01)`, 20 MiB cap, rollback check. |
| `BackupClient.kt` | `PUT/GET/HEAD/DELETE <base><id>` with `HttpsURLConnection` only; HTTPS, no redirects or caches, 15 s connect / 60 s read (HEAD 15 s), bodies capped, 404 = no backup. GET and HEAD return the server's `ETag`; `tagDecision` is the check before an upload (same tag or 404: upload; different tag: another phone; no answer: don't pause the chat). |
| `KeyVault.kt` | `backup/keys.bin`: id + key under AES-GCM Keystore key `encrogram_secured_backup_ids` (no user auth). `backup/phrase.bin`: entropy under RSA-OAEP key pair `encrogram_secured_backup_phrase` (user auth, 30 s, unlocked device on API 28+, StrongBox with fallback; encrypting needs only the public key). `metaBox()`: AES-GCM key `encrogram_secured_backup_meta` (no user auth) for BackupStore's sealed files; "turn off" keeps it. |
| `BackupStore.kt` | `backup/state` (counter, last backup, changed flag, error code, upload owed, skip-check-once, paused for another phone, failures in a row, last attempt, newest counter seen per backup id hash), `backup/restore_pending` (contacts to repair with their flags, and the item id the restore started from), and two sealed files: `server_tag.bin` (ETag of this phone's last upload, bound to the id) and `delete_queue.bin` (ids whose server copy must still be deleted). Pure rules: `backoffMs`, `autoDueMs`, `autoPaused`. |
| `RestoreRepair.kt` | Pure rules per restored contact: sync, forced sync (once), switch, wait, done or drop; what counts as proof; the restore's commit order. |

All files under `noBackupFilesDir/encrogram-secured/backup/`. Config: `RelayConfig.backupBaseUrl` (app: `SECURED_BACKUP_BASE_URL` in `encrogram/secured.properties`); null or not `https://.../` means backup calls fail with NOT_ALLOWED.

- **Threads:** network and sealing on `encrogram-secured-backup`; Keystore and words (`newPhrase`, `showPhrase`, `enableBackup`'s storing) on `encrogram-secured-phrase`, so "Show 12 words" never waits behind a 20 MB upload; export, import and the repair on the engine thread.
- **One upload** (one at a time; "Back up now" during another waits for it): HEAD the server copy while the chat still runs (no answer: no pause, retry later) -> for automatic uploads compare the ETag with this phone's last one (different: `otherPhone`, automatic uploads paused until "Back up now") -> engine thread: `/_stop` -> wait 0.5 s -> `/set file paths` with empty files and assets folders (text only) -> `/_db export` -> folders back -> `/network` checked -> `/_start` -> backup thread: seal, PUT, HEAD for the new ETag (kept sealed). Any failure to resume closes the store and goes to FAILED.
- **Triggers:** `enableBackup` and `backupNow` (both skip the tag check); owed uploads: right after a restore (skips the tag check once), 10 s after `contactConnected`, 30 s after a finished queue switch (`contactSwitch` `rcv`/`snd` `completed`) or a completed repair sync, coalesced (each request in a burst pushes the timer, at most 5 min after the first); and once a day when something changed. Failed automatic uploads back off 30 min, 1 h, 2 h ... 24 h (reset on success); `too_large` is never retried automatically.
- **Turn off:** the id is queued (sealed) before the DELETE; words and keys on the phone go either way. A failed delete is retried at each start and after each upload attempt; the result then says the server copy is still there. A phrase change queues the old id the same way.
- **Restore** (only in NEEDS_PROFILE; the phone needs a screen lock): download (keeps the ETag), open, counter check -> zip in `tmp/` -> `/_db import` -> close -> `DbKeyStore.replace(backup key)` -> `chatMigrateInit` -> `/_db encryption` to a fresh key -> `replace(fresh)` -> delete `.bak`/`.exported` -> reopen -> contact list -> repair list saved -> words, keys and ETag stored -> `restore_in_progress` marker cleared (commit point: any failure before it, including a marker that won't delete, fails the restore and wipes; a crash before it is wiped at the next start) -> first-run network lock -> normal start -> the owed upload.
- **After a restore, per contact:** once, `/_sync @id force=on` when the stats say `ok` and `ratchetSyncSupported`; plain `/_sync @id` when the core reports `allowed`/`required`. Proof that the ratchet works both ways is only a completed sync (`ok` after `started`/`agreed`) or a delivery receipt on one of our messages sent after the restore; a decrypted incoming message is not. Then `/_switch @id` once the contact is ready and no switch is running; the contact leaves the list when the switch is over. Any contact whose sync state becomes `required` is re-synced automatically (at most every 10 min, never forced). Groups: see the `G1:` note in `restoreOnEngine`.
- **Logs:** fixed event names only (`BACKUP_*`, `RESTORE_*`, `RATCHET_SYNC`, `QUEUE_SWITCH`), HTTP status as the count. Never words, id, key, URL, ETag, database key or archive.
- **Not verified without a device:** Keystore behaviour (auth window, StrongBox RSA-OAEP, invalidation), real export/import/re-key with the prebuilt core, nginx answers.

## Fetching the native libraries

```bash
encrogram/tools/fetch-simplex-libs.sh
```

Downloads `simplex-aarch64.apk` from the simplex-chat v7.0.4 GitHub release, checks its SHA-256
(`17966066…db29`) and the three library checksums, extracts only `libsimplex.so`, `libsupport.so` and
`libapp-lib.so` into `src/main/jniLibs/arm64-v8a/` (git-ignored) and deletes the APK. arm64 only.

## Tests

`encrogram/tools/build.sh :encrogram-core:testDebugUnitTest` — JVM tests for reply/event parsing (samples shaped
after `bots/api` and the core's types), status mapping, link checks, command strings, the relay lock-down planner,
and for v2: every new command string (`CommandsTest`), item replies and events including "a deleted message never
carries text" (`ItemEventsTest`), row fields, sort order, archive store and read-modify-write of settings and
preferences (`ChatListTest`), the profile preference merge (`ProfileMergeTest`), and input rules: reactions,
search sanitising, reply budget, security codes, page windows (`InputRulesTest`).

## Logging

Only `SecuredLog`: a fixed event name, an optional engine type tag (e.g. `errorAgent.SMP.AUTH`) and a count.
No free-text parameter; exceptions are reduced to their class name. Never message text, names, links, server
addresses or keys.

## MVP limitations (fix before real users)

- **Prebuilt libraries.** Taken from the official release APK, pinned by checksum, not built by us. No core
  patches, so relays are set at runtime.
- **Runtime server config can fail open in two engine paths** (`research/simplex-capabilities.md` §4): the core
  falls back to random preset servers whenever a user's effective server list becomes empty
  (`useServerCfgs`), and the agent's `mkUserServers`/`knownHosts` can use disabled servers when no enabled server
  has a role. We verify on every start that this cannot happen with the stored config, but nothing in the
  engine enforces it afterwards. The real fix is the preset fork plus a transport-level allowlist (§4.2, §4.4).
- **Our relay still proxies to foreign relays.** With `smp-proxy=unknown` and no fallback the phone never opens
  a connection to an unknown SMP relay, but if a peer moves its queue elsewhere our relay forwards to it until
  the server-side `PRXY` patch (§4.4 step 2) lands. Links to other relays are refused before connecting.
- **No background receiving.** The engine runs only while the app process runs; no service, no notifications.
- **Group, channel and relay links** (`/g`, `/c`, `/r`) are reported as `Malformed` by `checkLink`. One-time
  invites (`/i`, `/invitation`) and contact addresses (`/a`, `/contact`) are accepted; accepting incoming contact
  requests is not implemented.
- New-user window: a brand-new user is seeded with presets until step 4 finishes. That window is now covered by
  the first-run lock (all traffic forced to a closed local SOCKS port until our servers are verified). Checked
  against simplexmq v7.0.1 source; the core's exact pinned revision isn't available locally, and it hasn't
  been watched on a device yet.
- Contacts whose reply inbox is on a public relay are reached through our relay (it forwards). Task b1 on
  `docs/tasks.md` disables such contacts.
- Input limits: display names up to 50 code points, messages up to 13,000 bytes of JSON-escaped UTF-8 (below the
  core's uncompressed message limit).
- The GHC runtime (`-A64m -H64m`, fixed in the prebuilt glue) runs inside Telegram's process and cannot be
  restarted; `stop()` stops the chat and closes the store, and a later `start()` opens a new controller.
- The passphrase is a Java `String` and cannot be wiped from memory. Telegram's manifest allows backup: the
  wrapped passphrase in `encrogram_secured_keys` may be backed up, but it is useless without the device-bound
  Keystore key, and the databases live in `noBackupFilesDir`.
- The JNI glue's returned C strings are never freed (upstream defect, small leak per call).
- A reply keeps the quoted text (it is part of the reply's own message), so `replyTo.text` can still show text of a
  message that was later deleted. Hiding it needs a lookup per quote; not done yet.
- `/_profile` merge drops `localBadge` (it has a different shape from Profile's `badge`). Encrogram never sets a
  badge, so nothing is lost today; revisit if badges are ever used.
- Action commands that go over the network (send, edit, delete for everyone, reactions, forward, profile and
  preference updates) hold the engine thread like sends do.

SimpleX Chat is AGPL v3: keep its notices and publish the source of what we ship.
