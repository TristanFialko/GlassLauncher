#!/usr/bin/env node
/* Registry snapshot parity: Rust snapshot preparation (glass-core/src/snapshot.rs) vs the production JavaScript
   G.registry.entries() it replaces as the normal path.

   node tools/parity/snapshot-parity.js --generate   writes fixtures/registry-entries.json from the CURRENT registry.js
                                                     (run before changing behaviour; the committed file is the contract)
   node tools/parity/snapshot-parity.js              checks (1) entries() still equals the fixtures and (2) the Rust
                                                     snapshot path - registry.js snapshot wire -> glass-cli prepare ->
                                                     registry.js ref mapping - yields the same entries, in order.
   Needs rust/target/release/glass-cli (cargo build --release -p glass-cli). GLASS_CLI overrides the path. */
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const vm = require('vm');
const { execFileSync } = require('child_process');
const fixture = require('./fixture');

const ROOT = path.resolve(__dirname, '../..');
const ASSETS = path.join(ROOT, 'app/src/main/assets');
const OUT = path.join(__dirname, 'fixtures/registry-entries.json');
const exe = process.platform === 'win32' ? 'glass-cli.exe' : 'glass-cli';
const CLI = process.env.GLASS_CLI || path.join(ROOT, 'rust/target/release', exe);

const clone = (x) => JSON.parse(JSON.stringify(x));
const base = fixture.installed;
const without = (ids) => base.filter((x) => !ids.includes(x.id));

/* Each case: installed list, aliases, settings pages missing on the "box", extra providers (plain data; run() added). */
const CASES = [
  { name: 'fixture box, storage page missing', installed: base, aliases: fixture.aliases, unavailable: ['storage'] },
  { name: 'no settings app installed (main Settings shortcut kept)', installed: without(['com.android.tv.settings', 'com.android.settings']),
    aliases: fixture.aliases, unavailable: [] },
  { name: 'only phone settings installed', installed: without(['com.android.tv.settings']), aliases: {}, unavailable: ['wifi', 'bluetooth'] },
  { name: 'every optional settings page missing', installed: base.slice(0, 20), aliases: {},
    unavailable: ['wifi', 'bluetooth', 'display', 'sound', 'apps', 'storage', 'datetime', 'keyboard', 'accessibility', 'device'] },
  { name: 'empty app list', installed: [], aliases: {}, unavailable: [] },
  { name: 'visibility rules: show:false, hidden prefixes in any case, self package', installed: [
      { id: 'com.example.visible', name: 'Visible', show: true },
      { id: 'com.example.hiddenflag', name: 'Hidden Flag', show: false },
      { id: 'com.example.noshow', name: 'No Show Field' },
      { id: 'COM.SAMSUNG.tv', name: 'Upper Samsung', show: true },
      { id: 'org.Tizen.app', name: 'Mixed Tizen', show: true },
      { id: 'du.tv.proda2', name: 'Lower DU', show: true },
      { id: 'DU.TV.PRODAX', name: 'DU prefix', show: true },
      { id: 'xcom.samsung', name: 'Not a prefix', show: true },
      { id: 'com.samsungx.app', name: 'Samsungx prefix still hidden', show: true },
      { id: 'org.tizenfoo', name: 'Tizenfoo', show: true },
      { id: 'com.glasslauncher', name: 'Glass itself', show: true },
      { id: 'com.glasslauncher.utilityhub', name: 'Glass Utility Hub', show: true },
      { id: 'com.android.settings', name: 'Settings (hidden flag still supersedes)', show: false },
      { id: 'com.example.dup', name: 'Dup', show: true },
      { id: 'com.example.dup', name: 'Dup', show: true },
    ], aliases: { 'com.example.visible': ['seen', '', 'Sep\u001eara\u001ftor\u001d\u001c'], 'com.example.hiddenflag': ['never'],
      'com.example.dup': ['twice'] }, unavailable: [] },
  { name: 'odd titles and aliases', installed: [
      { id: 'com.example.a', name: '', show: true }, { id: 'com.example.b', name: '   ', show: true },
      { id: 'com.example.c', name: 'Ctl\u001c\u001d\u001e\u001fChars', show: true }, { id: 'com.example.d', name: '𝔉𝔯 Astral', show: true },
      { id: 'com.example.e', name: 'Tab\tName', show: true } ],
    aliases: { 'com.example.a': ['alias only'], 'com.example.c': [] }, unavailable: [] },
  { name: 'extra provider with available/supersededBy', installed: base.slice(0, 40), aliases: fixture.aliases, unavailable: ['sound'],
    extra: [
      { id: 'x:1', kind: 'app', title: 'Extra App', keywords: ['bonus'] },
      { id: 'x:2', kind: 'shortcut', title: 'Extra Gone', keywords: [], availableValue: false },
      { id: 'x:3', kind: 'shortcut', title: 'Superseded By Netflix', keywords: ['nf'], supersededBy: ['com.example.none', 'com.netflix.ninja'] },
      { id: 'x:4', kind: 'shortcut', title: 'Not Superseded', keywords: ['ok'], supersededBy: ['com.example.none'] },
      { id: 'x:5', kind: 'shortcut', title: 'Available', keywords: [], availableValue: true },
    ] },
];

