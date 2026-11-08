#!/usr/bin/env node
// Encrogram Secured load test. Node 22+ built-ins only (global WebSocket). See README.md next to this file.
//
// Simulated people are official SimpleX CLI processes (`simplex-chat -p <port>`, WebSocket JSON API), several
// profiles per process, talking only to our own SMP relay. Every profile is restricted to our relays the same way
// the app's engine does it (ServerConfigPlanner.kt): preset operators disabled, exactly our SMP and XFTP servers,
// read back and verified before use.
//
// Never prints relay passwords: they are read from local.properties, sent only to the local CLI processes, and
// every line written to a log goes through redact().

import { spawn, execFile } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import https from 'node:https';
import os from 'node:os';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '../../../..');

const DEFAULTS = {
  'relay-props': path.join(REPO, 'telegram-android/encrogram/local.properties'),
  'public-props': path.join(REPO, 'telegram-android/encrogram/secured.properties'),
  inventory: path.join(REPO, 'infra/inventory.env'),
  steps: '2,6,12',
  members: '18',
  'seat-group': '1',
  procs: '12',
  'base-port': '15300',
  rts: '-N1',
  dyld: '/opt/homebrew/opt/openssl@3/lib',
  'baseline-sec': '30',
  'settle-sec': '20',
  'steady-sec': '240',
  'min-gap': '15',
  'max-gap': '45',
  'burst-sec': '2',
  'drain-sec': '120',
  'confirm-sec': '120',
  'contact-timeout': '600',
  'join-timeout': '600',
  'mesh-timeout': '1500',
  'landing-url': 'https://encrogram.nytmanager.com/',
  'max-load': '1.6',
  'load-window': '60',
  'min-avail-mb': '800',
  'max-rss-mb': '1500',
  'max-landing-ms': '2000',
  'max-harness-mb': '6000',
  'seat-min-sec': '600',
};

// Rendering samples for the real phone in the seat group (lead, 8 Nov 2026): emoji-only and mixed emoji text,
// right-to-left text, and one ~3,000-character message. Sent without the "lt <id>" prefix (it would break
// emoji-only rendering), so they are matched by sender and exact text instead.
const LONG_TEXT = (() => {
  let t = 'Long message test (about 3,000 characters) for rendering checks. ';
  for (let i = 1; t.length < 3000; i++) t += `Line ${i}: the quick brown fox jumps over the lazy dog, 0123456789. `;
  return t.slice(0, 3000);
})();
const FAMILY = '\u{1F468}\u200D\u{1F469}\u200D\u{1F467}';
const SEAT_TEXTS = [
  '\u{1F525}\u{1F44D} ok',
  `${FAMILY} family`,
  '\u{1F525}\u{1F44D}',
  '\u{1F602}',
  FAMILY,
  '\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u062C\u0645\u064A\u0639',
  '\u05E9\u05DC\u05D5\u05DD \u05DC\u05DB\u05D5\u05DC\u05DD',
  '\u0645\u0631\u062D\u0628\u0627 Encrogram 123 \u05E9\u05DC\u05D5\u05DD',
  LONG_TEXT,
];

// ------------------------------------------------------------------------------------------------ small helpers

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = () => Date.now();
const pad = (n, w = 2) => String(n).padStart(w, '0');
const rand = (a, b) => a + Math.random() * (b - a);
const round = (x, d = 1) => (x == null || !Number.isFinite(x) ? null : Math.round(x * 10 ** d) / 10 ** d);
const sec = (ms) => round(ms / 1000, 1);

