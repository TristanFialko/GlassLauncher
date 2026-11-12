#!/usr/bin/env node
// Frozen search-ranking reference: registry.js queryJs() output on the parity fixture box, written to
// tools/parity/fixtures/ranking-golden.json so the Kotlin fallback (SearchRanking) and the Rust core can be checked against
// the original JavaScript ranking even after the WebView assets are deleted (Stage 4c).
//
//   node tools/parity/ranking-golden.js            check: current registry.js still produces the golden file exactly
//   node tools/parity/ranking-golden.js --rust     ... and Rust (glass-cli squery on the golden snapshot) matches it too
//   node tools/parity/ranking-golden.js --write    regenerate the golden file (only when the ranking is meant to change)
//
// The query list is deterministic (own LCG, independent of fixture.js's generator state). Queries containing a lone UTF-16
// surrogate are excluded: they have no UTF-8 form, so neither Rust nor a JSON fixture can carry them (registry.js LONE).
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const vm = require('vm');
const { execFileSync } = require('child_process');
const fixture = require('./fixture');

const ROOT = path.resolve(__dirname, '../..');
const ASSETS = path.join(ROOT, 'app/src/main/assets');
const GOLDEN = path.join(__dirname, 'fixtures/ranking-golden.json');
const MAX = 24;
const UNAVAILABLE = ['storage'];   // same box as search-parity.js: one settings page missing
const LONE = /[\ud800-\udbff](?![\udc00-\udfff])|(^|[^\ud800-\udbff])[\udc00-\udfff]/;

/* registry.js + shortcuts.js with no AndroidBridge: the pure JavaScript path (queryJs). */
function sandbox() {
  const G = {
    apps: {
      installed: fixture.installed.map((x) => Object.assign({}, x)),
      HIDDEN: /^(org\.tizen|com\.samsung|DU\.TV\.PRODA)/i,
      isInstalled(p) { return G.apps.installed.some((x) => x.id === p); },
    },
    cfg: { aliases: JSON.parse(JSON.stringify(fixture.aliases)) },
    bridge: {
      call(fn, a) { return fn === 'hasSettings' ? !UNAVAILABLE.includes(a) : null; },
      available() { return false; },
    },
    launch() {}, sound: { click() {} }, openSettings() {}, search: { close() {} }, cc: { open() {} },
    theme: { toggle() {}, isDark() { return false; } }, toast() {},
  };
  const ctx = { window: { Glass: G, PERF: {} }, String, Math, Array, Object, RegExp, JSON };
  vm.createContext(ctx);
  for (const f of ['js/registry.js', 'js/shortcuts.js']) vm.runInContext(fs.readFileSync(path.join(ASSETS, f), 'utf8'), ctx, { filename: f });
  return G;
}

function queries(entries) {
  const set = new Set(['', ' ', '   ', 'a', 'A', 'wi-fi', 'WI FI', 'wi fi', 'play store', 'settings', 'set', 'ntfx', 'ntf', 'yt',
    'σίσυφος', 'ΣΊΣΥΦΟΣ', 'istanbul', 'i̇stanbul', 'café', 'cafe', 'テレビ', '🎮', '𝔉𝔯', 'éclair', 'e\u0301', 'ｆｕｌｌ',
    'media center', 'mediacenter', '4k', '2024', '!!!', 'sep', 'separator', 'xyzzy', 'tab name', 'ideo space', 'storage',
    'volume', 'dark mode', 'wrls', 'movies', 'films', 'store', 'google', 'tv', 'home', 'glass', 'about', 'Date & time']);
  for (const e of entries) {
    for (const s of [e.title || ''].concat(e.keywords || [])) {
      for (let n = 1; n <= Math.min(8, s.length); n++) { const p = s.slice(0, n); set.add(p); set.add(p.toUpperCase()); }
      set.add(s); set.add(s.replace(/ /g, ''));
    }
  }
  let seed = 20261113;
  const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; };
  const chars = Array.from('abcdefghijklmnopqrstuvwxyz0123456789    -+&!.ÉéüΣσςİı日本テレビ🎮\u00a0\u3000\t');
  for (let i = 0; i < 400; i++) {
    const n = 1 + Math.floor(rnd() * 6);
    set.add(Array.from({ length: n }, () => chars[Math.floor(rnd() * chars.length)]).join(''));
  }
  return [...set].filter((q) => !LONE.test(q));
}

