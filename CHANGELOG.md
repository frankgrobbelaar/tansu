# Changelog

What changed in each version of tansu, newest first, in plain words. A release moves what's under
"Unreleased" under its version; see [docs/releasing.md](docs/releasing.md).

## Unreleased

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
- Rename, new folder, details, share files from the phone, and delete, which asks once and says
  what, how much and where.
- Every copy, move and delete is a job with its progress, time left and a notification. Jobs keep
  going when you leave tansu, and can be paused, resumed, cancelled and retried.
