#!/usr/bin/env node
// Search parity + host benchmark for the Rust core.
//
// Loads the production assets/js/registry.js and shortcuts.js in a sandbox, then:
//   1. norm():   compares JavaScript norm() with Rust norm() on every fixture string + random unicode strings.
//   2. ranking:  compares the original JavaScript query() (no AndroidBridge) with the Glass 2.0 path
//                (registry.js adapter -> wire format -> Rust via glass-cli) for thousands of queries.
//   3. --bench:  times the original JavaScript query() under Node/V8 and the Rust query on the same data.
//
// Usage: cargo build --release -p glass-cli --manifest-path rust/Cargo.toml && node tools/parity/search-parity.js [--bench]
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const vm = require('vm');
const { execFileSync } = require('child_process');
const fixture = require('./fixture');

const ROOT = path.resolve(__dirname, '../..');
const ASSETS = path.join(ROOT, 'app/src/main/assets');
const exe = process.platform === 'win32' ? 'glass-cli.exe' : 'glass-cli';
const CLI = process.env.GLASS_CLI || path.join(ROOT, 'rust/target/release', exe);
const hex = (s) => Buffer.from(s, 'utf8').toString('hex');
const unhex = (h) => Buffer.from(h, 'hex').toString('utf8');
const cli = (args, lines) => execFileSync(CLI, args, { input: lines.join('\n') + '\n', maxBuffer: 1 << 28 }).toString().split('\n').slice(0, lines.length);

/* A registry sandbox. bridge = null -> pure JavaScript; otherwise an AndroidBridge stub. */
function sandbox(bridge, unavailable) {
  const G = {
    apps: {
      installed: fixture.installed.map((x) => Object.assign({}, x)),
      HIDDEN: /^(org\.tizen|com\.samsung|DU\.TV\.PRODA)/i,
      isInstalled(p) { return G.apps.installed.some((x) => x.id === p); },
    },
    cfg: { aliases: JSON.parse(JSON.stringify(fixture.aliases)) },
    bridge: {
      call(fn, a) { if (!bridge || !bridge[fn]) return fn === 'hasSettings' ? !unavailable.includes(a) : null; return bridge[fn](a); },
      available(fn) { return !!(bridge && bridge[fn]); },
    },
    launch() {}, sound: { click() {} }, openSettings() {}, search: { close() {} }, cc: { open() {} },
    theme: { toggle() {}, isDark() { return false; } }, toast() {},
  };
  const ctx = { window: { Glass: G, PERF: {} }, String, Math, Array, Object, RegExp, JSON };
  if (bridge) ctx.AndroidBridge = bridge;
  vm.createContext(ctx);
  for (const f of ['js/registry.js', 'js/shortcuts.js']) vm.runInContext(fs.readFileSync(path.join(ASSETS, f), 'utf8'), ctx, { filename: f });
  return G;
}

function queries(G) {
  const set = new Set(['', ' ', '   ', 'a', 'A', 'wi-fi', 'WI FI', 'wi fi', 'play store', 'settings', 'set', 'ntfx', 'yt',
    'σίσυφος', 'ΣΊΣΥΦΟΣ', 'istanbul', 'i̇stanbul', 'café', 'cafe', 'テレビ', '🎮', '𝔉𝔯', 'éclair', 'e\u0301', 'ｆｕｌｌ',
    'media center', 'mediacenter', '4k', '2024', '!!!', 'sep', 'separator', 'xyzzy', 'tab name', 'ideo space']);
  const strings = [];
  G.registry.entries().forEach((e) => { strings.push(e.title || ''); (e.keywords || []).forEach((k) => strings.push(k)); });
  for (const s of strings) {
    for (let n = 1; n <= Math.min(12, s.length); n++) { const p = s.slice(0, n); set.add(p); set.add(p.toUpperCase()); }
    set.add(s); set.add(s.replace(/ /g, ''));
  }
  const pool = 'abcdefghijklmnopqrstuvwxyz0123456789    -+&!.ÉéüΣσςİı日本テレビ🎮\u00a0\u3000\t';
  const chars = Array.from(pool);
  for (let i = 0; i < 1500; i++) {
    const n = 1 + Math.floor(fixture.rnd() * 7);
    set.add(Array.from({ length: n }, () => chars[Math.floor(fixture.rnd() * chars.length)]).join(''));
  }
  return [...set];
}

