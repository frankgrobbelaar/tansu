# Architecture

How tansu is put together. This is the plan; it's updated as the code lands.

## Layers

One `:app` module and one activity, in three layers:

```
UI (Compose screens) → ViewModels (one StateFlow<UiState> each) → repositories → data sources
```

| Package | Holds |
| --- | --- |
| `ui/` | One package per feature (browse, viewer, transfers, servers, settings, share) and `ui/theme` (Kiri) |
| `files/` | The `FileSystem` interface and its phone, SMB and SFTP implementations |
| `servers/` | Saved servers (DataStore) and the credential vault (Android Keystore) |
| `jobs/` | The job queue, the transfer engine and its notification |
| `updates/` | The daily check of GitHub's latest release and the hand-off to Android's installer |
| `TansuApplication.kt`, `AppContainer.kt` | The application and its dependency container, wired by hand |

## Drawers

Every drawer is a `FileSystem`: list, stat, read from an offset, write, create a folder, rename and
delete. Screens and jobs only ever see this interface.

| Drawer | Built on | Notes |
| --- | --- | --- |
| Phone | `java.nio`, with All files access | The phone's shared storage |
| SMB | smbj | One connection and session per server, reused; SMB 2 and 3 |
| SFTP | sshj | One SSH connection per server, reused; host key checked against the pinned one |

A connection opens on first use, is shared by browsing and jobs, and reconnects after a drop.
`Drawers` keeps one `FileSystem` per server, and so one connection, until the server is edited or
removed. Library errors become a few typed errors (`FileSystemException`: `Unreachable`,
`AuthFailed`, `BadKey`, `ShareMissing`, `HostKeyUnknown`, `HostKeyChanged`, `NotFound`,
`AccessDenied`, `Unreadable`), which the UI puts into words; nothing else escapes a `FileSystem`.

Servers are given time, because a NAS reached through Tailscale is often waking up: Tailscale
finding its way to it, or its disks spinning up. Connecting has 20 seconds, and each call 30.
`Reconnecting` tries a connection that fails at once (no name yet, no route yet) again after 1, 2
and 4 seconds, but not one that waited out its timeout or that the server turned away; calls that
waited meanwhile share how it went, rather than each trying again. A call that finds the
connection dead, or gets no answer in time, is tried once more on a new connection, unless it
mustn't happen twice: a rename isn't, and a new folder whose answer was lost is looked for before
it's made again. A dead connection is hung up at once, without waiting on the server.
`Unreachable` says what the network said (no answer, no such name, or turned away), and Browse
and the server form say what to check for each. Browse says it's waiting for the server once a
listing has taken 3 seconds.

SMB reads ask for 256 KiB at a time, so a server across Tailscale isn't a round trip per few
kilobytes. smbj's own stream would ask for the next piece early, but it takes a read the server
turns down for the end of the file, so tansu reads with its own. SFTP reads keep 16 requests in
flight.

An SMB share that isn't there comes with the shares the server does have: Browse offers them in
place of the missing one, and saves the one tapped as the drawer's share, opening it at its top,
since a folder in the old share may not be in the new one; the server form puts it in the Share
field. A share the server does list is there, but won't open: Samba says a share isn't there when
it can't open the share's folder, as when its disk isn't mounted. Tansu says so, with Try again,
rather than offering the same share. smbj can't list shares, so `files/Srvsvc.kt` writes the one
call needed by hand, as plain bytes: MS-SRVS NetrShareEnum at level 1, in DCE/RPC over the srvsvc
pipe on IPC$. `SmbFileSystem.shares()` signs in with an `SMBClient` of its own, since a client
lends its open connection to everyone who connects to the same server and hanging up would end the
drawer's too. It binds to the pipe, asks, reads the answer to its last fragment, and hangs up. An
answer over 1 MiB, or still coming after 30 seconds, fails: no list of shares comes near either,
so the server is broken, or means harm. Only disk shares are offered, not IPC$, printers or
special shares like C$. When the server can't say, the message stands alone, with Edit server.

