# Spec

What tansu does and what it leaves out. How it looks is in [design.md](design.md), how it's built
in [architecture.md](architecture.md), and what was decided in [CLAUDE.md](../CLAUDE.md).

## The idea

A file manager for your phone and your own servers that just works: fast, clean, no sign-in, no
ads. It's made for one person, their Android phone (Android 14 or newer; the main device is a
Galaxy S24), and their SMB and SFTP servers at home or over Tailscale.

## Drawers

A drawer is a place files live. Drawers sit in a row at the top of Browse.

| Drawer | What it is | Needs |
| --- | --- | --- |
| Phone | The phone's own storage, always there | All files access |
| SMB server | A share on a NAS or PC, over SMB 2 or 3 | Host, share, optional folder, username and password (or guest) |
| SFTP server | Any SSH server | Host, port (22), optional folder, username, and a password or private key |

A host is a tailnet name, a local name or an IP. Tailscale runs on its own; tansu just connects.

## Flows

### 1. First run

1. Tansu says why it needs All files access and opens the system setting.
2. Back in tansu, Browse opens on the Phone drawer, with a quiet card offering "Add a server".
3. If access isn't granted, Browse shows the reason and a Grant button instead.

### 2. Add a server

1. Pick SMB or SFTP, then fill in a name, the host, the share or folder, and the login. SFTP takes
   a password or a private key from the phone.
2. Connect checks the details and says what it's checking, with Cancel. The first SFTP connection
   shows the server's key fingerprint, to trust or cancel. A share that isn't there lists the
   server's shares, and tapping one puts it in the Share field.
3. If everything works, the server is saved and opens as a new drawer. Edit and Remove are in the
   drawer's menu and in Settings › Servers.

### 3. Browse

- Folders first, then files. Sort by name, date, size or type; list or grid; hidden files off by
  default.
- Back goes up one folder; tapping the path jumps to any parent.
- Search filters the current folder by name.
- Pull to refresh. Each drawer remembers its last folder, and tansu reopens where you left off.

### 4. Open a file

- Pictures, videos and PDFs open in tansu. Pictures and videos on a server stream without
  downloading first.
- Anything else opens in another app. Server files are fetched first, with progress.

### 5. Cut, copy and paste

1. Long-press to select, tap to add more, or select all. The bar shows the count and total size.
2. Choose Cut or Copy. A paste bar follows you: "3 items · Paste here".
3. Go to any folder in any drawer and tap Paste here. The job starts in the background.

- Name clashes show both files and ask Keep both (the safe default), Replace or Skip, with "Do this
  for the rest".
- Cut within one drawer is a rename, done at once. Across drawers it copies, checks the size, then
  deletes the original.

### 6. Manage files

- New folder, rename, details (size, dates, path), share to other apps (server files are fetched
  first) and delete.
- Delete asks once, saying what, how much and where ("Delete 12 items from NAS? 48 MB from Media ›
  Photos › 2025. There's no trash, so this can't be undone."), then deletes with progress.

### 7. Share into tansu

1. Tansu is in the share sheet for any files, one or thousands, from Gallery, My Files or any app.
2. Pick a drawer and folder (it starts at the last one used), then tap Save here.
3. The copy runs in the background, so you can go straight back to where you were.

### 8. A server that can't be reached

- Browse says what went wrong and what to try, such as "Can't reach NAS. Is Tailscale on?", with
  Retry and Edit server. It tells a server that didn't answer from a name that isn't known and a
  server that turned the connection away.
- A server that's slow to wake up is waited for, and Browse says so, rather than giving up at
  once.
- A wrong password, a missing share and a folder you can't open each get their own message. A
  missing share lists the shares the server does have, and tapping one uses it instead, from its
  top. A share the server has but can't open, as when its disk isn't mounted, says so.
- A changed SFTP key blocks the connection until you keep it blocked or trust the new key.
- The drawer shows a quiet dot while its server has a problem.

## Jobs and progress

Every copy, move, delete and share-in is a job.

- A job shows what it's doing, how far it got (percent, items and size) and how long is left: in a
  card in Browse, on the Transfers tab and in a notification.
- It keeps running when you leave tansu.
- Transfers can be paused and resumed, and any job can be cancelled. Cancelling removes a
  half-copied file; finished files stay.
- Jobs run one at a time, in order. Each ends with a summary ("Copied 128 files to NAS, 2
  skipped"); failures list every file and why, with Retry.
- A dropped connection is retried for at least 30 seconds, then the job pauses and says why. Resume picks
  up from the file it was on.

## Settings

- **Appearance:** system, light or dark; dynamic colour (off by default).
- **Browse:** show hidden files, default sort, list or grid.
- **Servers:** add, edit, remove.
- **About:** version, updates (a daily check that can be turned off) and open-source licences.

## Security and privacy

- No sign-in, accounts, analytics or ads. Tansu talks only to the servers you add, and to GitHub
  once a day to look for a new version (which can be turned off).
- Server details stay on the phone. Passwords and private keys are encrypted with an Android
  Keystore key, never logged, and left out of backups.
- SFTP host keys are pinned on first connect; a changed key blocks the connection with a warning.
- SMB 2 and 3 only, signed or encrypted when the server offers it.
- Tansu asks for All files access, notifications and (on Android 17) the local network, each when
  first needed and with a reason.

## Quality bar

- Opens to the last folder in under a second; a 1,000-item phone folder lists in under 300 ms.
- Scrolling stays smooth at the S24's 120 Hz.
- TalkBack labels, 48 dp touch targets, readable at 200% font size, and 4.5:1 text contrast.

## Distribution

Sideloaded only, never on a store: the owner shares the APK with people they know, and tansu
updates itself from GitHub Releases. How releases, first installs and updates work is in
[releasing.md](releasing.md).

## Not in version 1

NFS, FTP, WebDAV and cloud drives; sign-in, sync and automatic backups; a trash; zip and unzip; USB
drives and SD cards; editing files; root access; storage cleaning; layouts designed for tablets.
