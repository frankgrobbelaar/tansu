# Changelog

What changed in each version of tansu, newest first, in plain words. A release moves what's under
"Unreleased" under its version; see [docs/releasing.md](docs/releasing.md).

## Unreleased

## 0.5.0 - 2026-11-13

Jobs survive Android closing tansu and carry on where a file broke off; quicker photo grids,
the shares on a NAS, and text that reads in dark mode.

- Jobs survive Android closing tansu: one that was running, waiting or paused comes back paused
  the next time tansu opens, to carry on where it was. A delete comes back cancelled, so it never
  takes files that arrived since.
- A big file whose copy breaks off, as when the phone moves from Wi-Fi to mobile data, carries on
  from where it was, rather than starting again; so does one paused part-way and resumed. A link
  that drops now and then keeps a job going, as long as each go gets further.
- Pictures in the grid show much sooner from servers, and at once when you come back to a
  folder, even after tansu restarts: for a photo from a camera, tansu reads only the small
  picture saved inside it, not the whole photo, and it keeps the thumbnails it makes.
- When an SMB server has no share by the name given, tansu lists the shares it does have. In
  Browse, tapping one points the drawer at it; in the server form, it goes in the Share field. A
  share the server has but can't open, as when its disk isn't mounted, says just that.
- Pasted files show up in the folder as they land, rather than when the whole job ends.
- A job's detail opens each picked folder to show the files in it, and where each stands.
- Replace replaces only the file you chose to replace: one saved again since is kept, beside the
  copy. A move never deletes a copy that's changed on the server since it was made.
- An empty folder, or a message about a server, can be pulled down to look again, as a folder of
  files can.
- The Add a server chip beside the drawers shows until you've added a server; then it's in More
  and Settings, so the drawers fit on a narrow phone.
- In dark mode, text on the first run and in Save to (sharing into tansu) is light, where it was
  black and hard to read.

## 0.4.4 - 2026-11-13

Servers that are slow to answer, as over Tailscale, are waited for, and say why when they can't
be reached.

- Servers that are slow to answer, as a NAS reached through Tailscale often is while it wakes up,
  are given more time and tried again before tansu says it can't reach them. Browse says when
  it's waiting for one.
- When a server can't be reached, tansu says whether it didn't answer, has a name that isn't
  known, or turned the connection away, and what to check for each.
- Pictures, videos and files open from SMB servers much faster over Tailscale: tansu reads them
  256 KB at a time, where it read 8 KB.
- A big file whose copy breaks part-way, as when the phone moves from Wi-Fi to mobile data, is
  tried again, rather than pausing its job at once.
- The Share field takes a whole address too, such as smb://nas/Media.

## 0.4.3 - 2026-11-13

One more fix for moving folders.

- An empty folder moved as the job was paused no longer fails as missing when it resumes.

## 0.4.2 - 2026-11-13

A few more corners of moving files made safe.

- A move paused at just the wrong moment can no longer take the file it was replacing for its
  own copy, and it leaves an original that changed while the job was paused.
- A moved folder the server can't yet say is gone no longer fails the rest of the job, and a
  folder that disappeared before its move began says so.
- Opening tansu again from Recents no longer jumps back to a finished job's folder.

## 0.4.1 - 2026-11-13

Copying and moving that hold up when a server's answer goes missing, and a handful of fixes.

- Moving with Replace replaces a different file the same size, as asked; only the very file
  being moved, reached another way such as through a second drawer on the same disk, is kept. A
  move leaves the original where it is if it changed after it was copied.
- A server's lost answer as a file is named no longer leaves a second copy, even when the job
  stops right then and another file goes first when it resumes. A dropped connection never makes
  a rename twice, and a move within an SFTP drawer whose answer was lost no longer says the file
  is already there.
- Files shared from apps that don't say how big they are save rather than fail; one that does
  and arrives short still fails.
- A job's detail marks a folder done as soon as everything in it is, and a cancelled job's
  picks that never went as stopped. A moved folder that couldn't be removed is tried again when
  the job resumes, and two folders whose names differ only in case keep their places.
- Renaming on a server whose connection had quietly dropped works the first time.
- Only tansu's own notifications can open a folder in it, and a finished job's notification
  still does after Android has closed tansu; a cancelled job's opens tansu. Choosing where to
  save a share no longer hides how other jobs ended.
- A version on offer stays on offer when GitHub doesn't answer as tansu starts.
- Videos keep the screen on only while they play. A PDF closed as it opens is let go of.

## 0.4.0 - 2026-11-13

A safer copy again, steadier viewers, and the small things.

- Copying is safer again. A file is checked against where it came from before it takes its
  name, so one still being written is caught rather than copied short; a move never replaces
  the very file it's moving; a server's lost answer no longer leaves a second copy; two folders
  whose names differ only in case stay apart on the phone and SMB; and long names fit.
- A job's detail lists what was picked, each with where it stands: waiting, on it now, done,
  skipped or not done. A refusal names the drawer that said no, and a refused login or a changed
  key says so.
- Tapping a finished job's notification opens the folder its files went to. While tansu is in
  front it says how a job ended itself, without a notification too.
- Videos keep the screen on, keep playing when the phone turns, and pause for a call or when
  headphones come out. Pictures, videos and PDFs reach the screen's edges, and a PDF with very
  tall pages no longer stops tansu.
- Replace works on SFTP servers that can't rename over a file in one step.
- Settings › About lists the open-source work tansu is built with, and its licences.

## 0.3.0 - 2026-11-13

Pictures, videos and PDFs in tansu, search and sort, and copying made safer still.

- Pictures, videos and PDFs open in tansu. Swipe through a folder's pictures and pinch or
  double-tap to zoom; videos play straight from the server as they stream; PDFs scroll with the
  page you're on. Anything else still opens in another app.
- Search a folder by name as you type, with what matched in bold.
- Sort by name, date, size or type, either way round, from Sort and view in the menu or in
  Settings. Any folder can be a grid instead of a list, with its pictures showing, and remembers
  it.
- Copying is safer still. A file only appears under its name once it's whole, so a stopped or
  cancelled job never leaves a broken file behind. A file that turns up while a job is paused is
  kept beside it, never written over, and Replace swaps the new file in in one step.
- Resuming or retrying a copy into an existing SFTP folder works with OpenSSH servers, and a full
  SFTP disk says so instead of waiting.
- A new version stays on offer in Settings after tansu closes.

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