BouncyCastle is registered in full at start-up, in place of Android's cut-down copy, because
sshj's key exchanges need it; smbj uses it for NTLM.

## Jobs

- A job (`jobs/Job.kt`) is an operation (copy, move or delete), what was picked, where it came from
  and where it goes. `Jobs` is the queue: one job at a time, in order, each able to pause, resume,
  cancel and retry. `JobRun` carries one out.
- While anything is queued or running, `TransferService` (a data sync foreground service) keeps
  tansu alive and shows the running job in a notification, with Pause and Cancel. It stops when
  the queue is empty. Unless tansu is in front saying so itself, the last job's ending stays in the
  shade; tapping it opens the folder its files went to, through `ShowFolder`, an activity alias
  that isn't exported, so no other app can steer tansu that way. Opened again from Recents, that
  way in is an old request, and ignored. User-initiated data transfer jobs were the first plan, but Android makes them
  wait for a network, which a copy on the phone alone doesn't need.
- A copy streams 256 KB chunks from one `FileSystem` to another, reporting progress as it goes. A
  move within a drawer is a rename; across drawers it copies, checks the size, then deletes the
  original. Folders are walked first, so the job knows how much there is. A file whose copy breaks off,
  as when Tailscale moves from Wi-Fi to mobile data, carries on from where it was rather than
  starting again, whether the job tried again by itself or was paused and resumed: its hidden
  half stays, and the job writes on from its end, less the last MiB, which a broken connection
  may have left with gaps (SFTP keeps about 16 writes of 32 KiB in flight). It does so only for
  the file the half is of (two paths can share a hidden name's short hash), and only while the
  source still has the size and date a fresh look gave when the half was begun, before it's
  carried on with and again after; otherwise the file starts again. Afterwards the half must hold
  exactly what was written, so a source that shrank meanwhile fails the file rather than leaving
  a stale tail. Files of 1 MiB or less, and files shared from other apps, which may not say when
  they changed, always start again, as does a file on a server that won't write part-way into
  one. A job has one hidden half at a time: another file's, left when it stopped, goes as soon as
  a different file begins, and one never reached, say because its file is gone, goes when the run
  ends.
- Name clashes are settled before the job starts (Keep both, Replace or Skip, per name), telling
  names apart without case, as the phone and SMB do. A job remembers where each pick went and
  which files are done, so a paused, resumed or retried job carries on without asking again or
  doing anything twice.
- Nothing is written over by accident. Every file is written under a short hidden name of the
  job's own (`.tansu-<token>-<hash>`: the token random per job, the hash of the source's path, so
  the name stays the same wherever the file ends up) beside where it goes, checked against the
  source's size (unless another app shared it, which may not say), and only then takes its name: with a rename that fails if the name
  is taken, so a clash the listing missed (or a file that arrived while the job was paused) keeps
  both, or, when the user chose Replace, with a rename that replaces the old file in one step:
  only the file as it was when the clash was settled, by its size and date, so one saved again
  since, as a job resumed later may find, is kept beside the copy. A half-written file is never
  visible under a real name, and it's the only thing Cancel removes.
- The job remembers every file it named, and never replaces one of them; nor does a move replace
  the very file it's moving, reached another way (a link, or a second drawer on the same disk):
  that shows as the hidden file appearing beside the original too. Two folders whose names differ
  only in case stay apart, both remembered. A rename whose answer was lost is recognised on the
  retry by its hidden file being gone, or by the file being where it was going, so it isn't made
  twice; for the same reason a dropped connection never retries a rename by itself.
- A folder is done once everything in it is, so a resumed or retried job passes it over.
- A move remembers each copy it has made until it has checked it and removed the original, so a
  move that stops in between checks that copy rather than making another. The original goes only
  as it was when copied: one that has changed since, or that turns out to be the copy itself,
  stays, and so does the copy. The job keeps what the original said when it was copied, so this
  holds across a pause. A copy that no longer matches, as one edited on the server meanwhile
  wouldn't, is kept too: a move deletes nothing on a guess.
