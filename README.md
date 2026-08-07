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

### Several documents

A Trellis instance serves exactly one document, so the desktop runs one instance
per document, each on its own port (`trellis ~/work.ron --port 7373 …`). Add one
entry per document in **Settings → Workstations**: **Add**, fill in host/port/key,
and **Test connection** — the test reports which document that port is serving and
offers its name as the label. Switch between them from the tree screen's overflow
menu (**Switch workstation**); the active one is shown under the title.

Each server keeps its **own** offline cache and its own expanded-node state, so
switching is instant and folds from one document never apply to another.

Only use on trusted networks; the desktop API is key-gated but unencrypted (HTTP).

## Build

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Java + XML Views, Material 3. AGP 8.7.3 / Gradle 8.11.1, `compileSdk 35`,
`minSdk 26`, Java 17.

## App lock

**Settings → Security → Require unlock to open.** Off by default. With it on, the
app asks for your fingerprint or face — with the phone's PIN, pattern or password
as the fallback — before it will show any notes, and it keeps the notes out of the
recents switcher and out of screenshots (`FLAG_SECURE`).

**Lock after** chooses how long a trip out of the app is forgiven: *Immediately*,
*1 minute* (default) or *5 minutes*. Moving between screens inside the app never
re-prompts; only leaving it does. A restart always re-asks — the unlocked state is
held in memory and never written down.

The toggle is disabled if the phone has no screen lock of its own, because there
would be no credential to check and the prompt would always open.

**What this does and doesn't protect.** It guards the screen, not the bytes. The
offline cache is plain JSON in the app's private storage and the API key is a
plain string in its preferences — unreachable on a locked, non-rooted phone (the
app also sets `allowBackup="false"`), but readable from a rooted or ADB-enabled
device whatever the UI does. Encrypting both behind an Android Keystore key is a
separate, planned piece of work.

## Themes

Two-axis appearance (dark/light × accent), matching the house style:
**Ocean** (blue, default), **Terminal** (green, monospace), and **Trellis**
(indigo/slate — the desktop signature). Plus three that mirror the desktop:
**Sticky Notes** (solid single-color paper cards — yellow by default — on a cork
board), **Futuristic** (a Minority-Report blue HUD with beveled tech-panel cards),
and **SynthWave** (the outrun neon palette). Set in Settings → Appearance.

## Status

- Connection settings + node-tree list with pull-to-refresh.
- **Collapse / expand nodes** — the tree starts **collapsed** (just the top
  level), so a big archive is navigable at a glance on a phone. Tap the ▸/▾ arrow
  on a parent to unfold it; **Collapse all** / **Expand all** in the toolbar menu
  handle the whole tree. Your open/closed state is **saved** — it persists across
  live refreshes and app restarts.
- Tap a node → its **basket**, rendered as a pannable/zoomable canvas with cards at
  their real positions and accent colors: text/code bodies, checklists, tables
  (with cell colors), and sketches (vector strokes). Polls every 3 s for updates.
- Image cards render their picture (Trellis ≥ 0.29.0); **tap one to open a
  full-screen viewer** with pinch-to-zoom, pan, double-tap-to-zoom, and
  **swipe between all images** on a multi-image card.
- **Tap a text, code, checklist, or table card to read it in full** — cards are
  clipped to their box on the canvas, so a long one only shows its top; tapping
  opens a scrollable reader with the whole thing (markdown for text/checklists,
  monospaced for code, and aligned columns with horizontal scroll for tables).
- **Search** (toolbar) — full-text across every node title and card; tap a hit to
  open that node's basket.
- **Capture** — the **+** button in a basket adds a **note**, a **camera photo**,
  or a **picked image** as a card in that node. (Read + capture; other edits are
  still done on the desktop.)
- **Agenda and Kanban** (overflow menu) — the desktop task views, read-only. Each
  row names the task's **full basket path** (`Super Weapon News › Open Items`),
  because basket names repeat across projects. **Filter by project** in either
  view's overflow menu narrows it to one; the two views remember their own choice,
  per server, so scoping the board doesn't narrow your agenda. Needs Trellis
  ≥ 0.71.0 for the project fields.
- Live updates via the desktop's `/api/wait` long-poll (Trellis ≥ 0.30.0).
- **Offline cache (read-only)** — every basket and image you open is cached on the
  phone, so when the LAN Trellis goes offline you can still read what you've
  already viewed. An "⚠ Offline — cached copy" note appears under the title while
  serving the cache; the app returns to the live document (and refreshes the
  cache) automatically the moment the host is back. No sync — writes still need
  the desktop.