/** Hides the password part of any smp:// or xftp:// address. */
const redact = (s) => String(s).replace(/((?:smp|xftp|ntf):\/\/[^:@\/\s"'\\]+):[^@\s"'\\]+@/g, '$1:<pw>@');
const stripPw = (a) => a.replace(/^((?:smp|xftp):\/\/[^:@\/]+):[^@]*@/, '$1@');
/** JSON with every non-ASCII character escaped as \\uXXXX, as the engine sends it (Commands.asciiJson). */
const asciiJson = (v) => JSON.stringify(v).replace(/[\u007f-\uffff]/g, (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`);

function summary(arr) {
  const a = arr.filter((x) => x != null && Number.isFinite(x)).sort((x, y) => x - y);
  if (!a.length) return { n: 0 };
  const q = (p) => a[Math.min(a.length - 1, Math.max(0, Math.ceil((p / 100) * a.length) - 1))];
  return { n: a.length, mean: round(a.reduce((s, x) => s + x, 0) / a.length), p50: q(50), p95: q(95), p99: q(99), max: a[a.length - 1] };
}

function stats(vals) {
  const a = vals.filter((x) => x != null && Number.isFinite(x));
  if (!a.length) return null;
  return { min: Math.min(...a), mean: round(a.reduce((s, x) => s + x, 0) / a.length, 2), max: Math.max(...a) };
}

/** The `type` tags along a nested core error, e.g. errorAgent:BROKER:TIMEOUT. Strings (addresses) are skipped. */
function typeTags(o, depth = 0) {
  if (!o || typeof o !== 'object' || depth > 5) return '';
  const parts = [];
  if (typeof o.type === 'string') parts.push(o.type);
  for (const [k, v] of Object.entries(o)) {
    if (k === 'type' || !v || typeof v !== 'object' || Array.isArray(v)) continue;
    const s = typeTags(v, depth + 1);
    if (s) { parts.push(s); break; }
  }
  return parts.join(':');
}

function errTag(r) {
  if (!r) return 'no-response';
  if (r.type === 'chatCmdError' || r.type === 'chatError') return typeTags(r.chatError) || r.type;
  return `unexpected:${r.type}`;
}

function cmdName(text) {
  const [a, b] = text.split(' ');
  return /^[a-z]+$/.test(b || '') ? `${a} ${b}` : a;
}

async function waitUntil(pred, timeoutMs, halt = () => false, step = 250) {
  const end = now() + timeoutMs;
  for (;;) {
    if (pred()) return true;
    if (now() > end || halt()) return pred();
    await sleep(step);
  }
}

function readProps(file) {
  const out = {};
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const m = /^\s*([A-Za-z0-9_.]+)\s*=\s*(.*?)\s*$/.exec(line);
    if (!m) continue;
    const q = /^(["'])(.*?)\1/.exec(m[2]);
    out[m[1]] = q ? q[2] : m[2].replace(/\s+#.*$/, '');
  }
  return out;
}

// ------------------------------------------------------------------- relay address rules (ServerConfigPlanner.kt)

function parseServer(s) {
  const m = /^(smp|xftp|ntf):\/\/([^:@\/]+)(?::([^@]*))?@([^\/?#\s]+)$/.exec(String(s || '').trim());
  if (!m) return null;
  let hosts = m[4];
  let port = '';
  const pm = /^(.*):(\d+)$/.exec(hosts);
  if (pm) { hosts = pm[1]; port = pm[2]; }
  return { scheme: m[1], fp: m[2], pw: m[3] ?? '', hosts: hosts.split(',').map((x) => x.toLowerCase()).sort(), port };
}
const sameRelay = (a, b) => a.scheme === b.scheme && a.fp === b.fp && a.hosts.some((h) => b.hosts.includes(h));
const sameRelayExact = (a, b) =>
  a.scheme === b.scheme && a.fp === b.fp && a.pw === b.pw && a.port === b.port && a.hosts.join(',') === b.hosts.join(',');

function networkConfigOk(r) {
  const c = r?.networkConfig;
  return !!c && c.smpProxyMode === 'unknown' && c.smpProxyFallback === 'prohibit' && c.socksProxy == null;
}

/** The APISetUserServers payload: one custom group holding exactly our two servers (mirrors customServersUpdate). */
function customServersUpdate(body, smp, xftp) {
  const custom = (body.userServers || []).find((g) => g.operator == null);
  const group = { operator: null };
  for (const [key, addr] of [['smpServers', smp], ['xftpServers', xftp]]) {
    const want = parseServer(addr);
    const deletions = [];
    const updates = [];
    let matched = false;
    for (const ex of custom?.[key] ?? []) {
      const a = parseServer(ex.server);
      if (a && !matched && sameRelay(a, want)) {
        matched = true;
        updates.push({ ...ex, server: addr, enabled: true, deleted: false, roles: {} });
      } else deletions.push({ ...ex, deleted: true });
    }
    const list = [...deletions, ...updates];
    if (!matched) list.push({ serverId: null, server: addr, preset: false, enabled: true, roles: {}, deleted: false });
    group[key] = list;
  }
  group.chatRelays = custom?.chatRelays ?? [];
  return [group];
}

/** Null when the effective SMP and XFTP servers are exactly ours (mirrors verifyUserServers). */
function verifyUserServers(body, smp, xftp) {
  if (body?.type !== 'userServers') return 'no_user_servers';
  const groups = body.userServers || [];
  const opDomains = [];
  for (const g of groups) {
    const op = g.operator;
    if (!op) continue;
    if (op.enabled !== false) return 'operator_enabled';
    for (const d of op.serverDomains || []) if (d) opDomains.push(String(d).toLowerCase());
  }
  for (const [scheme, key, addr] of [['smp', 'smpServers', smp], ['xftp', 'xftpServers', xftp]]) {
    const want = parseServer(addr);
    if (want.hosts.some((h) => opDomains.some((d) => h.endsWith(d)))) return `${scheme}_host_matches_operator_domain`;
    const eff = [];
    let leftover = false;
    for (const g of groups) {
      const op = g.operator;
      const opEn = !op || op.enabled !== false;
      for (const s of g[key] || []) {
        if (!op && !s.deleted && !s.enabled) leftover = true;
        if (opEn && s.enabled && !s.deleted) {
          const a = parseServer(s.server);
          if (!a) return `${scheme}_unparsable_server`;
          eff.push(a);
        }
      }
    }
    if (!eff.length) return `${scheme}_no_effective_server`;
    if (eff.some((e) => !sameRelayExact(e, want))) return `${scheme}_unexpected_server`;
    if (leftover) return `${scheme}_leftover_custom_server`;
  }
  return null;
}

// ----------------------------------------------------------------------------------------------- one CLI process

class CliProc {
  constructor(h, idx) {
    this.h = h;
    this.idx = idx;
    this.port = h.o.basePort + idx;
    this.dir = path.join(h.dbDir, `p${pad(idx)}`);
    this.corr = 0;
    this.pending = new Map();
    this.chain = Promise.resolve();
    this.active = null;
    this.bootUserId = null;
    this.ws = null;
    this.child = null;
    this.exited = false;
  }

  get tag() { return `p${pad(this.idx)}`; }

  async start() {
    const h = this.h;
    fs.mkdirSync(this.dir, { recursive: true });
    const fresh = !fs.existsSync(path.join(this.dir, 'sx_chat.db'));
    const files = path.join(this.dir, 'files');
    const tmp = path.join(this.dir, 'tmp');
    fs.mkdirSync(files, { recursive: true });
    fs.mkdirSync(tmp, { recursive: true });
    // Servers without the password here (no secret on the command line); the password is added per user through
    // /_servers, exactly like the engine. Passing both -s and --xftp-server leaves no preset operator at all.
    const args = [
      '-d', path.join(this.dir, 'sx'), '-p', String(this.port),
      '-s', stripPw(h.smp), '--xftp-server', stripPw(h.xftp),
      '--smp-proxy', 'unknown', '--smp-proxy-fallback', 'no', '--log-hosts', '-y',
      '--files-folder', files, '--temp-folder', tmp,
    ];
    if (fresh) args.push('--user-display-name', `boot${pad(this.idx)}`);
    args.push('+RTS', ...h.o.rts.split(/\s+/).filter(Boolean), '-RTS');
    const log = fs.createWriteStream(path.join(h.logDir, `cli-${this.tag}.log`), { flags: 'a' });
    this.child = spawn(h.o.cli, args, {
      env: { ...process.env, DYLD_FALLBACK_LIBRARY_PATH: h.o.dyld },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const pid = this.child.pid;
    h.pids.add(pid);
    h.writePids();
    // The WebSocket server echoes every command ("received command ..."), which would include the password.
    const onLine = (src) => (line) => {
      if (!line || line.startsWith('received command')) return;
      log.write(`${new Date().toISOString()} ${src} ${redact(line)}\n`);
    };
    readline.createInterface({ input: this.child.stdout }).on('line', onLine('out'));
    readline.createInterface({ input: this.child.stderr }).on('line', onLine('err'));
    this.child.on('exit', (code, sig) => {
      this.exited = true;
      log.write(`${new Date().toISOString()} exit code=${code} sig=${sig}\n`);
      h.pids.delete(pid);
      h.writePids();
      if (!h.stopping) { h.log(`!! CLI ${this.tag} exited code=${code} sig=${sig}`); h.count('harness:cliExited'); }
      for (const p of this.pending.values()) p.reject(new Error('cli exited'));
      this.pending.clear();
    });
    await this.connect();
  }

  async connect() {
    const deadline = now() + 90000;
    for (;;) {
      if (this.exited) throw new Error(`${this.tag} exited before the WebSocket opened`);
      try {
        this.ws = await new Promise((res, rej) => {
          const w = new WebSocket(`ws://127.0.0.1:${this.port}`);
          w.onopen = () => res(w);
          w.onerror = () => rej(new Error('ws error'));
        });
        break;
      } catch (e) {
        if (now() > deadline) throw e;
        await sleep(300);
      }
    }
    this.ws.onmessage = (ev) => this.onMessage(ev.data);
    this.ws.onclose = () => { if (!this.h.stopping) { this.h.log(`!! WebSocket closed ${this.tag}`); this.h.count('harness:wsClosed'); } };
  }

  onMessage(data) {
    let m;
    try { m = JSON.parse(typeof data === 'string' ? data : Buffer.from(data).toString()); } catch { this.h.count('harness:badJson'); return; }
    const r = m.resp;
    if (m.corrId != null) {
      const p = this.pending.get(m.corrId);
      if (p) { this.pending.delete(m.corrId); p.resolve(r); } else this.h.count('harness:lateResponse');
      return;
    }
    try { this.h.onEvent(this, r); } catch (e) { this.h.count('harness:eventHandler'); this.h.logErr('harness:eventHandler', e.stack); }
  }

  /**
   * Runs one command as [userId] (null: no switch). The WebSocket server runs one client's commands strictly in
   * order, and most API commands act on the *active* user, so commands are serialised per process and an
   * `/_user <id>` switch is inserted when needed.
   */
  cmd(userId, text, o = {}) {
    const timeout = o.timeout ?? 180000;
    const run = async () => {
      if (this.exited) throw new Error('cli exited');
      if (userId != null && userId !== this.active) {
        const r = await this.raw(`/_user ${userId}`, timeout);
        if (r?.type !== 'activeUser') { this.active = null; throw new Error(`/_user ${errTag(r)}`); }
        this.active = userId;
      }
      o.onStart?.();
      const t0 = now();
      const r = await this.raw(text, timeout);
      this.h.cmdTime(cmdName(text), now() - t0);
      if (o.setsActive && r?.type === 'activeUser') this.active = r.user.userId;
      return r;
    };
    const p = this.chain.then(run, run);
    this.chain = p.then(() => {}, () => {});
    return p;
  }

  raw(text, timeout) {
    const corrId = String(++this.corr);
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => {
        if (this.pending.delete(corrId)) { this.active = null; reject(new Error(`timeout ${cmdName(text)}`)); }
      }, timeout);
      this.pending.set(corrId, {
        resolve: (r) => { clearTimeout(t); resolve(r); },
        reject: (e) => { clearTimeout(t); reject(e); },
      });
      try { this.ws.send(JSON.stringify({ corrId, cmd: text })); } catch (e) { this.pending.delete(corrId); clearTimeout(t); reject(e); }
    });
  }

  async stop() {
    if (!this.child || this.exited) return;
    try { this.ws?.close(); } catch { /* closing anyway */ }
    this.child.kill('SIGINT');
    for (let i = 0; i < 50 && !this.exited; i++) await sleep(200);
    if (!this.exited) this.child.kill('SIGTERM');
    for (let i = 0; i < 25 && !this.exited; i++) await sleep(200);
    if (!this.exited) this.child.kill('SIGKILL');
  }
}

// ----------------------------------------------------------------------------------------------------- harness

class LoadTest {
  constructor(o) {
    this.o = o;
    this.runDir = path.resolve(o['run-dir']);
    this.dbDir = path.join(this.runDir, 'db');
    this.logDir = path.join(this.runDir, 'logs');
    this.procs = [];
    this.groups = [];
    this.profiles = [];
    this.byKey = new Map();
    this.simNames = new Set();
    this.msgs = new Map();
    this.itemIdx = new Map();
    this.pendingStatus = new Map();
    this.specialIdx = new Map();
    this.counts = {};
    this.phaseCounts = {};
    this.phaseEvents = {};
    this.cmdTimes = {};
    this.errSamples = {};
    this.serverSamples = [];
    this.harnessSamples = [];
    this.landing = [];
    this.pids = new Set();
    this.inflight = new Set();
    this.phaseMsgs = [];
    this.trip = null;
    this.trips = [];
    this.abort = null;
    this.stopping = false;
    this.seq = 0;
    this.stepIdx = 0;
    this.phase = 'init';
    this.landingBad = 0;
    this.lastServer = null;
    this.lastServerAt = 0;
    this.loadHighSince = null;
    this.clk = 100;
    this.results = { meta: {}, setup: [], steps: [], trips: this.trips, teardown: null, counts: this.counts };
  }

  // ---- logging and counters

  log(...a) {
    const line = `${new Date().toISOString()} ${redact(a.join(' '))}`;
    console.log(line);
    this.logStream?.write(line + '\n');
  }

  logErr(tag, detail) {
    this.errSamples[tag] = (this.errSamples[tag] || 0) + 1;
    if (this.errSamples[tag] <= 5) this.errStream?.write(`${new Date().toISOString()} ${tag} ${redact(String(detail)).slice(0, 2000)}\n`);
  }

  count(tag, n = 1) {
    this.counts[tag] = (this.counts[tag] || 0) + n;
    this.phaseCounts[tag] = (this.phaseCounts[tag] || 0) + n;
  }

  cmdTime(name, ms) { (this.cmdTimes[name] ??= []).push(ms); }

  writePids() {
    try { fs.writeFileSync(path.join(this.runDir, 'pids.txt'), [process.pid, ...this.pids].join('\n') + '\n'); } catch { /* best effort */ }
  }

