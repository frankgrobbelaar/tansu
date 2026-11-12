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

Every call has a 15-second timeout. BouncyCastle is registered in full at start-up, in place of
Android's cut-down copy, because sshj's key exchanges need it; smbj uses it for NTLM.

## Jobs

- A job (`jobs/Job.kt`) is an operation (copy, move or delete), what was picked, where it came from
  and where it goes. `Jobs` is the queue: one job at a time, in order, each able to pause, resume,
  cancel and retry. `JobRun` carries one out.
- While anything is queued or running, `TransferService` (a data sync foreground service) keeps
  tansu alive and shows the running job in a notification, with Pause and Cancel. It stops when
  the queue is empty. User-initiated data transfer jobs were the first plan, but Android makes them
  wait for a network, which a copy on the phone alone doesn't need.
- A copy streams 256 KB chunks from one `FileSystem` to another, reporting progress as it goes. A
  move within a drawer is a rename; across drawers it copies, checks the size, then deletes the
  original. Folders are walked first, so the job knows how much there is.
- Name clashes are settled before the job starts (Keep both, Replace or Skip, per name), telling
  names apart without case, as the phone and SMB do. A job remembers where each pick went and
  which files are done, so a paused, resumed or retried job carries on without asking again or
  doing anything twice.
- Nothing is written over by accident: `FileSystem.write` makes a new file and fails if the name
  is taken, so a clash the listing missed keeps both. Replace writes the new copy beside the
  original under a hidden name and swaps it in only once it's whole.
- Whatever goes wrong with one file or folder is put against it, and the job and the queue go on.
  A full disk or a refusal fails the file at once; a server that stops answering is retried for
  30 seconds, then the job pauses and says which server it lost. Cancelling, even a paused job,
  deletes the file half written; finished files stay.
- Links on SFTP are never gone through to delete or move what they point to. A picked link is
  followed for what's in it, and a move removes only the link; links inside a folder are left.
- Writes to SFTP go in 32 KiB pieces: OpenSSH refuses any message over 256 KiB.
- Saving shared files can't be retried: the other app only lends them while it shares them.
- Progress is a `StateFlow` read by the Browse card, the Transfers tab and the notification (which
  updates at most once a second).

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
Grid tiles show pictures' thumbnails through the same Coil loader.

## Updates

Each time tansu comes to the front, if a day has gone by since the last check and the check is
on, `updates/` reads GitHub's latest release and compares its version with the installed one. The
request names only tansu in its User-Agent. A newer version shows in Settings with the changes from
its release notes, and a dot marks the Settings tab.

Download and install fetches `tansu.apk` into the cache with its progress and Cancel, checks it
against the release's `tansu.apk.sha256`, and hands it to Android's installer through the
`FileProvider`. That needs `REQUEST_INSTALL_PACKAGES`. The first time, Android asks the user to let
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
