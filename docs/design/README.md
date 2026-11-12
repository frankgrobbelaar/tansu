# Tansu's design pages

Snapshots of the [Tansu Design canvas](https://claude.ai/artifact/HCxYiAaQqE7Qr8RqXmuDXB) as plain
pages: open [index.html](index.html) in a browser and click through. The canvas is the source of
truth (the owner's, and private); these pages are the shared copy.

| Board | Page |
| --- | --- |
| Tansu workflows: how it fits together, and every flow | [index.html](index.html) |
| Kiri 桐: colour, type, shape and motion, and Browse in light and dark | [kiri.html](kiri.html) |
| The three directions, Kiri chosen, and the rules they share | [directions.html](directions.html) |
| 1 · First run | [first-run.html](first-run.html) |
| 2 · Add a server | [add-server.html](add-server.html) |
| 3 · Browse | [browse.html](browse.html) |
| 4 · Open a file | [open-file.html](open-file.html) |
| 5 · Cut, copy and paste | [cut-copy-paste.html](cut-copy-paste.html) |
| 6 · Manage files | [manage-files.html](manage-files.html) |
| 7 · Share into tansu | [share-in.html](share-in.html) |
| 8 · Jobs and progress | [jobs.html](jobs.html) |
| 9 · When something's wrong | [problems.html](problems.html) |
| 10 · Settings and updates | [settings.html](settings.html) |

Screens are drawn in light at the Galaxy S24's 360 × 780 dp, with made-up files, folders and
servers. Screens marked as Android's own (the All files access setting, the share sheet, a
notification, the install prompt) are simplified stand-ins. Fonts load from Google Fonts, so the
pages need the internet to look exactly right.

## The Kit

The canvas's Kit boards are the parts every screen reuses: the top bar, the drawers, the path, a
file row, a job card and the navigation bar. Boards mount them with `<dc-import>`, so a change to a
Kit board shows up on every screen. The snapshot draws them into each page; they have no page of
their own.

## Building from these

The markup is plain HTML with inline styles, so every size, colour and spacing can be read off
directly. The values match the tokens in [design.md](../design.md) one for one, and each Kit part
maps to one Compose component.

## Refreshing the snapshot

The pages are generated; don't edit them. When the canvas changes, ask Claude to read it (its
`project/` files) into a folder, then:

```powershell
node scripts/design-snapshot.mjs --design <folder>
```
