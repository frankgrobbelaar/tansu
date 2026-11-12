# Changelog

What changed in each version of tansu, newest first, in plain words. A release moves what's under
"Unreleased" under its version; see [docs/releasing.md](docs/releasing.md).

## Unreleased

- Copying is safer still. A file only appears under its name once it's whole, so a stopped or
  cancelled job never leaves a broken file behind. A file that turns up while a job is paused is
  kept beside it, never written over, and Replace swaps the new file in in one step.
- Resuming or retrying a copy into an existing SFTP folder works with OpenSSH servers, and a full
  SFTP disk says so instead of waiting.
- A new version stays on offer in Settings after tansu closes.

- Pictures, videos and PDFs open in tansu. Swipe through a folder's pictures and pinch or
  double-tap to zoom; videos play straight from the server as they stream; PDFs scroll with the
  page you're on. Anything else still opens in another app.
- A folder shown as a grid shows its pictures, from the phone and from servers.

- Search a folder by name as you type, with what matched in bold.
- Sort by name, date, size or type, either way round, from Sort and view in the menu or in
  Settings. Any folder can be a grid instead of a list, and remembers it.

## 0.2.0 - 2026-11-13

Settings, updates that arrive in the app, and safer copying.

- Tansu is in the share sheet: share any number of files from Gallery, My Files or any app, pick
  a drawer and folder (it starts where you saved last time), and tap Save here. The save runs in
  the background and you're back where you were.
- Settings: light, dark or as the phone is; your wallpaper's colours if you like; hidden files in
  Browse; and your servers, to edit or add.
- Copying and moving are safer. Names that differ only in case count as a clash, a Replace keeps
  the original until the new copy is whole, Cancel never removes anything but the file it was
  writing, and deleting a link on an SFTP server leaves what it points to.
- A folder that can't be read or made no longer stops the app: it's listed with the files that
  didn't make it, and the next job goes on. A full disk says so at once.
- Uploads to SFTP servers that run OpenSSH, as most NAS boxes do, no longer stall on files over
  256 KB.
- Tansu updates itself. Once a day it asks GitHub for a new version (you can turn that off in
  Settings), shows what's new, and Download and install hands it to Android to confirm. Nothing
  about you is sent.

## 0.1.0 - 2026-11-13

The first version.

- Browse the phone's storage: folders first, every folder's count, the path to any parent, and
  files opened in the app Android picks.
- Add SMB and SFTP servers, at home or over Tailscale. Tansu checks the details before saving
  anything, shows an SFTP server's key to trust the first time, and keeps passwords and keys
  encrypted on the phone.
- Each server is a drawer to browse just like the phone, with Edit and Remove in its menu.
- When a server can't be reached, its login is turned down, its share is gone or its key changed,
  Browse says so in plain words with the next step.
- Select files and folders with a long press. Cut or copy them, then paste in any folder of any
  drawer; a name that's already there asks Keep both, Replace or Skip.
- Rename, new folder, details, share, and delete, which asks once and says what, how much and
  where.
- Open and share files on servers too: tansu brings them to the phone first, with their progress
  and Cancel, then hands them to the app you pick.
- Every copy, move and delete is a job with its progress, time left and a notification. Jobs keep
  going when you leave tansu, and can be paused, resumed, cancelled and retried.
