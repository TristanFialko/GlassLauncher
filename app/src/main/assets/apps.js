// Android package IDs for the ARRIS VIP7802.
window.APPS = [
  {name:"YouTube", id:"com.google.android.youtube.tv", icon:"icons/Youtube.png", dark:"darkicons/darkyoutube.png"},
  {name:"Netflix", id:"com.netflix.ninja", icon:"icons/Netflix.png", dark:"darkicons/darknetflix.png"},
  {name:"Prime Video", id:"com.amazon.amazonvideo.livingroom", icon:"icons/Prime.png", dark:"darkicons/darkprime.png"},
  {name:"Disney+", id:"com.disney.disneyplus", icon:"icons/Disney+.png", dark:"darkicons/darkdisney.png"},
  {name:"Spotify", id:"com.spotify.tv.android", icon:"icons/Spotify.png", dark:"darkicons/Spotify.png"}
];
window.THEME = { wallpaperLight:"wallpaper.jpg", wallpaperDark:"wallpaper2.jpg", iconLight:"moonsun/sun-filled.png", iconDark:"moonsun/moon-filled.png" };
window.SOUND = {enabled:true, volume:.2, keyVolume:.3};
window.PERF = {lite:true};

// ---- Home screen layout (all sizes in rem; 1rem = 20px at 1920x1080) ----
window.LAYOUT = {
  columns: 5,             // exactly this many apps per row. Never changes with screen width.
  showLabels: false,      // true brings back the "Pinned" / "All Apps" headings
  rowGap: 2.4,            // space between app rows
  clockGap: 5.4,          // space between the clock and the first app row
  clockScale: 0.6,        // how small the clock gets when you scroll down
  collapsedClockTop: -1.6,// where the shrunken clock sits (negative = partly off the top edge, as before)
  collapsedGap: 1.6,      // space between the shrunken clock and the visible rows
  bottomMargin: 3.2       // space kept free at the bottom of the screen
};

// ---- Control Centre icons: YOUR images, not mine ----
// Put your PNGs in the "ui" folder (or point these paths anywhere inside assets).
// Any entry left empty or missing simply shows no icon, so nothing breaks.
// invert:true  = your icons are BLACK on transparent (like the sun/moon ones) and get flipped to white.
// invert:false = your icons are already white / coloured and are shown as they are.
window.UI_ICONS = {
  invert: true,
  controls:    "ui/controls.png",     // header button that opens the Control Centre
  search:      "ui/search.png",       // header Search button (built-in icon is used if this file is missing)
  wifi:        "ui/wifi.png",
  wifiOff:     "ui/wifi-off.png",
  ethernet:    "ui/ethernet.png",
  bluetooth:   "ui/bluetooth.png",
  bluetoothOff:"ui/bluetooth-off.png",
  volume:      "ui/volume.png",
  volumeMuted: "ui/volume-muted.png",
  themeLight:  "moonsun/sun-filled.png",   // reuses your sun
  themeDark:   "moonsun/moon-filled.png",  // reuses your moon
  device:      "ui/device.png",
  settings:    "ui/settings.png"
};

// ---- Entertainment row (Phase 6) ----
// Second (and last) home-screen row: four cards, same size and spacing as the top row, centred in the same glass panel.
// For each app, Glass looks for the real package on the box in this order, then launches THAT package:
//   1. the first of `ids` that is installed   2. the first installed app whose name matches `match`
// If neither is found the card still shows, and selecting it says the app is not installed (nothing fake is launched).
// The package ids below are the usual ones for each app; they are NOT assumed - Search shows the real id under every installed app.
// icon = artwork for the light theme, dark = artwork for the dark theme (your supplied files, unmodified).
window.ENTERTAINMENT = {
  title: "Entertainment",
  apps: [
    {name:"Stremio",   ids:["com.stremio.one"],                                              match:/stremio/i,   icon:"icons/stremio.png",   dark:"darkicons/stremio.png"},
    {name:"Rhenium",   ids:[],                                                               match:/rhenium/i,   icon:"icons/rhenium.png",   dark:"darkicons/rhenium.png"},
    {name:"Moonlight", ids:["com.limelight","com.limelight.noroot","com.limelight.root"],     match:/moonlight/i, icon:"icons/moonlight.png", dark:"darkicons/moonlight.png"},
    {name:"RetroArch", ids:["com.retroarch","com.retroarch.aarch64","com.retroarch.ra32"],   match:/retroarch/i, icon:"icons/retroarch.png", dark:"darkicons/retroarch.png"}
  ]
};

// ---- Custom app icon overrides (Phase 3) ----
// Changes only how an app LOOKS inside Glass (home rows and Search). The package and launch path are untouched.
// Put the image in assets/icons/overrides/ and list it here by Android package id.
// Square images (like an App Store-style icon) are shown centred on the card; wide artwork fills it.
window.ICON_OVERRIDES = {
  // "com.android.vending": "icons/overrides/playstore.png"   // Google Play Store - uncomment once your icon is in place
};

// ---- Extra search words per app (Phase 3) ----
// "com.android.vending" already answers to: play store, google play, store.
window.SEARCH_ALIASES = {
  // "com.netflix.ninja": ["movies", "series"]
};