function build() {
  const G = sandbox();
  const R = G.registry;
  const entries = R.entries();
  const index = new Map(entries.map((e, i) => [e.id, i]));
  if (index.size !== entries.length) throw new Error('entry ids are not unique');
  const cases = queries(entries).map((q) => {
    const got = R.queryJs(q, MAX).map((e) => index.get(e.id));
    const five = R.queryJs(q, 5).map((e) => index.get(e.id)).join(',');
    if (five !== got.slice(0, 5).join(',')) throw new Error('max=5 is not a prefix of max=24 for ' + JSON.stringify(q));
    return [q, got.join(',')];
  });
  return {
    generated_from: 'app/src/main/assets/js/registry.js queryJs() + shortcuts.js on tools/parity/fixture.js, storage page missing',
    regenerate: 'node tools/parity/ranking-golden.js --write',
    max: MAX,
    snapshot: R.snapshotWire(),
    entries: entries.map((e) => ({ id: e.id, kind: e.kind, title: e.title, keywords: e.keywords || [] })),
    cases,
  };
}

function text(g) {
  // one case per line keeps diffs reviewable
  const head = JSON.stringify(Object.assign({}, g, { entries: undefined, cases: undefined }), null, 1).replace(/\n}$/, '');
  return head + ',\n "entries": [\n' + g.entries.map((e) => '  ' + JSON.stringify(e)).join(',\n') + '\n ],\n "cases": [\n' +
    g.cases.map((c) => '  ' + JSON.stringify(c)).join(',\n') + '\n ]\n}\n';
}

function checkRust(g) {
  const exe = process.platform === 'win32' ? 'glass-cli.exe' : 'glass-cli';
  const CLI = process.env.GLASS_CLI || path.join(ROOT, 'rust/target/release', exe);
  if (!fs.existsSync(CLI)) { console.error('missing ' + CLI + ' (cargo build --release -p glass-cli)'); process.exit(2); }
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'glass-golden-'));
  const wire = path.join(tmp, 'golden.snapshot');
  fs.writeFileSync(wire, g.snapshot, 'utf8');
  const refs = execFileSync(CLI, ['prepare', wire]).toString().replace(/\n$/, '');
  if (refs.startsWith('ERR')) { console.log('FAIL: Rust rejected the golden snapshot: ' + refs); process.exit(1); }
  const kept = refs ? refs.split(',').length : 0;
  const hex = (s) => Buffer.from(s, 'utf8').toString('hex');
  const out = execFileSync(CLI, ['squery', wire, String(g.max)], { input: g.cases.map((c) => hex(c[0])).join('\n') + '\n', maxBuffer: 1 << 28 })
    .toString().split('\n');
  const bad = g.cases.filter((c, i) => out[i] !== c[1]);
  console.log(JSON.stringify({ rustEntries: kept, goldenEntries: g.entries.length, rustChecked: g.cases.length, rustMismatches: bad.length }));
  bad.slice(0, 10).forEach((c) => console.log('RUST', JSON.stringify(c), JSON.stringify(out[g.cases.indexOf(c)])));
  if (kept !== g.entries.length || bad.length) process.exit(1);
}

function main() {
  const fresh = text(build());
  if (process.argv.includes('--write')) {
    fs.writeFileSync(GOLDEN, fresh, 'utf8');
    console.log('wrote ' + path.relative(ROOT, GOLDEN));
    return;
  }
  if (!fs.existsSync(GOLDEN)) { console.log('FAIL: missing ' + path.relative(ROOT, GOLDEN) + ' (run with --write)'); process.exit(1); }
  const saved = fs.readFileSync(GOLDEN, 'utf8').replace(/\r\n/g, '\n');
  const g = JSON.parse(saved);
  const nonEmpty = g.cases.filter((c) => c[1]).length;
  console.log(JSON.stringify({ entries: g.entries.length, cases: g.cases.length, casesWithResults: nonEmpty, jsMatchesGolden: saved === fresh }));
  if (saved !== fresh) {
    const a = saved.split('\n'), b = fresh.split('\n');
    const i = a.findIndex((l, k) => l !== b[k]);
    console.log('FAIL: registry.js no longer produces the golden ranking; first difference at line ' + (i + 1));
    console.log('golden : ' + a[i]);
    console.log('current: ' + b[i]);
    process.exit(1);
  }
  if (process.argv.includes('--rust')) checkRust(g);
}

main();
