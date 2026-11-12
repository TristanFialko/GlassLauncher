// Fixture: a plausible Android TV box app list plus edge cases (unicode, duplicates, punctuation,
// empty names, hidden vendor packages). Deterministic so parity/bench runs are comparable.
'use strict';

const real = [
  ['com.google.android.youtube.tv', 'YouTube'], ['com.netflix.ninja', 'Netflix'],
  ['com.amazon.amazonvideo.livingroom', 'Prime Video'], ['com.disney.disneyplus', 'Disney+'],
  ['com.spotify.tv.android', 'Spotify'], ['com.stremio.one', 'Stremio'], ['com.limelight', 'Moonlight'],
  ['com.retroarch', 'RetroArch'], ['com.android.vending', 'Google Play Store'],
  ['com.android.tv.settings', 'Settings'], ['com.android.settings', 'Settings'],
  ['com.google.android.tvlauncher', 'Android TV Home'], ['com.google.android.apps.tv.launcherx', 'Google TV Home'],
  ['com.google.android.music', 'YouTube Music'], ['com.google.android.videos', 'Google TV'],
  ['com.plexapp.android', 'Plex: Stream Movies & TV'], ['org.xbmc.kodi', 'Kodi'], ['tv.twitch.android.app', 'Twitch'],
  ['com.hbo.hbonow', 'Max'], ['com.apple.atve.androidtv.appletv', 'Apple TV'], ['bbc.iplayer.android', 'BBC iPlayer'],
  ['air.ITVMobilePlayer', 'ITVX'], ['com.channel4.ondemand', 'Channel 4'], ['uk.co.uktv.dave', 'U'],
  ['com.mxtech.videoplayer.ad', 'MX Player'], ['org.videolan.vlc', 'VLC'], ['com.android.chrome', 'Chrome'],
  ['com.opera.browser', 'Opera'], ['com.android.tv.dvr', 'Live Channels'], ['com.android.gallery3d', 'Gallery'],
  ['com.google.android.katniss', 'Google'], ['com.android.documentsui', 'Files'], ['com.estrongs.android.pop', 'ES File Explorer'],
  ['com.teamviewer.quicksupport.market', 'TeamViewer QuickSupport'], ['com.arris.vip.setup', 'ARRIS Setup'],
  ['DU.TV.PRODA', 'DU TV'], ['DU.TV.PRODA.epg', 'DU TV Guide'], ['com.spocky.projengmenu', 'Projectivy Launcher'],
  ['com.glasslauncher', 'Glass Launcher'], ['com.glasslauncher.utilityhub', 'Glass Utility Hub'],
  ['com.samsung.android.tvplus', 'Samsung TV Plus'], ['org.tizen.legacy', 'Tizen Legacy'],
  ['com.example.unicode1', 'Ünïcödé Player'], ['com.example.unicode2', '日本のテレビ'], ['com.example.unicode3', '🎮 Game Hub'],
  ['com.example.unicode4', 'ΣΊΣΥΦΟΣ TV'], ['com.example.unicode5', 'İstanbul TV'], ['com.example.unicode6', 'Ｆｕｌｌｗｉｄｔｈ Ｍｅｄｉａ'],
  ['com.example.unicode7', 'Café\u00a0Radio'], ['com.example.unicode8', 'Tab\tName'], ['com.example.unicode9', 'Ideo\u3000Space'],
  ['com.example.dup1', 'Media Center'], ['com.example.dup2', 'Media Center'], ['com.example.dup3', 'media center'],
  ['com.example.punct', '!!!'], ['com.example.empty', ''], ['com.example.digits', '4K Demo 2024'],
  ['com.example.long', 'The Extremely Long Application Name That Goes On And On For Testing Purposes'],
  ['com.example.astral', '𝔉𝔯𝔞𝔨𝔱𝔲𝔯 Stream'], ['com.example.combining', 'e\u0301clair TV'], ['com.example.sep', 'Sep\u001eara\u001ftor'],
];

const words = ['Smart', 'Home', 'Remote', 'Cast', 'Media', 'Player', 'Service', 'System', 'Update', 'Weather',
  'News', 'Sports', 'Music', 'Radio', 'Photo', 'Video', 'Live', 'Game', 'Fitness', 'Kids'];
const filler = [];
let seed = 1234567;
function rnd() { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; }
for (let i = 0; i < 160; i++) {
  const n = 1 + Math.floor(rnd() * 3);
  const name = Array.from({ length: n }, () => words[Math.floor(rnd() * words.length)]).join(' ') + (rnd() < 0.3 ? ' ' + i : '');
  filler.push(['com.vendor' + (i % 7) + '.app' + i, name]);
}

module.exports = {
  installed: real.concat(filler).map(([id, name]) => ({ id, name, show: true })),
  aliases: { 'com.netflix.ninja': ['films', 'movies'], 'com.google.android.youtube.tv': ['yt', 'videos'] },
  rnd,
};