- A job that stops as a file is being named remembers which file it was; resumed, it settles that
  file before any other, so another file can't take its place and the file is never copied twice.
  A hidden file stops being named before it's removed, so a stop as it goes can't pass for a
  naming, and a look the server can't answer counts as not named: copied again, never lost.
  A rename whose answer was lost is done if the file is gone from where it was and is where it
  was going, whether the server's second go says Exists (SFTP) or NotFound.
- A moved folder is done once it's gone from where it was; one that couldn't be removed, or that
  the server can't yet say is gone, is tried again when the job resumes. A folder gone when the
  job resumes counts as moved only if the job had begun removing it, which it remembers first.
- Whatever goes wrong with one file or folder is put against it, and the job and the queue go on.
  A full disk or a refusal fails the file at once; a server that stops answering is retried for
  at least 30 seconds from when it stopped, or from when bytes last went through, so a link that
  drops now and then keeps going, then the job pauses and says which server it lost. Cancelling, even a paused job,
  deletes the file half written; finished files stay.
- Links on SFTP are never gone through to delete or move what they point to. A picked link is
  followed for what's in it, and a move removes only the link; links inside a folder are left.
- Writes to SFTP go in 32 KiB pieces: OpenSSH refuses any message over 256 KiB. OpenSSH speaks
  SFTP 3, which can't say "already there" or "disk full": a folder or file that fails is looked
  up to tell the two apart, and a write that fails isn't waited out.
- Saving shared files can't be retried: the other app only lends them while it shares them.
- A job knows the files in each folder picked for it, as its run found them, so its detail can
  open a folder to show them, each with where it stands; one moved within a drawer goes whole.
