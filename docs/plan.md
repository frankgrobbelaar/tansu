# Build plan

Tansu is built in small iterations. Each one goes through the same loop before it lands on `main`:

1. **Develop** the slice, test first where it pays off.
2. **Test**: formatting, detekt, Android Lint, unit tests and a build all pass. Screens are checked
   on the emulator against the design boards.
3. **Review**: a fresh review of the change, looking for bugs and for drift from the principles in
   [CLAUDE.md](../CLAUDE.md). Findings are fixed.
4. **Repeat** until the slice is right, then commit and push.

| # | Iteration | What it delivers | Status |
| --- | --- | --- | --- |
| 0 | Foundation | The Gradle project, linting and formatting, CI, the Kiri theme, and the app shell with Browse, Transfers and Settings | Done |
| 1 | Browse the phone | All files access, the file-system interface, the phone drawer, folders and files, the path and back | Done |
| 2 | Servers | Adding SMB and SFTP servers, encrypted credentials, host keys, server drawers, and the problem states | Done |
| 3 | Jobs | Copy, move and delete with progress, notifications, the Transfers tab, pause, resume, cancel and retry | Done |
| 4 | Select and paste | Selection, cut, copy and paste across drawers, clashes, rename, new folder, details, delete, share out | Done, apart from sharing files from servers |
| 5 | Share in | Tansu in the share sheet, and the Save to picker | Done |
| 6 | Open files | Pictures, videos and PDFs in tansu; other files in another app | Done |
| 7 | Browse extras and Settings | Search, sort and view, grid, hidden files, and Settings | Done |
| 8 | Releases and updates | The `release` branch and its workflow, signing, the downloads page, and the in-app update | Done |
| 9 | Polish | Accessibility, performance, dark theme and the small things | Under way: each job's picks in its detail, the finished notification opening the folder, TalkBack labels on rows, the licences, quick grid thumbnails kept for servers' pictures |

The release signing key and its GitHub secrets were made on 12 Nov 2026 (see
[releasing.md](releasing.md)); the owner keeps the backup.

A review of iterations 3 to 5 found a crash when a folder couldn't be listed or made, SFTP
uploads over 256 KiB failing against OpenSSH, and three ways to lose a file (names differing only
in case, Cancel after a refused Replace, and deleting a link on SFTP). They're fixed and tested. A second review of those fixes found more ways a paused, resumed or
case-blind job could write over a file; every file is now written under a hidden name and only
takes its real name once it's whole, never over anything the user didn't choose to replace.
A third review, of those and the viewers, found a few more corners (a retried merge into a
case-blind folder, a move onto the very file it moves, lost answers from a server, very tall PDF
pages, streams left open by a cancelled thumbnail); each is fixed and tested where it can be.
A fourth review found that a move with Replace judged "the very file it moves" by size alone,
that a lost answer could still double a file kept beside another or a rename on a dropped
connection, that shared files without a size failed, and a few smaller leaks and races (a PDF
closed as it opened, a folder request shown twice, an update offer lost to a slow start). They're
fixed and tested. A fifth review of those fixes found that a job resumed after stopping mid-name
could still copy that file twice if another went first, that SFTP answers a re-run rename with
Exists, that share-ins had lost their size check, and that the notification's way in didn't
survive Android closing tansu; each is fixed and tested, and a move now leaves an original that
changed after it was copied. A sixth review found that a pause as a refused copy was cleared away
could pass for its naming, that a folder gone before its move began counted as moved, that a
server unable to say a folder was gone could end the job, and that the original's state as
copied didn't survive a pause; all four are fixed and tested. A seventh review found one
regression, an empty moved folder failing as missing after a pause, and nothing that could lose
a file; it's fixed and tested. An eighth review, of carrying on with a file that broke off, found
that a half could be carried on with by another file whose hidden name shared its hash, that a
half never reached stayed behind, that a source rewritten with its date kept or cut short could be
stitched, and that the retry window didn't count progress; each is fixed and tested. Jobs now
survive Android ending tansu, tested by ending the process at every step.

## Left for later

Nothing for now.

## Decided

- Fetching a server file to open in another app, or to share, stays in Browse with its progress
  and Cancel, not a job with a notification: it's one file the user is waiting on, like the
  update download (decided 2026-11-13).
