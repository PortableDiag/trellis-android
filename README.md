# Trellis (Android)

A near-real-time **viewer** for your desktop [Trellis](https://github.com/PortableDiag/trellis)
notes over the LAN. It reads the desktop app's key-gated agent API and shows the
node tree and baskets on your phone, so you can see updates (including agent/AI
edits) while away from the workstation.

**View-only** to start. Nothing is stored server-side; the phone just reads the
document from the desktop over HTTP on your local network.

## Setup

1. On the **desktop**: Tools → Settings → enable **LAN access**, note the API key
   and the LAN URL shown (e.g. `http://192.168.0.101:7373`). Restart Trellis.
2. In the **app**: open Settings (gear), enter the host/IP, port (default 7373),
   and API key, then **Test connection**.

Only use on trusted networks; the desktop API is key-gated but unencrypted (HTTP).

## Build

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Java + XML Views, Material 3. AGP 8.7.3 / Gradle 8.11.1, `compileSdk 35`,
`minSdk 26`, Java 17.

## Themes

Two-axis appearance (dark/light × accent), matching the house style:
**Ocean** (blue, default), **Terminal** (green, monospace), and **Trellis**
(indigo/slate — the desktop signature). Set in Settings → Appearance.

## Status

- Connection settings + node-tree list with pull-to-refresh.
- Tap a node → its **basket**, rendered as a pannable/zoomable canvas with cards at
  their real positions and accent colors: text/code bodies, checklists, tables
  (with cell colors), and sketches (vector strokes). Polls every 3 s for updates.
- Image cards render their picture, fetched from the desktop's image-bytes endpoint
  (Trellis ≥ 0.29.0). **Next:** live push (SSE) instead of 3 s polling.
