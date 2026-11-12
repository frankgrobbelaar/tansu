# Changelog

What changed in each version of tansu, newest first, in plain words. A release moves what's under
"Unreleased" under its version; see [docs/releasing.md](docs/releasing.md).

## Unreleased

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
