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
(indigo/slate — the desktop signature). Plus six that mirror the desktop:
**Sticky Notes** (solid single-color paper cards — yellow by default — on a cork
board), **Futuristic** (a blue HUD with beveled tech-panel cards), **SynthWave**
(the outrun neon palette), and three taken from instruments rather than
interfaces — **Blueprint** (cyan linework on Prussian blue; cards are drawing
sheets with a title block and registration ticks), **Silkscreen** (white legend
and gold pads on solder-mask green; each card a part with a pin-1 dot), and
**Phosphor** (a storage scope: P31 blue-green traces, no fills, a beam rule under
each title). Set in Settings → Appearance.

**A theme belongs to a workstation, not to the app (v0.29.0).** One Trellis
instance serves one document, so the server *is* the document — and telling work
from personal at a glance is worth more than one colour everywhere. Each
configured workstation keeps its own accent; one that has never been given an
opinion follows the app default, so changing the default still moves everything
that has not chosen.

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
- **The hypercube — both axes, on the basket's own menu (v0.28.0).** Trellis is
  a tree of baskets; a **basket** is the space — `x` and `y` always, `z` with
  **Depth** on and a time axis with **Time** on, at which point that basket is a
  *hypercube*. (The tree is not a dimension: it is the index over baskets.) Both
  are off by default and both are in the basket's overflow menu, where you can
  see what they do — they were reachable only from Settings, which is how a
  basket could be missing a card that is on the desktop with nothing to say why.
  - **Depth** projects every card through the **same pinhole camera the desktop
    uses** — same camera distance, same clamps — so a basket arranged in depth
    reads as the same arrangement on both. Cards are drawn far-to-near and each
    is **hit-tested through its own projection**, because tapping where a card
    looks and getting a different one is how a 3-D view becomes unusable while
    still looking correct. Off, `z` is only the stacking order and nothing is
    lost.
  - **Time** shows, in a journal day, the cards from *other days* whose
    `start::`→`due::` span covers it — the same card, drawn as a projection that
    names where it lives and takes you there when tapped. Same two limits as the
    desktop, both learned by running it: containment rather than the agenda's
    overdue rule, and only cards that live in other days.
  - **When it shows you nothing, it says why.** Time off, a basket that is not a
    journal day, or a token that is refused `/api/tasks` (a subtree-scoped agent
    token is) each produce a note under the basket title instead of an empty
    canvas — the silence was the actual bug.
- **`[[wiki-links]]` are links** — in a card's **body**, in a **table cell**, and (since v0.33.0) in a card's **title**. Tap one to follow it. `[[#1391]]`
  goes to that **card**: the basket opens centred on it with a brief outline,
  because in a journal every card written on a day shares a basket, so opening
  the right basket is not the same as arriving. `[[Roadmap]]`, `[[62]]` and
  `[[#1391|display text]]` all work, resolved exactly as the desktop resolves
  them (card id, then node id, then case-insensitive title). A link that resolves
  to nothing says so instead of doing nothing. Needs Trellis ≥ 0.87.0 for
  `GET /api/cards/{cid}`.
- **Emphasis is honoured (v0.30.0)** — a card the desktop or an agent marked
  **glow** or **pulse** wears the same halo here. The phone reads
  `emphasis_live`, the field that already accounts for the expiry, so a lapsed
  highlight is gone before it arrives and the two never disagree about what
  "now" is. A pulse asks for another frame **only while one is on screen**;
  otherwise an animation callback would spin the view for ever on a basket with
  no emphasis in it, on a battery.
- **Capture** — the **+** button in a basket adds a **note**, a **camera photo**,
  or a **picked image** as a card in that node.
- **Editing (v0.27.0)** — the three edits worth making on a phone, each one call
  to the desktop API, each addressed to the card itself:
  - **A card's text.** Open a text or code card and tap **Edit**. You edit the
    card's *source*, never the rendered form, and nothing is shown as changed
    until the document says it changed — a failed save keeps you in the editor
    with your text and names the error.
  - **A checklist tick.** Lines are real checkboxes, each addressed by its
    **stable item id** (desktop ≥ 0.90.0), so ticking survives a reorder. A tick
    that fails to write springs back rather than lying.
  - **`status::`** — from the reader's overflow menu: todo / doing / blocked /
    done, or clear it. It edits the property **in place** on the one card, which
    is the whole point of the model: a copied task card is a second task.

  Deliberately not editable here: tables, sketches, and a card that **mirrors a
  file** — the file owns that text, and the desktop refuses the edit too (409).
- **Tags** (overflow menu) — every `#tag` in the document with its card count;
  tap one for the cards that carry it, and tap a card to arrive **at the card**,
  not merely in its basket. In a journal every card written that day shares one
  basket, so opening the basket is not the same as arriving.
- **What links here** — on a basket's menu and on a card's, the cards whose text
  carries a `[[wiki-link]]` to it. Following a link is the easy half; knowing
  what points *at* what you are reading is the half that needs a screen. An empty
  result says so, and names the link syntax that would fill it.
- **Link graph** (overflow menu) — which baskets link to which, as a picture.
  The tree already shows the hierarchy, so this exists to show the shape the
  hierarchy cannot: the links that cut across it. Laid out by an **annealed force
  simulation** that comes to rest and then stops asking for frames — a physics
  loop that never converges is a battery drain that looks like a feature. Seeded
  on a circle so the same document lays out the same way every time; busier
  baskets draw bigger; arrowheads say which way each link points. Pinch, drag,
  and tap a node to open that basket.
- **Agenda and Kanban** (overflow menu) — the desktop task views, read-only. Each
  row names the task's **full basket path** (`Newsletter › Open Items`),
  because basket names repeat across projects. **Filter by project** in either
  view's overflow menu narrows it to one; the two views remember their own choice,
  per server, so scoping the board doesn't narrow your agenda. Needs Trellis
  ≥ 0.71.0 for the project fields.
- Live updates via the desktop's `/api/wait` long-poll (Trellis ≥ 0.30.0).
- **Notifications (v0.34.0)** — **Settings → Notifications**, both off by
  default: what is **overdue or due today**, and when an **agent changes
  something**. Two channels, so Android can silence one without losing the
  other, and a tap lands on the Agenda or on the changed card.

  The honest limits, stated in Settings rather than discovered. It checks about
  **every fifteen minutes** (Android's floor for periodic work) and only the
  **workstation you have selected** — the others are different documents, and
  checking them all turns one notification into N. **Nothing arrives when the
  phone can't reach the host**: Trellis is a document on your LAN, not a service,
  so off the network the check is a quiet no-op rather than an hourly complaint.
  An unchanged digest is not repeated, and nothing due sends nothing.

  **Check now** runs it immediately — a periodic job you can't trigger is a
  feature you can't confirm.
- **Offline cache (read-only)** — every basket and image you open is cached on the
  phone, so when the LAN Trellis goes offline you can still read what you've
  already viewed. Editing needs the host: an edit is written straight through,
  never queued. An "⚠ Offline — cached copy" note appears under the title while
  serving the cache; the app returns to the live document (and refreshes the
  cache) automatically the moment the host is back. No sync — writes still need
  the desktop.