  tripNow(reason) {
    if (this.trip) return;
    this.trip = { reason, at: new Date().toISOString(), phase: this.phase, step: this.stepIdx };
    this.trips.push(this.trip);
    this.log(`!!! STOP THRESHOLD: ${reason} (phase ${this.phase})`);
  }

  halt() { return !!(this.trip || this.abort); }

  async sleepHalt(ms) {
    const end = now() + ms;
    while (now() < end && !this.halt()) await sleep(Math.max(1, Math.min(1000, end - now())));
  }

  // ---- monitoring: relay over one SSH session (read only), landing page, the harness itself

  startMonitor() {
    if (this.o['no-monitor']) { this.log('server monitor disabled (--no-monitor)'); return; }
    const inv = readProps(this.o.inventory);
    const alias = inv.VPS_SSH_ALIAS;
    if (!alias) throw new Error('VPS_SSH_ALIAS missing in inventory');
    // Read-only: /proc, ss, and counting record types in the relay's store log. Prints numbers only.
    const script = `
SL=/var/opt/simplex/smp-server-store.log
i=0; C=0; D=0
echo "CLK $(getconf CLK_TCK)"
while true; do
  PID=$(pgrep -xo smp-server)
  if [ -n "$PID" ] && [ -r /proc/$PID/stat ]; then
    set -- $(awk '{print $14, $15}' /proc/$PID/stat); UT=$1; ST=$2
    RSS=$(awk '/^VmRSS/{print $2}' /proc/$PID/status)
    IO=$(awk '/^rchar|^wchar/{printf "%s ", $2}' /proc/$PID/io 2>/dev/null); [ -z "$IO" ] && IO="0 0 "
  else PID=0; UT=0; ST=0; RSS=0; IO="0 0 "; fi
  read -r L1 L5 L15 REST < /proc/loadavg
  AV=$(awk '/^MemAvailable/{print $2}' /proc/meminfo)
  SW=$(awk '/^SwapFree/{print $2}' /proc/meminfo)
  CPU=$(awk '/^cpu /{print $2+$3+$4+$5+$6+$7+$8+$9, $5+$6}' /proc/stat)
  CON=$(ss -tnH state established '( sport = :5223 )' | wc -l)
  if [ $((i % 3)) -eq 0 ] && [ -r $SL ]; then C=$(grep -c '^CREATE ' $SL); D=$(grep -c '^DELETE ' $SL); fi
  echo "S $(date +%s) $PID $L1 $L5 $AV $UT $ST $RSS $CON $CPU \${IO}$C $D $SW"
  i=$((i+1))
  sleep 10
done
`;
    const run = () => {
      if (this.stopping) return;
      const child = spawn('ssh', ['-o', 'BatchMode=yes', '-o', 'ServerAliveInterval=15', '-o', 'ConnectTimeout=20', alias, 'bash -s'], { stdio: ['pipe', 'pipe', 'pipe'] });
      this.monitorChild = child;
      this.pids.add(child.pid);
      this.writePids();
      child.stdin.end(script);
      readline.createInterface({ input: child.stdout }).on('line', (l) => this.onServerLine(l));
      readline.createInterface({ input: child.stderr }).on('line', (l) => this.log('monitor stderr:', l.replace(/\d{1,3}(\.\d{1,3}){3}/g, '<ip>')));
      child.on('exit', (code) => {
        this.pids.delete(child.pid);
        this.writePids();
        if (!this.stopping) { this.log(`monitor ssh exited (${code}); restarting in 5 s`); this.count('harness:monitorRestart'); setTimeout(run, 5000); }
      });
    };
    run();
    this.serverCsv = fs.createWriteStream(path.join(this.runDir, 'server-samples.csv'));
    this.serverCsv.write('time,phase,load1,load5,availMB,swapFreeMB,smpCpuPct,sysBusyPct,smpRssMB,conns,queues,creates,deletes,readKBps,writeKBps\n');
    // No sample for 45 s while load runs = no protection for the owner's sites: stop.
    this.watchdog = setInterval(() => {
      if (this.loadActive && this.lastServerAt && now() - this.lastServerAt > 45000) this.tripNow('server monitor lost for 45 s');
    }, 5000);
  }

  onServerLine(line) {
    const f = line.trim().split(/\s+/);
    if (f[0] === 'CLK') { this.clk = +f[1] || 100; return; }
    if (f[0] !== 'S' || f.length < 17) return;
    const [, ts, pid, l1, l5, avKB, ut, st, rssKB, conns, cpuT, cpuI, rch, wch, cr, de, swKB] = f;
    const cur = {
      t: now(), ts: +ts, pid: +pid, load1: +l1, load5: +l5, availMB: Math.round(+avKB / 1024), swapFreeMB: Math.round(+swKB / 1024),
      cpuTicks: +ut + +st, rssMB: Math.round(+rssKB / 1024), conns: +conns, cpuT: +cpuT, cpuI: +cpuI,
      rchar: +rch, wchar: +wch, creates: +cr, deletes: +de, queues: +cr - +de, phase: this.phase,
    };
    const prev = this.lastServer;
    if (prev && prev.pid === cur.pid && cur.ts > prev.ts) {
      const dt = cur.ts - prev.ts;
      cur.smpCpuSecDelta = (cur.cpuTicks - prev.cpuTicks) / this.clk;
      cur.smpCpu = round((cur.smpCpuSecDelta / dt) * 100, 1);
      cur.sysBusy = round(100 * (1 - (cur.cpuI - prev.cpuI) / Math.max(1, cur.cpuT - prev.cpuT)), 1);
      cur.rKBps = round((cur.rchar - prev.rchar) / 1024 / dt, 1);
      cur.wKBps = round((cur.wchar - prev.wchar) / 1024 / dt, 1);
    }
    this.lastServer = cur;
    this.lastServerAt = cur.t;
    this.serverSamples.push(cur);
    this.serverCsv?.write([new Date(cur.t).toISOString(), cur.phase, cur.load1, cur.load5, cur.availMB, cur.swapFreeMB, cur.smpCpu ?? '', cur.sysBusy ?? '', cur.rssMB, cur.conns, cur.queues, cur.creates, cur.deletes, cur.rKBps ?? '', cur.wKBps ?? ''].join(',') + '\n');
    const o = this.o;
    if (cur.load1 > o.maxLoad) {
      this.loadHighSince ??= cur.t;
      if (cur.t - this.loadHighSince >= o.loadWindow * 1000) this.tripNow(`server load average ${cur.load1} above ${o.maxLoad} for ${o.loadWindow} s`);
    } else this.loadHighSince = null;
    if (cur.availMB < o.minAvailMb) this.tripNow(`server available memory ${cur.availMB} MB below ${o.minAvailMb} MB`);
    if (cur.rssMB > o.maxRssMb) this.tripNow(`smp-server RSS ${cur.rssMB} MB above ${o.maxRssMb} MB`);
  }

