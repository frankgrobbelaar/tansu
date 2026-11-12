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
| 9 | Polish | Accessibility, performance, dark theme and the small things | |

The release signing key and its GitHub secrets were made on 12 Nov 2026 (see
[releasing.md](releasing.md)); the owner keeps the backup.

A review of iterations 3 to 5 found a crash when a folder couldn't be listed or made, SFTP
uploads over 256 KiB failing against OpenSSH, and three ways to lose a file (names differing only
in case, Cancel after a refused Replace, and deleting a link on SFTP). They're fixed and tested. A second review of those fixes found more ways a paused, resumed or
case-blind job could write over a file; every file is now written under a hidden name and only
takes its real name once it's whole, never over anything the user didn't choose to replace.

## Left for later

- Jobs live in memory: a job queued or paused when Android ends tansu's process is lost. The
  architecture plans for saving them.
- Sharing files from a server out to other apps needs them fetched first, which comes with opening
  server files (iteration 6).

- From the same review, still to do: fetching a server file to open or share it runs in Browse
  rather than as a job with a notification; one timed-out listing restarts a running transfer's
  file; and the job detail doesn't list files one by one yet.
- A missing SMB share should list the shares the server does have (spec, flow 8). Listing shares
  needs SMB's RPC calls, which smbj leaves to another library; until then the message offers Edit
  server.
