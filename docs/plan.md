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
| 3 | Jobs | Copy, move and delete with progress, notifications, the Transfers tab, pause, resume, cancel and retry | |
| 4 | Select and paste | Selection, cut, copy and paste across drawers, clashes, rename, new folder, details, delete, share out | |
| 5 | Share in | Tansu in the share sheet, and the Save to picker | |
| 6 | Open files | Pictures, videos and PDFs in tansu; other files in another app | |
| 7 | Browse extras and Settings | Search, sort and view, grid, hidden files, and Settings | |
| 8 | Releases and updates | The `release` branch and its workflow, signing, the downloads page, and the in-app update | |
| 9 | Polish | Accessibility, performance, dark theme and the small things | |

Two things wait for the owner: making the release signing key (and its GitHub secrets), and the
first push to `release`.

## Left for later

- A missing SMB share should list the shares the server does have (spec, flow 8). Listing shares
  needs SMB's RPC calls, which smbj leaves to another library; until then the message offers Edit
  server.