  probeLanding() {
    const t0 = now();
    let done = false;
    const fin = (code) => {
      if (done) return;
      done = true;
      const ms = now() - t0;
      this.landing.push({ t: t0, ms, code, phase: this.phase });
      this.landingCsv?.write(`${new Date(t0).toISOString()},${this.phase},${ms},${code}\n`);
      if (ms > this.o.maxLandingMs || code !== 200) {
        this.landingBad++;
        if (this.landingBad >= 2) this.tripNow(`landing page slow or failing twice in a row (${ms} ms, status ${code})`);
      } else this.landingBad = 0;
    };
    const req = https.get(this.o.landingUrl, { agent: false, timeout: 10000, headers: { 'User-Agent': 'encrogram-loadtest' } }, (res) => {
      res.on('data', () => {});
      res.on('end', () => fin(res.statusCode));
      res.on('error', () => fin(0));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', () => fin(0));
  }

  sampleHarness() {
    const pids = [process.pid, ...this.procs.filter((p) => p.child && !p.exited).map((p) => p.child.pid)];
    execFile('ps', ['-o', 'pid=,%cpu=,rss=', '-p', pids.join(',')], (err, out) => {
      if (err) return;
      let cpu = 0;
      let rss = 0;
      let maxProc = 0;
      for (const l of out.trim().split('\n')) {
        const [pid, c, r] = l.trim().split(/\s+/).map(Number);
        cpu += c;
        rss += r;
        if (pid !== process.pid) maxProc = Math.max(maxProc, r);
      }
      const s = {
        t: now(), phase: this.phase, cpuPct: round(cpu), rssMB: Math.round(rss / 1024), maxProcRssMB: Math.round(maxProc / 1024),
        nodeRssMB: Math.round(process.memoryUsage().rss / 1048576), macLoad1: round(os.loadavg()[0], 2),
      };
      this.harnessSamples.push(s);
      this.harnessCsv?.write(`${new Date(s.t).toISOString()},${s.phase},${s.cpuPct},${s.rssMB},${s.maxProcRssMB},${s.nodeRssMB},${s.macLoad1}\n`);
      if (s.rssMB > this.o.maxHarnessMb) this.tripNow(`harness RAM ${s.rssMB} MB above the ${this.o.maxHarnessMb} MB budget`);
    });
  }

  nettop() {
    return new Promise((res) => {
      execFile('nettop', ['-P', '-L', '1', '-x', '-n', '-J', 'bytes_in,bytes_out'], { timeout: 20000, maxBuffer: 16 << 20 }, (err, out) => {
        const m = new Map();
        if (!err) for (const line of out.split('\n')) { const x = /\.(\d+),(\d+),(\d+),/.exec(line); if (x) m.set(+x[1], { i: +x[2], o: +x[3] }); }
        res(m);
      });
    });
  }

  // ---- events from the CLI processes

  onEvent(proc, r) {
    if (!r || typeof r !== 'object') return;
    const type = r.type || '?';
    this.phaseEvents[type] = (this.phaseEvents[type] || 0) + 1;
    const p = r.user ? this.byKey.get(`${proc.idx}:${r.user.userId}`) : null;
    switch (type) {
      case 'newChatItems':
        if (p) for (const aci of r.chatItems || []) this.onItem(p, aci);
        break;
      case 'chatItemsStatusesUpdated':
        if (p) for (const aci of r.chatItems || []) this.onStatus(p, aci.chatItem);
        break;
      case 'contactConnected':
        if (p) this.onContact(p, r.contact);
        break;
      case 'receivedGroupInvitation':
        if (p) p.invitedGroupId = r.groupInfo?.groupId;
        break;
      case 'userJoinedGroup':
        if (p) { p.joined = true; this.addConn(p, r.hostMember); }
        break;
      case 'joinedGroupMember':
      case 'connectedToGroupMember':
        if (p) this.addConn(p, r.member);
        break;
      case 'groupDeleted':
        if (p) p.groupDeleted = true;
        break;
      case 'chatError':
        this.countErr('event', r.chatError, r);
        break;
      case 'chatErrors':
        for (const e of r.chatErrors || []) this.countErr('event', e, r);
        break;
      case 'messageError': {
        const tag = `event:messageError:${r.severity}:${String(r.errorMessage || '').replace(/[0-9a-f]{8,}/gi, '#').slice(0, 60)}`;
        this.count(tag);
        this.logErr(tag, JSON.stringify(r));
        break;
      }
      case 'hostDisconnected': this.count('net:hostDisconnected'); break;
      case 'hostConnected': this.count('net:hostConnected'); break;
      case 'chatCmdError': this.count(`cmd:orphan:${errTag(r)}`); break;
      default: break;
    }
  }

  countErr(source, chatError, raw) {
    const tag = `${source}:${typeTags(chatError) || 'unknown'}`;
    this.count(tag);
    this.logErr(tag, JSON.stringify(raw));
  }

  onContact(p, contact) {
    const n = contact?.profile?.displayName;
    if (!n) return;
    if (p.role === 'owner') p.contacts.set(n, contact.contactId);
    else if (n === p.group.owner.name) p.ownerContactId = contact.contactId;
  }

  addConn(p, member) {
    const n = member?.memberProfile?.displayName;
    if (!n) return;
    p.connected.add(n);
    if (!this.simNames.has(n) && !p.group.extra.has(n)) {
      p.group.extra.add(n);
      this.log(`a non-simulated member joined ${p.group.name}`);
    }
  }

  onItem(p, aci) {
    const ci = aci?.chatItem;
    if (!ci || aci.chatInfo?.type !== 'group' || ci.chatDir?.type !== 'groupRcv') return;
    const c = ci.content;
    if (c?.type !== 'rcvMsgContent') return;
    const text = c.msgContent?.text || '';
    const m = /^lt (\S+) /.exec(text);
    let msg;
    if (m) {
      msg = this.msgs.get(m[1]);
      if (!msg) { this.count('msg:unknownId'); return; }
    } else {
      msg = this.specialIdx.get(`${ci.chatDir.groupMember?.memberProfile?.displayName}|${text}`);
      if (!msg) return; // e.g. typed on the real phone
    }
    if (msg.g !== p.g) { this.count('msg:wrongGroup'); return; }
    if (msg.rcv.has(p.name)) { msg.dup++; this.count('msg:duplicate'); return; }
    msg.rcv.set(p.name, now());
  }

  onStatus(p, ci) {
    if (ci?.chatDir?.type !== 'groupSnd') return;
    const key = `${p.name}:${ci.meta?.itemId}`;
    const msg = this.itemIdx.get(key);
    if (!msg) { (this.pendingStatus.get(key) ?? this.pendingStatus.set(key, []).get(key)).push(ci.meta?.itemStatus); return; }
    this.applyStatus(msg, ci.meta?.itemStatus);
  }

  applyStatus(msg, st) {
    if (!st) return;
    const t = now();
    if (st.type === 'sndSent' && st.sndProgress === 'complete' && !msg.tSent) msg.tSent = t;
    if (st.type === 'sndRcvd') {
      if (!msg.tSent) msg.tSent = t;
      if (st.sndProgress === 'complete' && !msg.tRcvdAll) msg.tRcvdAll = t;
    }
    if (/^snd(Error|Warning)/.test(st.type) && !msg.statusErr) {
      msg.statusErr = `status:${typeTags(st)}`;
      this.count(msg.statusErr);
      this.logErr(msg.statusErr, JSON.stringify(st));
    }
  }

  // ---- layout and process start

  buildLayout(maxGroups) {
    const o = this.o;
    let i = 0;
    for (let g = 1; g <= maxGroups; g++) {
      const group = { idx: g, name: `LT-g${pad(g)}`, members: [], owner: null, sim: [], extra: new Set(), seat: g === o.seatGroup, t: {} };
      const names = [`g${pad(g)}own`, ...Array.from({ length: o.members }, (_, k) => `g${pad(g)}m${pad(k + 1)}`)];
      names.forEach((name, j) => {
        const p = {
          name, g, group, role: j === 0 ? 'owner' : 'member', procIdx: (i++ % o.procs) + 1,
          proc: null, userId: null, contacts: new Map(), ownerContactId: null, invitedGroupId: null, localGroupId: null,
          joined: false, connected: new Set(), groupDeleted: false, fail: null, t: {},
        };
        this.profiles.push(p);
        this.simNames.add(name);
        if (j === 0) group.owner = p; else group.members.push(p);
      });
      this.groups.push(group);
    }
  }

  async startProcs(n) {
    for (let i = 1; i <= n; i++) this.procs.push(new CliProc(this, i));
    await Promise.all(this.procs.map(async (p) => { await p.start(); await this.configureProcess(p); }));
    for (const pr of this.profiles) pr.proc = this.procs[pr.procIdx - 1];
  }

  async configureProcess(proc) {
    const net = await proc.cmd(null, '/network socks=off smp-proxy=unknown smp-proxy-fallback=no');
    if (!networkConfigOk(net)) throw new Error(`${proc.tag}: network config not verified (${errTag(net)})`);
    const ops = await proc.cmd(null, '/_operators');
    if (ops?.type !== 'serverOperatorConditions') throw new Error(`${proc.tag}: operators ${errTag(ops)}`);
    const list = ops.conditions?.serverOperators ?? [];
    if (list.some((op) => op.enabled !== false)) {
      const set = await proc.cmd(null, `/_operators ${JSON.stringify(list.map((op) => ({ ...op, enabled: false })))}`);
      if (!(set?.conditions?.serverOperators ?? []).every((op) => op.enabled === false)) throw new Error(`${proc.tag}: operators not disabled`);
    }
    this.operatorsSeen = Math.max(this.operatorsSeen || 0, list.length);
    const ul = await proc.cmd(null, '/users');
    const users = (ul?.users || []).map((x) => x.user);
    let boot = users.find((u) => /^boot\d+$/.test(u.localDisplayName || ''));
    if (!boot) {
      const r = await proc.cmd(null, `/_create user ${JSON.stringify({ profile: { displayName: `boot${pad(proc.idx)}`, fullName: '' }, pastTimestamp: false })}`, { setsActive: true });
      if (r?.type !== 'activeUser') throw new Error(`${proc.tag}: boot user ${errTag(r)}`);
      boot = r.user;
    }
    proc.bootUserId = boot.userId;
    proc.existingUsers = users;
    await this.configureServers(proc, boot.userId);
  }

  async configureServers(proc, uid) {
    // /_servers <id> is refused unless <id> is the active user (differentActiveUser).
    const cur = await proc.cmd(uid, `/_servers ${uid}`);
    if (cur?.type !== 'userServers') throw new Error(`servers_get ${errTag(cur)}`);
    const set = await proc.cmd(uid, `/_servers ${uid} ${JSON.stringify(customServersUpdate(cur, this.smp, this.xftp))}`);
    if (set?.type !== 'cmdOk') throw new Error(`servers_set ${errTag(set)}`);
    const back = await proc.cmd(uid, `/_servers ${uid}`);
    const problem = verifyUserServers(back, this.smp, this.xftp);
    if (problem) throw new Error(`servers_verify ${problem} (${proc.tag} user ${uid})`);
    this.serversVerified = (this.serversVerified || 0) + 1;
  }

  async createProfile(p) {
    const r = await p.proc.cmd(null, `/_create user ${JSON.stringify({ profile: { displayName: p.name, fullName: '' }, pastTimestamp: false })}`, { setsActive: true });
    if (r?.type !== 'activeUser') throw new Error(`create user ${errTag(r)}`);
    p.userId = r.user.userId;
    this.byKey.set(`${p.proc.idx}:${p.userId}`, p);
    await this.configureServers(p.proc, p.userId);
  }

  // ---- group setup

  async setupGroup(g) {
    const o = this.o;
    const T = g.t;
    const owner = g.owner;
    const all = [owner, ...g.members];
    T.start = now();
    await Promise.all(all.map((p) => this.createProfile(p).catch((e) => { p.fail = `create:${e.message}`; this.count(`setup:create:${e.message.split(' ')[0]}`); })));
    if (owner.fail) throw new Error(`owner ${owner.fail}`);
    T.users = now();

    // 1. contacts: the owner makes a one-time invitation per member, the member connects to it.
    T.contactsStart = now();
    await Promise.all(g.members.filter((m) => !m.fail).map(async (m) => {
      try {
        const inv = await owner.proc.cmd(owner.userId, `/_connect ${owner.userId}`);
        if (inv?.type !== 'invitation') throw new Error(`invite ${errTag(inv)}`);
        m.t.contactStart = now();
        const r = await m.proc.cmd(m.userId, `/_connect ${m.userId} ${inv.connLinkInvitation.connFullLink}`);
        if (!['sentConfirmation', 'sentInvitation'].includes(r?.type)) throw new Error(`connect ${errTag(r)}`);
        if (!await waitUntil(() => owner.contacts.has(m.name) && m.ownerContactId != null, o.contactTimeout * 1000, () => this.halt())) throw new Error('contact-timeout');
        m.t.contact = now();
      } catch (e) {
        m.fail = `contact:${e.message}`;
        this.count(`setup:contact:${e.message.split(' ').slice(0, 2).join(':')}`);
      }
    }));
    T.contacts = now();
    if (this.halt()) throw new Error('halted');

    // 2. group with the app's preferences, then add every connected contact; each member accepts.
    const gc = await owner.proc.cmd(owner.userId, `/_group ${owner.userId} ${JSON.stringify({ displayName: g.name, fullName: '', groupPreferences: { history: { enable: 'on' }, fullDelete: { enable: 'on' } } })}`);
    if (gc?.type !== 'groupCreated') throw new Error(`group ${errTag(gc)}`);
    g.groupId = gc.groupInfo.groupId;
    owner.localGroupId = g.groupId;
    if (g.seat) await this.writeSeatLink(g);
    T.joinStart = now();
    await Promise.all(g.members.filter((m) => !m.fail).map(async (m) => {
      try {
        const cid = owner.contacts.get(m.name);
        m.t.add = now();
        const r = await owner.proc.cmd(owner.userId, `/_add #${g.groupId} ${cid} member`);
        if (r?.type !== 'sentGroupInvitation') throw new Error(`add ${errTag(r)}`);
        if (!await waitUntil(() => m.invitedGroupId != null, o.joinTimeout * 1000, () => this.halt())) throw new Error('invitation-timeout');
        const j = await m.proc.cmd(m.userId, `/_join #${m.invitedGroupId}`);
        if (j?.type !== 'userAcceptedGroupSent') throw new Error(`join ${errTag(j)}`);
        m.localGroupId = m.invitedGroupId;
        if (!await waitUntil(() => m.joined, o.joinTimeout * 1000, () => this.halt())) throw new Error('joined-timeout');
        m.t.joined = now();
      } catch (e) {
        m.fail ??= `join:${e.message}`;
        if (m.invitedGroupId != null) m.localGroupId ??= m.invitedGroupId;
        this.count(`setup:join:${e.message.split(' ').slice(0, 2).join(':')}`);
      }
    }));
    T.joins = now();

    // 3. full mesh: the owner introduces members to each other; wait until every pair is connected.
    g.sim = all.filter((p) => !p.fail && p.localGroupId != null);
    const need = g.sim.length - 1;
    const simSet = new Set(g.sim.map((p) => p.name));
    const meshCount = (p) => [...p.connected].filter((n) => simSet.has(n) && n !== p.name).length;
    let lastPoll = now();
    g.meshComplete = await waitUntil(() => {
      if (now() - lastPoll > 60000) { lastPoll = now(); this.pollMembers(g).catch(() => {}); }
      return g.sim.every((p) => meshCount(p) >= need);
    }, o.meshTimeout * 1000, () => this.halt(), 1000);
    T.mesh = now();
    g.meshPairs = g.sim.reduce((s, p) => s + meshCount(p), 0);
    g.meshPairsNeeded = g.sim.length * need;
    this.log(`${g.name}: ${g.sim.length} simulated members, contacts ${sec(T.contacts - T.contactsStart)} s, joins ${sec(T.joins - T.joinStart)} s, mesh ${g.meshComplete ? 'complete' : 'INCOMPLETE'} ${sec(T.mesh - T.joinStart)} s after first add (${g.meshPairs}/${g.meshPairsNeeded} directed links)`);
  }

  /** Merges `/_members` statuses into the event-based connection sets (in case an event was missed). */
  async pollMembers(g) {
    const ok = new Set(['connected', 'complete', 'creator']);
    const out = { profiles: 0, connectedLinks: 0, statuses: {} };
    await Promise.all(g.sim.map(async (p) => {
      const r = await p.proc.cmd(p.userId, `/_members #${p.localGroupId}`).catch(() => null);
      if (r?.type !== 'groupMembers') { this.count(`setup:members:${errTag(r)}`); return; }
      out.profiles++;
      for (const m of r.group?.members || []) {
        const n = m.memberProfile?.displayName;
        out.statuses[m.memberStatus] = (out.statuses[m.memberStatus] || 0) + 1;
        if (n && ok.has(m.memberStatus)) { p.connected.add(n); if (this.simNames.has(n)) out.connectedLinks++; }
      }
    }));
    return out;
  }

  async writeSeatLink(g) {
    const r = await g.owner.proc.cmd(g.owner.userId, `/_create link #${g.groupId} member`);
    if (r?.type !== 'groupLinkCreated') { this.log(`seat link for ${g.name} failed: ${errTag(r)}`); this.count(`setup:seatLink:${errTag(r)}`); return; }
    const l = r.groupLink.connLinkContact;
    const file = path.join(this.runDir, 'group1-link.txt');
    fs.writeFileSync(file, `${l.connShortLink ?? l.connFullLink}\n${l.connShortLink ? `${l.connFullLink}\n` : ''}`, { mode: 0o600 });
    g.linkAt = now();
    this.log(`invite link for the real phone (${g.name}) written to ${file}`);
  }

  groupSetupSummary(g) {
    const T = g.t;
    const fails = {};
    for (const p of [g.owner, ...g.members]) if (p.fail) { const k = p.fail.split(' ')[0]; fails[k] = (fails[k] || 0) + 1; }
    return {
      group: g.name,
      simulated: g.sim.length,
      failures: fails,
      usersSec: sec(T.users - T.start),
      contactsSec: sec(T.contacts - T.contactsStart),
      contactPerMemberMs: summary(g.members.filter((m) => m.t.contact).map((m) => m.t.contact - m.t.contactStart)),
      joinsSec: sec(T.joins - T.joinStart),
      joinPerMemberMs: summary(g.members.filter((m) => m.t.joined).map((m) => m.t.joined - m.t.add)),
      meshComplete: g.meshComplete,
      meshSecFromFirstAdd: sec(T.mesh - T.joinStart),
      meshSecAfterJoins: sec(T.mesh - T.joins),
      meshDirectedLinks: `${g.meshPairs}/${g.meshPairsNeeded}`,
      verify: g.verify,
      totalSec: sec(T.mesh - T.start),
    };
  }

  // ---- phases and messages

  async beginPhase(label) {
    this.phase = label;
    this.phaseCounts = {};
    this.phaseEvents = {};
    this.cmdTimes = {};
    this.phaseMsgs = [];
    this.phaseStart = now();
    this.netStart = await this.nettop();
    this.log(`--- phase ${label}`);
  }

  async endPhase(label, extra = {}) {
    const t0 = this.phaseStart;
    const t1 = now();
    const net = await this.nettop();
    const cli = this.procs.filter((p) => p.child).map((p) => p.child.pid);
    let bin = 0;
    let bout = 0;
    for (const pid of cli) {
      const a = this.netStart?.get(pid);
      const b = net.get(pid);
      if (a && b) { bin += b.i - a.i; bout += b.o - a.o; }
    }
    const cmd = {};
    for (const [k, v] of Object.entries(this.cmdTimes)) if (k !== '/_user') cmd[k] = summary(v);
    const errors = Object.fromEntries(Object.entries(this.phaseCounts).sort((a, b) => b[1] - a[1]));
    const events = Object.fromEntries(Object.entries(this.phaseEvents).sort((a, b) => b[1] - a[1]).slice(0, 25));
    const res = {
      label, durationSec: sec(t1 - t0), ...extra,
      server: this.serverWindow(t0, t1),
      harness: this.harnessWindow(t0, t1),
      landingMs: stats(this.landing.filter((x) => x.t >= t0 && x.t <= t1).map((x) => x.ms)),
      macNet: { bytesIn: bin, bytesOut: bout },
      cmdMs: cmd, counts: errors, events,
    };
    fs.writeFileSync(path.join(this.runDir, 'results.json'), JSON.stringify(this.results, null, 2));
    return res;
  }

  serverWindow(t0, t1) {
    const s = this.serverSamples.filter((x) => x.t >= t0 && x.t <= t1 + 1000);
    if (!s.length) return null;
    const col = (k) => s.map((x) => x[k]);
    const cpuSec = s.reduce((a, x) => a + (x.smpCpuSecDelta || 0), 0);
    return {
      samples: s.length, load1: stats(col('load1')), availMB: stats(col('availMB')), swapFreeMB: stats(col('swapFreeMB')),
      smpCpuPct: stats(col('smpCpu')), sysBusyPct: stats(col('sysBusy')), smpRssMB: stats(col('rssMB')),
      conns: stats(col('conns')), queues: stats(col('queues')), readKBps: stats(col('rKBps')), writeKBps: stats(col('wKBps')),
      smpCpuSec: round(cpuSec, 2),
    };
  }

  harnessWindow(t0, t1) {
    const s = this.harnessSamples.filter((x) => x.t >= t0 && x.t <= t1 + 1000);
    if (!s.length) return null;
    return { cpuPct: stats(s.map((x) => x.cpuPct)), rssMB: stats(s.map((x) => x.rssMB)), maxProcRssMB: stats(s.map((x) => x.maxProcRssMB)), macLoad1: stats(s.map((x) => x.macLoad1)) };
  }

  sendMsg(p, g, phase, special = null) {
    const id = `${this.stepIdx}.${++this.seq}`;
    const msg = {
      id, g: g.idx, s: p.name, phase, exp: g.sim.length - 1, tIssued: now(), tStart: 0, tAcc: 0, itemId: null, err: null,
      rcv: new Map(), dup: 0, tSent: 0, tRcvdAll: 0, statusErr: null, special: !!special,
    };
    this.msgs.set(id, msg);
    this.phaseMsgs.push(msg);
    const text = special ?? `lt ${id} ${p.name} hello ${Math.random().toString(36).slice(2, 8)}`;
    if (special) this.specialIdx.set(`${p.name}|${special}`, msg);
    const cmd = `/_send #${p.localGroupId} json ${asciiJson([{ msgContent: { type: 'text', text }, mentions: {} }])}`;
    const pr = p.proc.cmd(p.userId, cmd, { onStart: () => { msg.tStart = now(); } }).then((r) => {
      msg.tAcc = now();
      const ci = r?.type === 'newChatItems' ? r.chatItems?.[0]?.chatItem : null;
      if (ci) {
        msg.itemId = ci.meta.itemId;
        const key = `${p.name}:${msg.itemId}`;
        this.itemIdx.set(key, msg);
        this.applyStatus(msg, ci.meta.itemStatus);
        for (const st of this.pendingStatus.get(key) || []) this.applyStatus(msg, st);
        this.pendingStatus.delete(key);
      } else {
        msg.err = `cmd:send:${errTag(r)}`;
        this.count(msg.err);
        this.logErr(msg.err, JSON.stringify(r));
      }
    }, (e) => {
      msg.err = `harness:send:${e.message}`;
      this.count(msg.err);
    }).finally(() => this.inflight.delete(pr));
    this.inflight.add(pr);
  }

  async drain(maxSec) {
    const msgs = this.phaseMsgs;
    await waitUntil(() => this.inflight.size === 0, 120000, () => !!this.abort, 500);
    const delivered = () => msgs.every((m) => m.err || m.rcv.size >= m.exp);
    const ticked = () => msgs.every((m) => m.err || m.tRcvdAll || m.statusErr);
    const t0 = now();
    await waitUntil(delivered, maxSec * 1000, () => !!this.abort, 500);
    await waitUntil(ticked, Math.max(0, maxSec * 1000 - (now() - t0)), () => !!this.abort, 500);
  }

  msgStats(msgs) {
    const ok = msgs.filter((m) => !m.err);
    const lat = [];
    const latByGroup = {};
    let exp = 0;
    let got = 0;
    let dup = 0;
    let lastRcv = 0;
    let firstStart = Infinity;
    for (const m of ok) {
      exp += m.exp;
      got += Math.min(m.rcv.size, m.exp);
      dup += m.dup;
      firstStart = Math.min(firstStart, m.tStart);
      for (const t of m.rcv.values()) { lat.push(t - m.tStart); lastRcv = Math.max(lastRcv, t); }
      const lg = (latByGroup[m.g] ??= { exp: 0, got: 0 });
      lg.exp += m.exp;
      lg.got += Math.min(m.rcv.size, m.exp);
    }
    const lostByGroup = Object.fromEntries(Object.entries(latByGroup).filter(([, v]) => v.got < v.exp).map(([k, v]) => [`g${k}`, v.exp - v.got]));
    const spanSec = lastRcv > firstStart ? (lastRcv - firstStart) / 1000 : null;
    return {
      sent: msgs.length,
      sendFailed: msgs.length - ok.length,
      sendErrors: Object.entries(msgs.filter((m) => m.err).reduce((a, m) => ((a[m.err] = (a[m.err] || 0) + 1), a), {})),
      statusErrors: ok.filter((m) => m.statusErr).length,
      deliveriesExpected: exp,
      deliveriesReceived: got,
      lost: exp - got,
      lostByGroup,
      duplicates: dup,
      latencyMs: summary(lat),
      sendCmdMs: summary(ok.map((m) => m.tAcc - m.tStart)),
      harnessQueueMs: summary(msgs.filter((m) => m.tStart).map((m) => m.tStart - m.tIssued)),
      oneTickMs: summary(ok.filter((m) => m.tSent).map((m) => m.tSent - m.tStart)),
      doubleTickMs: summary(ok.filter((m) => m.tRcvdAll).map((m) => m.tRcvdAll - m.tStart)),
      doubleTickReached: `${ok.filter((m) => m.tRcvdAll).length}/${ok.length}`,
      deliveriesPerSecOverSpan: spanSec ? round(got / spanSec, 1) : null,
    };
  }

  writeMsgs(label, msgs) {
    const f = fs.createWriteStream(path.join(this.runDir, `messages-${label}.jsonl`));
    for (const m of msgs) {
      f.write(JSON.stringify({ id: m.id, g: m.g, s: m.s, err: m.err, statusErr: m.statusErr, exp: m.exp, rcv: m.rcv.size, queueMs: m.tStart - m.tIssued, sendMs: m.tAcc - m.tStart, tick1: m.tSent ? m.tSent - m.tStart : null, tick2: m.tRcvdAll ? m.tRcvdAll - m.tStart : null, lat: [...m.rcv.values()].map((t) => t - m.tStart) }) + '\n');
    }
    f.end();
  }

  async steadyPhase(groups, durSec, label) {
    const o = this.o;
    await this.beginPhase(label);
    const senders = groups.flatMap((g) => g.sim.map((p) => [p, g]));
    const end = now() + durSec * 1000;
    const seat = groups.find((g) => g.seat);
    const seatSenders = seat ? seat.sim.filter((p) => p.role === 'member') : [];
    if (seatSenders.length) {
      SEAT_TEXTS.forEach((t, k) => setTimeout(() => {
        if (!this.halt() && now() < end) this.sendMsg(seatSenders[k % seatSenders.length], seat, label, t);
      }, rand(5000, Math.max(6000, durSec * 1000 - 15000))));
    }
    await Promise.all(senders.map(async ([p, g]) => {
      let next = now() + rand(0, o.maxGap * 1000);
      while (!this.halt()) {
        if (next >= end) break;
        while (now() < next && !this.halt()) await sleep(Math.min(1000, next - now()));
        if (this.halt()) break;
        this.sendMsg(p, g, label);
        next = now() + rand(o.minGap * 1000, o.maxGap * 1000);
      }
    }));
    const sendEnd = now();
    await this.drain(o.drainSec);
    const msgs = this.phaseMsgs;
    const res = await this.endPhase(label, {
      groups: groups.length, senders: senders.length, sendWindowSec: sec(sendEnd - this.phaseStart),
      offeredMsgPerSec: round(msgs.length / ((sendEnd - this.phaseStart) / 1000), 2), messages: this.msgStats(msgs),
    });
    this.writeMsgs(label, msgs);
    return res;
  }

  async burstPhase(groups, label) {
    const o = this.o;
    await this.beginPhase(label);
    const senders = groups.flatMap((g) => g.sim.map((p) => [p, g]));
    for (const [p, g] of senders) setTimeout(() => { if (!this.halt()) this.sendMsg(p, g, label); }, rand(0, o.burstSec * 1000));
    await sleep(o.burstSec * 1000 + 200);
    await this.drain(o.drainSec);
    const msgs = this.phaseMsgs;
    const res = await this.endPhase(label, { groups: groups.length, senders: senders.length, messages: this.msgStats(msgs) });
    this.writeMsgs(label, msgs);
    return res;
  }

  async setupStep(newGroups) {
    const label = `s${this.stepIdx}-setup`;
    await this.beginPhase(label);
    const t0 = now();
    await Promise.all(newGroups.map((g) => this.setupGroup(g).catch((e) => { g.fail = e.message; g.sim = g.sim.length ? g.sim : []; this.log(`${g.name} setup failed: ${e.message}`); })));
    const t1 = now();
    if (!this.abort) await Promise.all(newGroups.filter((g) => g.sim.length).map(async (g) => { g.verify = await this.pollMembers(g); }));
    const res = await this.endPhase(label, { wallSec: sec(t1 - t0), groups: newGroups.map((g) => this.groupSetupSummary(g)) });
    this.results.setup.push(res);
    return res;
  }

  // ---- teardown

  /** Before teardown: the rendering samples once more (newest in history), and the seat group up for --seat-min-sec. */
  async seatHold() {
    const g = this.groups.find((x) => x.seat && x.groupId != null && x.linkAt);
    if (!g) return;
    const senders = g.sim.filter((p) => p.role === 'member' && !p.proc?.exited);
    if (senders.length) {
      this.phase = 'seat-samples';
      SEAT_TEXTS.forEach((t, k) => this.sendMsg(senders[k % senders.length], g, 'seat-samples', t));
      await waitUntil(() => this.inflight.size === 0, 60000, () => false, 500);
      await sleep(10000);
    }
    const left = g.linkAt + this.o.seatMinSec * 1000 - now();
    if (left > 0) { this.log(`keeping ${g.name} up ${sec(left)} s more for the real phone`); await sleep(left); }
    this.results.seat = { group: g.name, linkWrittenAt: new Date(g.linkAt).toISOString(), realMembersJoined: g.extra.size };
  }

  async teardown() {
    this.loadActive = false;
    await this.beginPhase('teardown');
    const T = { queuesBefore: this.lastServer?.queues ?? null, queuesPeak: Math.max(0, ...this.serverSamples.map((s) => s.queues || 0)) };
    await waitUntil(() => this.inflight.size === 0, 60000, () => false, 500);
    const ready = this.groups.filter((g) => g.groupId != null);
    // 1. owners delete their groups for everyone
    await Promise.all(ready.map(async (g) => {
      const r = await g.owner.proc.cmd(g.owner.userId, `/_delete #${g.groupId} full notify=on`).catch((e) => ({ type: `harness:${e.message}` }));
      if (r?.type === 'groupDeletedUser') g.deleted = true; else this.count(`teardown:ownerDeleteGroup:${errTag(r)}`);
    }));
    const members = this.profiles.filter((p) => p.role === 'member' && p.userId != null && (p.localGroupId ?? p.invitedGroupId) != null);
    await waitUntil(() => members.every((p) => p.groupDeleted), 90000, () => false, 1000);
    T.membersNotified = `${members.filter((p) => p.groupDeleted).length}/${members.length}`;
    // 2. members delete the group on their side (allowed once it is deleted, or after leaving)
    await Promise.all(members.map(async (p) => {
      const gid = p.localGroupId ?? p.invitedGroupId;
      let r = await p.proc.cmd(p.userId, `/_delete #${gid} full notify=off`).catch((e) => ({ type: `harness:${e.message}` }));
      if (r?.type !== 'groupDeletedUser') {
        await p.proc.cmd(p.userId, `/_leave #${gid}`).catch(() => null);
        r = await p.proc.cmd(p.userId, `/_delete #${gid} full notify=off`).catch((e) => ({ type: `harness:${e.message}` }));
        if (r?.type !== 'groupDeletedUser') this.count(`teardown:memberDeleteGroup:${errTag(r)}`);
      }
    }));
    // 3. contacts, both sides
    const owners = this.groups.map((g) => g.owner).filter((p) => p.userId != null);
    await Promise.all(owners.flatMap((o) => [...o.contacts.values()].map(async (cid) => {
      const r = await o.proc.cmd(o.userId, `/_delete @${cid} full notify=on`).catch((e) => ({ type: `harness:${e.message}` }));
      if (r?.type !== 'contactDeleted') this.count(`teardown:ownerDeleteContact:${errTag(r)}`);
    })));
    await Promise.all(this.profiles.filter((p) => p.ownerContactId != null && p.userId != null).map(async (p) => {
      const r = await p.proc.cmd(p.userId, `/_delete @${p.ownerContactId} full notify=off`).catch((e) => ({ type: `harness:${e.message}` }));
      if (r?.type !== 'contactDeleted') this.count(`teardown:memberDeleteContact:${errTag(r)}`);
    }));
    T.gracefulDoneAt = new Date().toISOString();
    this.log('graceful deletes sent; waiting for the relay queue count to settle');
    T.queuesAfterGraceful = await this.waitQueuesSettle(80000, 300000);
    // 4. sweep: delete every test profile with its SMP queues (catches pending connections and anything missed)
    await this.sweep(false, T);
    T.queuesAfterSweep = await this.waitQueuesSettle(80000, 600000);
    T.baselineQueues = this.baselineQueues ?? null;
    this.results.teardown = { ...T, phase: await this.endPhase('teardown') };
    this.log(`teardown: queues peak ${T.queuesPeak}, before ${T.queuesBefore}, after graceful ${T.queuesAfterGraceful}, after sweep ${T.queuesAfterSweep}, baseline ${T.baselineQueues}`);
    return T;
  }

  async sweep(ownedGroups, T = {}) {
    let deleted = 0;
    let failed = 0;
    await Promise.all(this.procs.filter((p) => !p.exited).map(async (proc) => {
      const ul = await proc.cmd(null, '/users').catch(() => null);
      const users = (ul?.users || []).map((x) => x.user).filter((u) => u.userId !== proc.bootUserId);
      for (const u of users) {
        if (ownedGroups) {
          const ch = await proc.cmd(u.userId, `/_get chats ${u.userId}`).catch(() => null);
          for (const c of ch?.chats || []) {
            const gi = c.chatInfo?.type === 'group' ? c.chatInfo.groupInfo : null;
            if (gi && gi.membership?.memberRole === 'owner' && !['deleted', 'left', 'removed'].includes(gi.membership.memberStatus)) {
              await proc.cmd(u.userId, `/_delete #${gi.groupId} full notify=on`).catch(() => null);
            }
          }
        }
        const r = await proc.cmd(proc.bootUserId, `/_delete user ${u.userId} del_smp=on`).catch((e) => ({ type: `harness:${e.message}` }));
        if (r?.type === 'cmdOk') deleted++; else { failed++; this.count(`teardown:deleteUser:${errTag(r)}`); }
      }
    }));
    T.usersDeleted = deleted;
    T.usersDeleteFailed = failed;
    this.log(`sweep: ${deleted} profiles deleted with their relay queues, ${failed} failed`);
  }

  async waitQueuesSettle(stableMs, maxMs) {
    const end = now() + maxMs;
    let last = this.lastServer?.queues ?? null;
    let lastChange = now();
    while (now() < end) {
      await sleep(5000);
      const q = this.lastServer?.queues ?? null;
      if (q !== last) { last = q; lastChange = now(); } else if (now() - lastChange >= stableMs) break;
      if (this.baselineQueues != null && q != null && q <= this.baselineQueues && now() - lastChange >= 35000) break;
    }
    return last;
  }

  async stopAll() {
    this.stopping = true;
    await Promise.all(this.procs.map((p) => p.stop()));
  }

  // ---- main

  async prepare() {
    const o = this.o;
    if (this.runDir === REPO || this.runDir.startsWith(REPO + path.sep)) throw new Error('--run-dir must be outside the repository');
    if (!o.cli || !fs.existsSync(o.cli)) throw new Error('--cli <path to simplex-chat> is required');
    fs.mkdirSync(this.logDir, { recursive: true });
    fs.mkdirSync(this.dbDir, { recursive: true });
    this.logStream = fs.createWriteStream(path.join(this.runDir, 'harness.log'), { flags: 'a' });
    this.errStream = fs.createWriteStream(path.join(this.runDir, 'errors.log'), { flags: 'a' });
    this.landingCsv = fs.createWriteStream(path.join(this.runDir, 'landing.csv'), { flags: 'a' });
    this.harnessCsv = fs.createWriteStream(path.join(this.runDir, 'harness-samples.csv'), { flags: 'a' });
    const props = readProps(o['relay-props']);
    this.smp = props.SECURED_SMP_SERVER;
    this.xftp = props.SECURED_XFTP_SERVER;
    const pub = readProps(o['public-props']);
    const s = parseServer(this.smp);
    const x = parseServer(this.xftp);
    if (!s || s.scheme !== 'smp' || s.fp !== pub.SECURED_SMP_FINGERPRINT || !s.hosts.includes(String(pub.SECURED_SMP_HOST).toLowerCase()) || s.port !== String(pub.SECURED_SMP_PORT)) throw new Error('SMP server in relay props does not match secured.properties');
    if (!x || x.scheme !== 'xftp' || x.fp !== pub.SECURED_XFTP_FINGERPRINT || !x.hosts.includes(String(pub.SECURED_XFTP_HOST).toLowerCase()) || x.port !== String(pub.SECURED_XFTP_PORT)) throw new Error('XFTP server in relay props does not match secured.properties');
    this.results.meta = {
      date: new Date().toISOString(), smp: stripPw(this.smp), xftp: stripPw(this.xftp), cliVersion: await this.cliVersion(),
      procs: o.procs, rts: o.rts, steps: o.steps, membersPerGroup: o.members + 1, steadySec: o.steadySec, gapSec: [o.minGap, o.maxGap],
      burstSec: o.burstSec, thresholds: { maxLoad: o.maxLoad, loadWindow: o.loadWindow, minAvailMb: o.minAvailMb, maxRssMb: o.maxRssMb, maxLandingMs: o.maxLandingMs },
      mac: { cpus: os.cpus().length, memGB: round(os.totalmem() / 2 ** 30, 0) },
    };
    process.on('SIGINT', () => this.onSignal('SIGINT'));
    process.on('SIGTERM', () => this.onSignal('SIGTERM'));
    process.on('exit', () => { for (const pid of this.pids) { try { process.kill(pid, 'SIGKILL'); } catch { /* gone */ } } });
  }

  onSignal(sig) {
    this.signals = (this.signals || 0) + 1;
    if (this.signals > 1) { this.log(`second ${sig}: killing everything without teardown`); process.exit(130); }
    this.abort = sig;
    this.log(`${sig}: stopping load, then teardown (send again to kill without teardown)`);
  }

  cliVersion() {
    return new Promise((res) => execFile(this.o.cli, ['--version'], { env: { ...process.env, DYLD_FALLBACK_LIBRARY_PATH: this.o.dyld }, timeout: 20000 }, (err, out) => res(err ? 'unknown' : String(out).split('\n')[0].trim())));
  }

  startSamplers() {
    this.harnessCsv.write('time,phase,cpuPct,rssMB,maxProcRssMB,nodeRssMB,macLoad1\n');
    this.landingCsv.write('time,phase,ms,status\n');
    this.timers = [setInterval(() => this.sampleHarness(), 10000), setInterval(() => this.probeLanding(), 10000)];
    this.probeLanding();
  }

  async stopSamplers() {
    for (const t of this.timers || []) clearInterval(t);
    clearInterval(this.watchdog);
    this.stopping = true;
    try { this.monitorChild?.kill('SIGTERM'); } catch { /* gone */ }
    await sleep(300);
    for (const s of [this.serverCsv, this.harnessCsv, this.landingCsv, this.errStream]) s?.end();
    await sleep(300);
  }

  async run() {
    const o = this.o;
    await this.prepare();
    this.startMonitor();
    this.startSamplers();
    this.log(`baseline ${o.baselineSec} s`);
    await this.beginPhase('baseline');
    await sleep(o.baselineSec * 1000);
    this.baselineQueues = this.lastServer?.queues ?? null;
    this.results.baseline = await this.endPhase('baseline');
    if (!this.o['no-monitor'] && !this.lastServer) throw new Error('no server samples during the baseline; refusing to load the server');
    if (this.trip) throw new Error(`threshold already exceeded before any load: ${this.trip.reason}`);

    const steps = o.steps;
    this.buildLayout(steps[steps.length - 1]);
    let tornDown = false;
    try {
      this.loadActive = true;
      await this.beginPhase('start');
      await this.startProcs(o.procs);
      this.results.start = await this.endPhase('start', { procs: o.procs, operatorsSeen: this.operatorsSeen, serversVerified: this.serversVerified });
      let prevN = 0;
      let lastGood = 0;
      for (let si = 0; si < steps.length && !this.halt(); si++) {
        const N = steps[si];
        this.stepIdx = si + 1;
        const step = { step: si + 1, groups: N };
        this.results.steps.push(step);
        this.log(`=== step ${si + 1}: ${N} groups (setting up ${prevN + 1}..${N})`);
        step.setup = await this.setupStep(this.groups.slice(prevN, N));
        prevN = N;
        if (this.halt()) break;
        await this.sleepHalt(o.settleSec * 1000);
        const active = this.groups.slice(0, N).filter((g) => g.sim.length > 1);
        step.steady = await this.steadyPhase(active, o.steadySec, `s${si + 1}-steady`);
        if (this.halt()) break;
        step.burst = await this.burstPhase(active, `s${si + 1}-burst`);
        if (this.halt()) break;
        lastGood = N;
        this.log(`step ${si + 1} done: steady p50/p95 ${step.steady.messages.latencyMs.p50}/${step.steady.messages.latencyMs.p95} ms, lost ${step.steady.messages.lost}; burst p95 ${step.burst.messages.latencyMs.p95} ms, lost ${step.burst.messages.lost}`);
      }
      if (this.trip && !this.abort && lastGood > 0) {
        this.log(`dropping back to ${lastGood} groups to confirm recovery`);
        const first = this.trip;
        this.trip = null;
        const active = this.groups.slice(0, lastGood).filter((g) => g.sim.length > 1);
        this.results.confirm = { after: first, groups: lastGood, steady: await this.steadyPhase(active, o.confirmSec, 'confirm-previous') };
      }
    } catch (e) {
      this.log(`run error: ${e.stack || e.message}`);
      this.results.error = String(e.message);
    } finally {
      this.abort = null; // teardown must run even after a signal
      this.trip = null;
      try { await this.seatHold(); } catch (e) { this.log(`seat hold error: ${e.message}`); }
      try { await this.teardown(); tornDown = true; } catch (e) { this.log(`teardown error: ${e.stack || e.message}`); this.results.teardownError = String(e.message); }
      await this.stopAll();
      this.log('all CLI processes stopped; 30 s of post-test samples');
      this.stopping = false;
      await sleep(30000);
      this.results.after = this.serverWindow(now() - 31000, now());
      await this.stopSamplers();
      this.results.counts = this.counts;
      fs.writeFileSync(path.join(this.runDir, 'results.json'), JSON.stringify(this.results, null, 2));
      const t = this.results.teardown;
      const clean = tornDown && t && t.usersDeleteFailed === 0;
      if (clean && !o['keep-db']) { fs.rmSync(this.dbDir, { recursive: true, force: true }); this.log('test databases deleted'); } else this.log(`databases kept in ${this.dbDir} (rerun with --teardown-only if relay queues remain)`);
      this.log('done');
    }
  }

  /** Restarts the CLI processes from an earlier run's databases and deletes everything they made. */
  async teardownOnly() {
    await this.prepare();
    this.startMonitor();
    this.startSamplers();
    await sleep(15000);
    this.baselineQueues = null;
    const dirs = fs.readdirSync(this.dbDir).filter((d) => /^p\d+$/.test(d)).map((d) => +d.slice(1)).sort((a, b) => a - b);
    for (const i of dirs) this.procs.push(new CliProc(this, i));
    await Promise.all(this.procs.map(async (p) => { await p.start(); await this.configureProcess(p); }));
    await sleep(20000);
    await this.beginPhase('teardown-only');
    const T = { queuesBefore: this.lastServer?.queues ?? null };
    await this.sweep(true, T);
    T.queuesAfterSweep = await this.waitQueuesSettle(80000, 600000);
    this.results.teardown = { ...T, phase: await this.endPhase('teardown-only') };
    this.log(`teardown-only: queues before ${T.queuesBefore}, after ${T.queuesAfterSweep}`);
    await this.stopAll();
    await this.stopSamplers();
    fs.writeFileSync(path.join(this.runDir, 'results-teardown-only.json'), JSON.stringify(this.results, null, 2));
    if (T.usersDeleteFailed === 0 && !this.o['keep-db']) fs.rmSync(this.dbDir, { recursive: true, force: true });
  }
}

// ------------------------------------------------------------------------------------------------------- main

function parseArgs(argv) {
  const o = { ...DEFAULTS };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    if (!k.startsWith('--')) throw new Error(`unexpected argument ${k}`);
    const key = k.slice(2);
    const v = argv[i + 1];
    if (v === undefined || v.startsWith('--')) o[key] = true;
    else { o[key] = v; i++; }
  }
  const num = (k) => { const n = Number(o[k]); if (!Number.isFinite(n)) throw new Error(`--${k} must be a number`); return n; };
  return {
    ...o,
    steps: String(o.steps).split(',').map(Number).filter((n) => n > 0),
    members: num('members'), seatGroup: num('seat-group'), procs: num('procs'), basePort: num('base-port'),
    baselineSec: num('baseline-sec'), settleSec: num('settle-sec'), steadySec: num('steady-sec'), minGap: num('min-gap'), maxGap: num('max-gap'),
    burstSec: num('burst-sec'), drainSec: num('drain-sec'), confirmSec: num('confirm-sec'),
    contactTimeout: num('contact-timeout'), joinTimeout: num('join-timeout'), meshTimeout: num('mesh-timeout'),
    landingUrl: o['landing-url'], maxLoad: num('max-load'), loadWindow: num('load-window'), minAvailMb: num('min-avail-mb'),
    maxRssMb: num('max-rss-mb'), maxLandingMs: num('max-landing-ms'), maxHarnessMb: num('max-harness-mb'), seatMinSec: num('seat-min-sec'),
  };
}

const USAGE = `usage: node loadtest.mjs --cli <simplex-chat binary> --run-dir <scratch dir outside the repo> [options]
       node loadtest.mjs --cli <...> --run-dir <same dir> --teardown-only
see README.md for every option`;

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  let o;
  try { o = parseArgs(process.argv.slice(2)); } catch (e) { console.error(`${e.message}\n${USAGE}`); process.exit(2); }
  if (o.help || !o.cli || !o['run-dir']) { console.error(USAGE); process.exit(o.help ? 0 : 2); }
  const t = new LoadTest(o);
  (o['teardown-only'] ? t.teardownOnly() : t.run()).then(() => process.exit(0), (e) => { t.log(`fatal: ${e.stack || e.message}`); process.exit(1); });
}

export { LoadTest, parseServer, customServersUpdate, verifyUserServers, redact };
