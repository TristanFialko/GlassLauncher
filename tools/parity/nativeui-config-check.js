#!/usr/bin/env node
/* The native UI prototype reads assets/nativeui/home.json; the WebView UI reads assets/apps.js. Until one config feeds
   both, this check fails if they describe different cards, packages, artwork, wallpapers or sound settings.
   node tools/parity/nativeui-config-check.js */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ASSETS = path.resolve(__dirname, '../../app/src/main/assets');
const ctx = { window: {} };
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(path.join(ASSETS, 'apps.js'), 'utf8'), ctx, { filename: 'apps.js' });
const w = ctx.window;
const native = JSON.parse(fs.readFileSync(path.join(ASSETS, 'nativeui/home.json'), 'utf8'));

const card = (a) => ({ name: a.name, id: a.id || '', ids: a.ids || [], match: a.match ? a.match.source : '',
  matchFlags: a.match ? a.match.flags : '', icon: a.icon || '', dark: a.dark || '' });
const want = {
  rows: [{ kind: 'top', title: '', apps: w.APPS.map(card) },
    { kind: 'ent', title: w.ENTERTAINMENT.title, apps: w.ENTERTAINMENT.apps.map(card) }],
  overrides: w.ICON_OVERRIDES || {},
  wallpaperLight: w.THEME.wallpaperLight, wallpaperDark: w.THEME.wallpaperDark,
  soundEnabled: w.SOUND.enabled !== false, soundVolume: w.SOUND.volume, keyVolume: w.SOUND.keyVolume,
};
// the native prototype matches names case-insensitively, which is what every /.../i in apps.js means
const got = {
  rows: native.rows.map((r) => ({ kind: r.kind, title: r.title, apps: r.apps.map((a) => ({ name: a.name, id: a.id, ids: a.ids, match: a.match,
    matchFlags: a.match ? 'i' : '', icon: a.icon, dark: a.dark })) })),
  overrides: native.overrides || {},
  wallpaperLight: native.wallpaperLight, wallpaperDark: native.wallpaperDark, soundEnabled: native.soundEnabled, soundVolume: native.soundVolume, keyVolume: native.keyVolume,
};
const bad = [];
const missing = [];
for (const r of want.rows) for (const a of r.apps) for (const f of [a.icon, a.dark]) if (f && !fs.existsSync(path.join(ASSETS, f))) missing.push(f);
if (JSON.stringify(want) !== JSON.stringify(got)) bad.push('home.json differs from apps.js:\n  apps.js   ' + JSON.stringify(want) + '\n  home.json ' + JSON.stringify(got));
if (missing.length) bad.push('artwork files missing: ' + missing.join(', '));
console.log(JSON.stringify({ cards: want.rows.reduce((n, r) => n + r.apps.length, 0), mismatches: bad.length }));
bad.forEach((b) => console.log(b));
if (bad.length) process.exit(1);