function main() {
  if (!fs.existsSync(CLI)) { console.error('missing ' + CLI + ' (cargo build --release -p glass-cli)'); process.exit(2); }
  const unavailable = ['storage'];               // one settings page missing on this "box", to exercise available()
  const ref = sandbox(null, unavailable);
  const qs = queries(ref);

  // 1. norm parity
  const LONE = /[\ud800-\udbff](?![\udc00-\udfff])|(^|[^\ud800-\udbff])[\udc00-\udfff]/;
  const lone = qs.filter((q) => LONE.test(q)).length;   // no UTF-8 form: the adapter keeps these on the JS path
  const normInputs = [...new Set(qs.concat(ref.registry.entries().map((e) => e.title)))].filter((s) => !LONE.test(s));
  const rustNorm = cli(['norm'], normInputs.map(hex)).map(unhex);
  const normBad = normInputs.filter((s, i) => ref.registry.norm(s) !== rustNorm[i]);

  // 2. ranking parity through the real adapter
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'glass-parity-'));
  const wireFile = path.join(tmp, 'index.wire');
  let answers = null, indexCalls = 0, searchCalls = 0;
  const bridge = {
    coreSearchIndex(w) {
      indexCalls++; fs.writeFileSync(wireFile, w, 'utf8');
      answers = {};
      for (const max of [24, 5]) {
        const out = cli(['query', wireFile, String(max)], qs.map(hex));
        qs.forEach((q, i) => { answers[max + '|' + q] = out[i]; });
      }
      return w ? w.split('\u001e').length : 0;
    },
    coreSearch(q, max) { searchCalls++; const r = answers[max + '|' + q]; return r === undefined ? null : r; },
    hasSettings(k) { return !unavailable.includes(k); },
  };
  const fast = sandbox(bridge, unavailable);
  fast.registry.invalidate();
  let rankBad = [];
  for (const max of [24, 5]) {
    for (const q of qs) {
      const a = ref.registry.query(q, max).map((e) => e.id).join(',');
      const b = fast.registry.query(q, max).map((e) => e.id).join(',');
      if (a !== b) rankBad.push({ q, max, js: a, rust: b });
    }
  }
  const entries = ref.registry.entries().length;
  console.log(JSON.stringify({ entries, queries: qs.length, normChecked: normInputs.length, normMismatches: normBad.length,
    loneSurrogateQueriesRoutedToJs: lone, rankingChecked: qs.length * 2, rankingMismatches: rankBad.length, indexCalls, rustSearchCalls: searchCalls }));
  normBad.slice(0, 10).forEach((s) => console.log('NORM', JSON.stringify(s), JSON.stringify(ref.registry.norm(s))));
  rankBad.slice(0, 10).forEach((m) => console.log('RANK', JSON.stringify(m)));
  if (searchCalls === 0) { console.log('FAIL: adapter never used the Rust path'); process.exit(1); }
  if (normBad.length || rankBad.length) process.exit(1);

  if (process.argv.includes('--bench')) bench(ref, qs.filter((q) => ref.registry.norm(q)), wireFile, fast);
}

function bench(ref, qs, wireFile, fast) {
  const rounds = 30;
  for (let i = 0; i < 3; i++) qs.forEach((q) => ref.registry.query(q, 24));   // JIT warm-up
  const samples = [];
  for (let r = 0; r < rounds; r++) {
    const t = process.hrtime.bigint();
    qs.forEach((q) => ref.registry.query(q, 24));
    samples.push(Number(process.hrtime.bigint() - t) / 1e3 / qs.length);
  }
  samples.sort((a, b) => a - b);
  const p = (f) => samples[Math.round((samples.length - 1) * f)];
  const t0 = process.hrtime.bigint();
  for (let i = 0; i < 200; i++) ref.registry.wire(ref.registry.entries());
  const wireUs = Number(process.hrtime.bigint() - t0) / 1e3 / 200;
  const qFile = wireFile + '.queries';
  fs.writeFileSync(qFile, qs.map(hex).join('\n'));
  const rust = JSON.parse(execFileSync(CLI, ['bench', wireFile, qFile, String(rounds)]).toString());
  console.log(JSON.stringify({ impl: 'js-original (node ' + process.version + ')', entries: ref.registry.entries().length, queries: qs.length, rounds,
    per_query_us_median: +p(0.5).toFixed(3), per_query_us_p95: +p(0.95).toFixed(3), wire_serialise_us: +wireUs.toFixed(2) }));
  console.log(JSON.stringify(rust));
}

main();
