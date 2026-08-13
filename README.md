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

## Encrypted at rest

**Since v0.22.0, the two things worth protecting on the phone are encrypted on
disk**: the **API key** — which is live access to your whole document over the
LAN — and the **offline cache**, which is every basket and card body you have
opened. Both used to sit in app-private storage as plaintext: safe on a locked,
non-rooted phone, and readable from a rooted or ADB-enabled one no matter what
the UI did.

Both are now AES-256/GCM under a key held in the **Android Keystore**, where the
key material cannot be extracted from the device at all. There is nothing to
turn on and nothing to configure. Your existing setup migrates itself on first
run: the API key is re-written encrypted and the plaintext removed, and the cache
is dropped and re-fetched (it is a cache — that costs one round-trip).

**With the app lock on, the key is bound to unlocking the phone.** It is usable
only in a window after an unlock, so notes pulled off a locked device are
ciphertext, not just a screen you cannot see. The binding accepts your PIN,
pattern or password as well as a fingerprint — deliberately, because a key tied
to a *specific* fingerprint is destroyed the moment you enrol another one, which
would mean losing your cache for adding a finger.

With the app lock off, the data is still encrypted, just not tied to an unlock.

**One thing cannot be encrypted, and is deleted instead.** Taking a photo needs a
real file on disk, because the camera is a separate app writing into it. That
scratch file is removed as soon as the image has been read — whether the upload
succeeded, failed, or you cancelled the shot — and any left by an older build or
an interrupted capture are swept away at startup.

**If you remove your phone's screen lock entirely, the key is destroyed** — by
Android, by design, and there is no recovery. The app notices, clears what it can
no longer read, and asks for the API key again.

**This does not encrypt the network.** The desktop API is plain HTTP, so with LAN
access enabled the key still crosses your network in the clear on every request.
That is a separate problem; only enable LAN access on a network you trust.

## Releasing

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Signing needs `keystore.properties` beside `build.gradle` (gitignored, along with
`*.jks`):

```
storeFile=trellis-release.jks
storePassword=…
keyAlias=trellis
keyPassword=…
```

Without it the build **fails** rather than emitting an unsigned APK — up to
v0.21.0 it produced `app-release-unsigned.apk` and the *debug* APK got shipped in
its place, which meant six releases went out signed with the public Android debug
key. Ship the artifact from `assembleRelease`, never the one from `assembleDebug`.

**Back up `trellis-release.jks`.** Losing it means never being able to update an
installed copy of the app again.

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
- **Table cells render `[[wiki-links]]` too.** A table is laid out as monospace so
  its columns line up, which means no Markdown engine sees it — so an evidence
  column of `[[#10215]]` used to read as its own brackets. Cells now show the link
  text, tappable in the reader, and the column padding is measured on what the
  cell *reads* as, so the alignment survives the substitution.
- **`trellis://` links open the app.** `trellis://7374/card/1391` — from a chat,
  a note, anywhere — opens that card here. The **port picks the workstation**,
  because one Trellis instance serves one document, so a link goes to the right
  document rather than to whichever server happens to be selected. A port no
  saved workstation uses says so instead of guessing: card ids repeat across
  documents, so guessing would land on a real card that is not the one meant.
  `hypercube://` is accepted too. Ask the desktop for a link with
  `GET /api/cards/{cid}/link` rather than assembling one.
- **The hypercube, as far as a viewer can carry it.** Trellis is a tree of
  baskets; a **basket** is the space — `x` and `y` always, `z` with Depth on and a
  time axis with Time on, at which point that basket is a *hypercube*. (The tree
  is not a dimension: it is the index over baskets.) **Time** is a real toggle
  here, in **Settings → Hypercube**, off by default: a journal day then also shows
  cards from *other days* whose `start::`→`due::` span covers it — the same card,
  drawn as a projection that names where it lives and takes you there when
  tapped. Same two limits as the desktop, both learned by running it: containment
  rather than the agenda's overdue rule, and only cards that live in other days.
- **Card depth is respected.** Desktop v0.92.0 gave cards a `z`; the viewer is
  flat, which is exactly what the desktop calls Depth-off — so `z` is read as the
  stacking order and cards draw (and are tapped) in the same order as on the
  desktop instead of in document order.
- **`[[wiki-links]]` are links** — tap one in the reader to follow it. `[[#1391]]`
  goes to that **card**: the basket opens centred on it with a brief outline,
  because in a journal every card written on a day shares a basket, so opening
  the right basket is not the same as arriving. `[[Roadmap]]`, `[[62]]` and
  `[[#1391|display text]]` all work, resolved exactly as the desktop resolves
  them (card id, then node id, then case-insensitive title). A link that resolves
  to nothing says so instead of doing nothing. Needs Trellis ≥ 0.87.0 for
  `GET /api/cards/{cid}`.
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
