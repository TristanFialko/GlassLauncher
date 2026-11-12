Glass Control Centre icons
==========================
Drop your own PNG icons into this folder using these file names
(or change the paths in apps.js > window.UI_ICONS).

  controls.png        header button that opens the Control Centre
  search.png          header Search button
  wifi.png            Wi-Fi connected
  wifi-off.png        Wi-Fi off / not connected
  ethernet.png        wired connection
  bluetooth.png       Bluetooth on
  bluetooth-off.png   Bluetooth off
  volume.png          volume
  volume-muted.png    volume muted
  device.png          "This box" tile
  settings.png        Android Settings tile

Recommended: square PNG with transparent background, 128x128 or larger.
Black-on-transparent works with invert:true (default) in apps.js.
Missing files are fine: that slot just shows no icon.
Sun / moon icons for the theme tile are reused from the moonsun folder.

Phase 3: every icon above now has a built-in SVG fallback, so nothing is blank if a file is missing.
Your PNG is used instead whenever the file exists.
