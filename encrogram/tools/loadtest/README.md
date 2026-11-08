# Secured load test

`loadtest.mjs` simulates many people in many Secured groups, all talking at once, against **our own relay only**. It measures setup time, delivery latency, losses and errors, and samples the relay server (read-only) while it runs. Results of the 8 November 2026 run are in `research/secured-load-test.md`.

Node 22 or newer, built-ins only (it uses Node's global `WebSocket`). No packages, no downloads.

## What it runs

- **Simulated people** are the official SimpleX CLI (`simplex-chat`), the same core version as the app. Each CLI process runs its WebSocket JSON API (`-p <port>`) and holds several profiles (users). The core runs one client's commands strictly in order, and most API commands act on the *active* user, so the harness serialises commands per process and switches with `/_user <id>` before each one that needs it.
- **Our relays only.** Each process starts with `-s` and `--xftp-server` set to our relays (without the password), so the core has no preset operator at all. Then, for every profile, the harness does what the engine does in `core/.../ServerConfigPlanner.kt`:
  1. `/network socks=off smp-proxy=unknown smp-proxy-fallback=no`, and verifies it;
  2. `/_operators`: every operator disabled;
  3. `/_servers <user>`: exactly our SMP and XFTP servers with the create password;
  4. reads them back, and stops if anything else is effective.
- **Per group:** one owner and N members (default 18).
  1. The owner makes a one-time invitation per member, and the member connects to it (contacts).
  2. The owner creates the group with the app's preferences (`history` and `fullDelete` on).
  3. The owner adds every contact, and each member accepts (`/_join`).
  4. The core's introductions then connect every member to every other member (full mesh).
  - The seat group (default group 1) also gets a group link, written to `<run-dir>/group1-link.txt` (mode 600), so a real phone can join.
  - **Rendering samples** for that phone, sent by members of the seat group during every steady phase and once more just before teardown, so they are also the newest items in the group history. They include:
    - emoji-only text and mixed emoji text (with a ZWJ family emoji);
    - Arabic, Hebrew and mixed right-to-left text;
    - one 3,000-character message.
    They carry no `lt <id>` prefix, so they are matched by sender and exact text. The seat group stays up at least `--seat-min-sec` (600) after the link is written.
- **Ramp** (`--steps 2,6,12`). Each step sets up its new groups, then runs two phases:
  - **Steady:** every simulated member sends a short text every 15-45 s at random, for `--steady-sec`.
  - **Burst:** every member sends one message within `--burst-sec`.
  - Each phase ends with a drain, which waits for every delivery and for ✓✓.
- **Stop thresholds** (protect the shared server's other sites). The first one that holds stops the ramp. The harness then runs `--confirm-sec` at the previous step's group count and goes to teardown.
  - Server load average above 1.6 for 60 s.
  - Available memory under 800 MB.
  - smp-server RSS above 1.5 GB.
  - The landing page taking over 2 s, or failing, twice in a row.
  - The harness itself above 6 GB of RAM.
  - The server monitor silent for 45 s.
- **Teardown is always run**, even after an error or Ctrl-C:
  1. owners delete their groups with notify;
  2. members delete the group on their side;
  3. both sides delete their contacts;
  4. every test profile is deleted with `/_delete user <id> del_smp=on`, which deletes its queues on the relay;
  5. the harness waits for the relay's queue count (store log `CREATE` minus `DELETE` records) to settle, then stops all CLI processes.
  The test databases are deleted afterwards only if every profile was deleted.

## Measurements

| What | How |
|---|---|
| Delivery latency | Each text carries an id; latency = receive event time minus the moment the `/_send` command started in the core (same Mac clock). Reported p50/p95/p99/max per phase |
| Lost and failed | Expected deliveries (members − 1 per message) minus received after the drain; `/_send` errors; item status errors |
| ✓ and ✓✓ | Time until `sndSent` complete (every copy on the relay) and `sndRcvd` complete (receipts from every member; groups of 20 or fewer only) |
| CLI errors | Command errors, `chatError`/`chatErrors`/`messageError` events, host disconnects, by type |
| Setup | Contacts, joins, and time to full mesh per group (events, checked with `/_members`) |
| Relay | One SSH session, read only, every 10 s: load average, available memory, swap, smp-server CPU (from `/proc/<pid>/stat`), RSS, established connections on 5223, system CPU busy, smp-server read/write rate (`/proc/<pid>/io`), queue count from the store log |
| Landing page | `GET https://encrogram.nytmanager.com/` every 10 s from the Mac |
| Harness | CPU and RSS of node plus every CLI process (`ps`), Mac load; bytes in and out per phase (`nettop`) |

## Run it

```bash
node loadtest.mjs \
  --cli /path/to/simplex-chat \
  --run-dir /private/tmp/.../scratchpad/loadtest-run
```

- `--cli` is required. The binary is not in the repo and must never be committed. On macOS the official build links Homebrew's `openssl@3.0`. The harness sets `DYLD_FALLBACK_LIBRARY_PATH` to `--dyld` (default `/opt/homebrew/opt/openssl@3/lib`, where OpenSSL 3.x is ABI compatible).
- `--run-dir` must be outside the repository. Databases, logs and results go there.
- The relay addresses with their create passwords are read from `telegram-android/encrogram/local.properties` (`SECURED_SMP_SERVER`, `SECURED_XFTP_SERVER`). They are checked against the public `secured.properties` and never printed. Every log line is redacted.
- The server monitor uses `VPS_SSH_ALIAS` from `infra/inventory.env`. It only reads: `/proc`, `ss`, and `grep -c` on the store log. It never changes or restarts anything.

Options (defaults in brackets):

| Option | Meaning |
|---|---|
| `--steps` [2,6,12] | Groups per ramp step |
| `--members` [18] | Members per group besides the owner |
| `--seat-group` [1] | Group that gets a link for a real phone (0 = none) |
| `--seat-min-sec` [600] | Keep the seat group up at least this long after writing its link |
| `--procs` [12] | CLI processes; profiles are spread round robin |
| `--rts` [-N1] | GHC runtime options for each CLI |
| `--steady-sec` [240], `--min-gap` [15], `--max-gap` [45] | Steady phase |
| `--burst-sec` [2], `--drain-sec` [120], `--settle-sec` [20], `--confirm-sec` [120], `--baseline-sec` [30] | Phases |
| `--contact-timeout` [600], `--join-timeout` [600], `--mesh-timeout` [1500] | Setup limits, seconds |
| `--max-load` [1.6], `--load-window` [60], `--min-avail-mb` [800], `--max-rss-mb` [1500], `--max-landing-ms` [2000], `--max-harness-mb` [6000] | Stop thresholds |
| `--keep-db` | Keep the test databases after a clean teardown |
| `--no-monitor` | No SSH sampling (local experiments only; never against the shared server under load) |

**If a run dies before teardown** (for example, the Mac sleeps or the process is killed), restart the CLIs from the same databases and delete everything they made:

```bash
node loadtest.mjs --cli /path/to/simplex-chat --run-dir <same dir> --teardown-only
```

Ctrl-C once stops the load and runs the teardown. Ctrl-C twice kills everything without teardown, so run `--teardown-only` afterwards. `<run-dir>/pids.txt` lists every process the harness started.

## Output (in `--run-dir`)

- `results.json`: everything per phase (setup per group, latency, losses, errors, relay and harness windows, teardown queue counts).
- `harness.log`, `errors.log` (the first 5 samples of each error type, redacted), `logs/cli-pNN.log` (CLI output without the command echo).
- `server-samples.csv`, `harness-samples.csv`, `landing.csv`, `messages-<phase>.jsonl`.
- `group1-link.txt`: the seat group's invite link (short link, then full link). Never commit or paste it.

## Caveats

- One CLI process holds about 19 profiles and processes their events on one thread. A process therefore behaves like one phone that is in about 19 groups, not like 19 phones. Burst latency includes this client-side queueing; the steady phase is closer to the network and relay cost.
- All simulated profiles share one IP and one Mac, so per-IP limits or the Mac's own network can show up as relay limits. Check the relay CPU and load columns before blaming the relay.
- Profiles in the same process share an SMP agent but not a TCP connection: the transport session key includes the user id, so each profile has its own TLS connection to the relay, like a phone.
