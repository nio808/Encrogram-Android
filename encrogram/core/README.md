# :encrogram-core — the Secured engine

Implements `app.encrogram.core.api.SecuredEngine` over the embedded SimpleX chat core (simplex-chat
7.0.0.12, from the official v7.0.4 release). Kotlin Android library. It has **no dependency on Telegram code**
(`:TMessagesProj`): it is meant to become the shared core.

MVP scope: one local profile, one-time invite links, 1:1 text chats, honest delivery ticks.

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
| `internal/DbKeyStore.kt` | Database passphrase, wrapped by an Android Keystore key. |
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
invite is discarded if it fails our own link check), connect `/connect <checked link>`, messages
`/_get chat @<id> count=<n>`, send `/_send @<id> json [{"msgContent":{"type":"text","text":…},"mentions":{}}]`,
read `/_read chat @<id>`. Commands are made pure ASCII (non-ASCII escaped as `\uXXXX` in JSON) because the JNI
glue passes Java's modified UTF-8.

Status mapping: `sndNew`/`sndWarning` -> SENDING, `sndSent` -> SENT_TO_SERVER, `sndRcvd` -> DELIVERED,
`sndError*` -> FAILED, `rcvNew`/`rcvRead` -> RECEIVED. Unknown states never claim progress.

## Fetching the native libraries

```bash
encrogram/tools/fetch-simplex-libs.sh
```

Downloads `simplex-aarch64.apk` from the simplex-chat v7.0.4 GitHub release, checks its SHA-256
(`17966066…db29`) and the three library checksums, extracts only `libsimplex.so`, `libsupport.so` and
`libapp-lib.so` into `src/main/jniLibs/arm64-v8a/` (git-ignored) and deletes the APK. arm64 only.

## Tests

`encrogram/tools/build.sh :encrogram-core:testDebugUnitTest` — JVM tests for reply/event parsing (samples shaped
after `bots/api`), status mapping, link checks, command strings and the relay lock-down planner.

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

SimpleX Chat is AGPL v3: keep its notices and publish the source of what we ship.
