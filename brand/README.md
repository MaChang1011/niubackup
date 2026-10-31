# 牛备 NiuBackup — brand assets

The app icon is the approved brand mark: a white geometric bull-head
silhouette with the orange up-arrow （额头上的牛橙上箭头 — backup = send
it up to your own PC) on a deep-teal rounded square.

| File | Purpose |
| --- | --- |
| `app-icon.svg` | Master artwork, 1024×1024 (rounded-square icon) |
| `app-icon-1024.png` | Rendered master PNG |
| `app-icon-foreground.svg` | Adaptive-icon foreground layer (transparent, artwork ≈58% centered for the safe zone) |
| `app-icon-background.svg` | Adaptive-icon background layer (full-square teal gradient, no corners) |

Colors: deep teal `#0A4757 → #0E6B7E` (radial gradient), white `#FFFFFF`,
accent orange `#F07F13`.

Derived assets (rendered from these sources with cairosvg; do not edit
by hand — regenerate instead):

- Android: `android/app/src/main/res/mipmap-*/ic_launcher{,_round,_background,_foreground}.png`
  plus `mipmap-anydpi-v26/ic_launcher{,_round}.xml` (adaptive icon).
- Desktop (Tauri): `desktop/src-tauri/icons/` — `32x32.png`, `64x64.png`,
  `128x128.png`, `128x128@2x.png`, `256x256.png`, `512x512.png`,
  `icon.png` (512), `icon.ico` (multi-size), `icon.icns`.