/* registry.js + shortcuts.js in a sandbox. bridge = null -> pure JavaScript (no Rust). */
function sandbox(c, bridge) {
  const G = {
    apps: {
      installed: clone(c.installed),
      HIDDEN: /^(org\.tizen|com\.samsung|DU\.TV\.PRODA)/i,
      isInstalled(p) { return G.apps.installed.some((x) => x.id === p); },
    },
    cfg: { aliases: clone(c.aliases) },
    bridge: {
      call(fn, a) { if (fn === 'hasSettings') return !c.unavailable.includes(a); return bridge && bridge[fn] ? bridge[fn](a) : null; },
      available(fn) { return !!(bridge && bridge[fn]); },
    },
    launch() {}, sound: { click() {} }, openSettings() {}, search: { close() {} }, cc: { open() {} },
    theme: { toggle() {}, isDark() { return false; } }, toast() {},
  };
  const ctx = { window: { Glass: G, PERF: {} }, String, Math, Array, Object, RegExp, JSON };
  if (bridge) ctx.AndroidBridge = bridge;
  vm.createContext(ctx);
  for (const f of ['js/registry.js', 'js/shortcuts.js']) vm.runInContext(fs.readFileSync(path.join(ASSETS, f), 'utf8'), ctx, { filename: f });
  if (c.extra) {
    const list = c.extra.map((e) => {
      const o = Object.assign({}, e, { run() {} });
      delete o.availableValue;
      if ('availableValue' in e) o.available = () => e.availableValue;
      return o;
    });
    G.registry.register({ entries: () => list });
  }
  return G;
}

const view = (e) => ({ kind: e.kind, id: e.id, title: e.title, subtitle: e.subtitle === undefined ? null : e.subtitle,
  pkg: e.pkg === undefined ? null : e.pkg, tag: e.tag === undefined ? null : e.tag, keywords: e.keywords || [] });

function generate() {
  const out = { generated_from: 'app/src/main/assets/js/registry.js G.registry.entries() (pre-snapshot-migration production path)',
    cases: CASES.map((c) => ({ name: c.name, entries: sandbox(c, null).registry.entries().map(view) })) };
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(out, null, 1) + '\n');
  console.log('wrote ' + path.relative(ROOT, OUT) + ': ' + out.cases.map((c) => c.entries.length).join(', ') + ' entries');
}

function check() {
  if (!fs.existsSync(CLI)) { console.error('missing ' + CLI + ' (cargo build --release -p glass-cli)'); process.exit(2); }
  const fx = JSON.parse(fs.readFileSync(OUT, 'utf8'));
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'glass-snapshot-'));
  let bad = 0, snapshotCalls = 0, checked = 0;
  CASES.forEach((c, i) => {
    const want = JSON.stringify(fx.cases[i].entries);
    const js = JSON.stringify(sandbox(c, null).registry.entries().map(view));
    if (js !== want) { bad++; console.log('ENTRIES (JS) differ from fixture: ' + c.name); }
    const file = path.join(tmp, i + '.snapshot');
    const bridge = {
      coreSearchSnapshot(w) { snapshotCalls++; fs.writeFileSync(file, w, 'utf8'); const r = execFileSync(CLI, ['prepare', file]).toString().replace(/\n$/, ''); return r === 'ERR' ? null : r; },
      coreSearchIndex() { return -1; },
      coreSearch() { return ''; },
    };
    const G = sandbox(c, bridge);
    const got = G.registry.snapshotEntries();
    const rust = got === null ? 'null (fell back)' : JSON.stringify(got.map(view));
    checked++;
    if (rust !== want) { bad++; console.log('ENTRIES (Rust snapshot) differ: ' + c.name + '\n  want ' + want.slice(0, 400) + '\n  got  ' + rust.slice(0, 400)); }
  });
  console.log(JSON.stringify({ cases: checked, snapshotCalls, mismatches: bad }));
  if (snapshotCalls < CASES.length) { console.log('FAIL: Rust snapshot path not used for every case'); process.exit(1); }
  if (bad) process.exit(1);
}

if (process.argv.includes('--generate')) generate(); else check();
module.exports = { CASES, sandbox, view };