- Progress is a `StateFlow` read by the Browse card, the Transfers tab and the notification (which
  updates at most once a second). While Browse is on screen, it lists the folder on show again as
  a running job puts files in it or takes them away (a copy doesn't change where it copies from),
  at most every 2 seconds, so pasted files show up as they land; such a listing that fails keeps
  what's shown. A job's end lists the folder again even if a listing was already on its way.
- Jobs survive Android ending tansu. `Jobs` keeps a log for each job still to finish
  (`files/jobs/<id>.log`, through `FileJournal`): the job as it stood when it last started, as
  JSON, then a line for each change since: what's done, which files it named, where each pick
  went, the move's copies and the hidden file under way. Every change to a drawer goes through a
  wrapper that writes down what changed before the step, so a log never knows less than the
  drawers show: ending there is the same as a pause just before that step, or just after it with
  its answer lost, which the job already settles. A line cut short as the process ended is left
  out; a log spoiled anywhere else, or of another version (its first line says which), is
  dropped rather than bringing back a job that knows less than it did. When tansu starts, each
  logged job comes back paused for the user to resume, counted on the Transfers tab. Some come
  back cancelled, without their hidden half, as they can't safely go on: a share-in, whose other
  app's files are gone; a delete, which would list its folders afresh and could take what's
  arrived since; and one the user cancelled before its half was cleared away, which a cancel
  marks first and then clears, keeping the log until the half is gone. A job's log goes when it
  ends, and is written afresh when it starts or pauses and every 500 writes, so it stays quick to
  read back. A test ends the process at every step of a copy, a move with clashes, a move within
  a drawer and case twins, then brings the job back and checks every drawer ends as a straight
  run left it; and that a delete, a share-in or a copy changed meanwhile comes back safely.

## Sharing in

`ShareActivity` takes `ACTION_SEND` and `ACTION_SEND_MULTIPLE` for any type and shows Save to: a
drawer and folder picker that starts where the last share was saved. Save here starts a copy job
whose source is `SharedFiles`, a read-only `FileSystem` over the shared content URIs.

Android lets an app read shared URIs only while the activity they were shared with is alive. So
the activity runs in a task of its own (a document, out of Recents), steps back with
`moveTaskToBack` once the job starts, which returns the user to the app they shared from, and
finishes when the job ends.

## Opening files

Pictures go through Coil and videos through Media3, both reading straight from the `FileSystem`, so
server files stream without downloading first: Coil through `DrawerImage` and its fetcher, Media3
through `DrawerDataSource`, which opens each read at the position Media3 asks for, so seeking skips
what it passes. PDFs open with Android's `PdfRenderer`, from the cache for server files. Other files
open in another app through a `FileProvider` URI, after server files are fetched to the cache.

The viewers are screens of their own (`ui/viewer`), pushed on the place's history like any task.

Grid tiles show thumbnails through the same Coil loader, with a fetcher of their own
(`DrawerThumbnail`); the viewer still loads the whole picture. A JPEG's thumbnail is the one the
camera put in its EXIF data, if it's big enough for a tile (a shorter side of 288 pixels or more;
Samsung's are 512 × 384) and has the photo's shape. Only the start of the file is needed, up to
the frame header that gives the photo's size and never past 256 KiB. It's read 8 KiB at a time,
since SFTP reads ahead by the size of each read; SMB still brings its first 256 KiB down in one go,
so a 10 MB photo costs a few hundred kilobytes at most. AndroidX's `ExifInterface` (which Coil
brings anyway) finds the thumbnail in it. The thumbnail's size comes from its header before it's
decoded, and one over 2048 pixels is passed over, so a broken or booby-trapped photo can't make a
bitmap too big to draw. Any other picture is read whole and decoded straight to a shorter side of
384 pixels by `ImageDecoder` (HEIC, PNG and WebP too), and a longer side of 4096 at most, as Coil
makes its own, so a long screenshot can still be drawn: on the phone from the file itself, for a
server's from a copy in the cache, deleted once it's decoded. A server's picture Android can't read
isn't brought down again until tansu restarts. A server's thumbnails are kept as small JPEGs, or
WebPs if they can be seen through, in Coil's disk cache (`thumbnails` in the cache folder, 256 MiB)
by drawer, path, size and date, so a folder seen once shows at once after, even after tansu
restarts; one the phone has no room for is shown all the same. The phone's aren't kept: they're
about as quick to make again.
Each drawer makes four at a time, since every read from an SMB server shares its one connection; a
tile scrolled away before its turn reads nothing, and one cancelled while its picture comes down
closes the stream and keeps nothing.

## Updates

Each time tansu comes to the front, if a day has gone by since the last check and the check is
on, `updates/` reads GitHub's latest release and compares its version with the installed one. The
request names only tansu in its User-Agent. A newer version shows in Settings with the changes from
its release notes, and a dot marks the Settings tab.

A version found is kept in settings, so it's still on offer after tansu closes without asking
GitHub again. Download and install fetches `tansu.apk` into the cache with its progress and
Cancel, checks it against the release's `tansu.apk.sha256`, and hands it to Android's own
installer through the `FileProvider`. That needs `REQUEST_INSTALL_PACKAGES`. The first time, Android asks the user to let
tansu install apps and comes back to tansu, where Install offers the download again. Android checks
the update is signed with tansu's key. The download isn't a job: it's one file that Settings owns,
and the next check clears it away. See [releasing.md](releasing.md).

## Servers and secrets

Server details (name, kind, host, port, share or folder, username) are kept in DataStore.
Passwords and private keys are encrypted with AES-GCM under a non-exportable Android Keystore key,
decrypted only to connect, never logged, and excluded from backups and device transfer. SFTP host
keys are pinned per server.

Adding a server checks it first: a throwaway connection lists the starting folder with the details
as typed, and only then is anything saved. An SFTP server seen for the first time is refused with
its key, which the user checks and trusts; the check then runs again with the key pinned. Editing
keeps the pinned key unless the host or port changes.

Android 17 keeps apps off the local network until the user allows it (`ACCESS_LOCAL_NETWORK`, a
runtime permission). Tansu asks when a server is first connected, and Browse explains it if a
server can't be reached without it. Servers reached through Tailscale don't need it.
