# Design

How tansu looks and behaves. The [Tansu Design canvas](https://claude.ai/artifact/HCxYiAaQqE7Qr8RqXmuDXB)
holds the boards (the owner's, and private); [design/](design/README.md) has them as pages you can
open locally.

## Kiri 桐

Material 3 Expressive through Compose Material 3, with Kiri's tokens on top. Kiri is named after
paulownia, the pale wood most tansu are made of: calm and warm, with sand and paper surfaces, a
wood-brown accent with sage beside it, soft corners and quiet motion. The owner picked it over Ai
(indigo, crisp and native) and Urushi (lacquer, bold and expressive); all three are summarised on
the canvas's first board.

### Colour

Material 3 colour roles. Dynamic colour is an option, off by default: it swaps these for the
wallpaper's colours, and type, shapes and motion stay.

| Role | Light | Dark | Used for |
| --- | --- | --- | --- |
| Surface | `#FBF7F1` | `#1B1815` | The screen |
| Container | `#F1E9DE` | `#26211C` | The navigation bar |
| Container high | `#EADFD0` | `#312B25` | Cards, such as a transfer in progress |
| On surface | `#2B2622` | `#EDE4D9` | Main text |
| On surface variant | `#6A5F55` | `#BFB2A3` | Secondary text, file icons |
| Outline variant | `#DCCFBE` | `#4A4139` | Chip borders, dividers |
| Primary | `#8A5A2B` | `#E2B07A` | Progress and the one strong action on a screen |
| Primary container | `#F3DFC6` | `#5C3D1E` | Folders, the selected drawer |
| On primary container | `#4A2E12` | `#FADDBA` | Text and icons on primary container |
| Secondary container | `#E3E8D5` | `#3E4A31` | The navigation indicator |
| On secondary container | `#2C3520` | `#D9E4C6` | The icon in the indicator |

Text keeps at least 4.5:1 contrast against its background in both modes.

### Type

Figtree (open source, OFL), bundled with the app. Numbers use tabular figures. A monospace face
(Android's own) appears only for server keys and full paths.

| Style | Size | Weight | Example |
| --- | --- | --- | --- |
| Headline | 22 | 700 | Photos |
| Title | 15 | 600 | IMG_2041.jpg |
| Body | 13 | 400 | 312 items · 14 Mar |
| Label | 12 | 600 | Copying 3 files to NAS |

### Shape and motion

- Corners: 8 dp small, 14 dp medium, 20 dp large, and full for pills. File tiles are 12 dp, chips
  10 dp and cards 18 dp.
- Motion is calm: fades and short slides of 200 to 300 ms with no overshoot (Compose's standard
  motion scheme).
- Icons are thin rounded lines (1.6 stroke). Folders sit on a primary-container tile; other files
  are a plain icon in on-surface-variant, and pictures show a thumbnail.

## Components

The canvas's Kit boards are the parts every screen reuses; each becomes one Compose component.

| Part | What it is |
| --- | --- |
| Top bar | Back, Close or nothing on the left; the title (and a subtitle, such as a selection's size); search and More on the right |
| Drawers | The row of places under the top bar: the phone, then each server, with a quiet dot when one has a problem |
| Path | The current folder's path, every part tappable, with a count on the right |
| File row | A folder tile, a plain icon or a thumbnail; the name, then the size and date or a progress bar; a check when selecting |
| Job card | What a job is doing, a progress bar, how far and how long, and one action: pause, resume, cancel or retry |
| Navigation bar | Browse, Transfers and Settings, with a count on Transfers and a dot on Settings when an update is ready |

Dialogs ask one question with at most three answers, the safe one first. Sheets slide up for
choices about the current screen (sort and view, details, a download before opening). The paste
bar and the snackbars are dark, so they read as "in your hand" rather than part of the page.

## Rules every screen follows

1. **Material 3 Expressive underneath.** Compose's components, accessibility and motion; Kiri only
   sets colour, type, shape and motion.
2. **Light and dark.** Follows the phone's setting, with an override in Settings.
3. **Dynamic colour as an option.** It replaces the palette with the wallpaper's; nothing else
   changes.
4. **Drawers.** Each place files live (the phone, the NAS, an SFTP server) is a drawer at the top
   of Browse.
5. **Honest progress.** A transfer says what it's doing, how far it got and how long is left, and
   it can always be paused.
6. **Readable first.** Text at 4.5:1 or better in both modes, 48 dp touch targets, and file types
   told apart by icon shape before colour.

## Animation

1. Motion explains change; it never decorates.
2. Short and calm: 100 to 200 ms for small elements, 250 to 350 ms for screens and sheets.
3. One motion system: the same springs everywhere, interruptible, picking up a swipe's speed.
4. Spatial continuity: a folder opens out of its row and closes back into it; deeper slides one
   way, back the other.
5. Show what changed: pasted files slide in, deleted ones fade out, sorted rows move into place.
6. Gestures lead: predictive back previews the previous screen, and drags follow the finger.
7. Animate cheap properties (move, scale, fade), never layout on every frame.
8. Never block input during a transition.
9. With "Remove animations" on, motion is instant or a fade, and no meaning depends on it.
10. Measure on release builds: at 120 Hz a frame has about 8 ms. Drop what stutters.

## Navigation

1. Three top-level places, Browse, Transfers and Settings, in a bottom bar.
2. Back goes up a folder, then to the drawer's root, then out. System gestures are left alone.
3. Each tab keeps its history and scroll position, even after Android closes the app.
4. Always show where you are: the folder as the title, a tappable path, the drawer highlighted.
5. Back retraces your steps one at a time; the path jumps straight to a parent.
6. The main action sits within thumb reach; search and More stay in the top bar.
7. Entry points land in context: sharing in opens the destination picker, and a job's notification
   opens that job.
8. Short tasks with Cancel; no dialogs on top of dialogs.
9. Navigation is state: one typed back stack, saved and testable.
10. Phones get a bottom bar; larger screens get a side rail with the same places.

## Experience

1. Fast, always: reopen where you left off, show cached listings at once, and give every tap
   instant feedback.
2. Honest progress: what, how far and how long, with pause, cancel and a summary at the end.
3. Errors that help: the problem, what tansu already did, and the next step.
4. Every state is designed: loading, empty, error, offline, no permission and partly failed.
5. Confirm only what can't be undone; reversible actions get Undo.
6. Never lose data: copy, check, then delete; clean up half-copied files; ask on name clashes.
7. Selection that scales: long-press, tap, drag a range, select all, with the count and size shown.
8. Thumb and eye friendly: 48 dp targets, readable at large font sizes, 4.5:1 contrast.
9. Plain words with human units: "1.4 GB", "1 min left", "Yesterday".
10. Respect the user: no sign-in, ads or tracking, and permissions asked for when needed, with a
    reason.

## The design pages

[design/](design/README.md) holds the canvas as plain pages, written by
`scripts/design-snapshot.mjs`. They're generated, so don't edit them. When the canvas changes, ask
Claude to read it (its `project/` files) into a folder, then:

```powershell
node scripts/design-snapshot.mjs --design <folder>
```
