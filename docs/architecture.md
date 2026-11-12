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
| `App.kt` | The application and its dependency container, wired by hand |

## Drawers

Every drawer is a `FileSystem`: list, stat, read from an offset, write, create a folder, rename and
delete. Screens and jobs only ever see this interface.

| Drawer | Built on | Notes |
| --- | --- | --- |
| Phone | `java.nio`, with All files access | The phone's shared storage |
| SMB | smbj | One connection and session per server, reused; SMB 2 and 3 |
| SFTP | sshj | One SSH connection per server, reused; host key checked against the pinned one |

A connection opens on first use, is shared by browsing and jobs, and reconnects after a drop.
Library errors become a few typed errors (`Unreachable`, `AuthFailed`, `NotFound`, `AccessDenied`,
`NoSpace`, `Exists`, `Cancelled`), which the UI puts into words.

## Jobs

- A job is an operation (copy, move or delete), its sources and its destination. Queued and paused
  jobs are saved, so they survive the app being closed.
- Jobs run one at a time as user-initiated data transfer jobs (Android 14+), each with its
  notification.
- A copy streams in chunks from one `FileSystem` to another, reporting progress as it goes. A move
  within a drawer is a rename; across drawers it copies, checks the size, then deletes.
- Progress is a `StateFlow` read by the Browse card, the Transfers tab and the notification (which
  updates at most once a second).

## Sharing in

An activity takes `ACTION_SEND` and `ACTION_SEND_MULTIPLE` for any type. It shows the destination
picker, hands the shared URIs and their read grants to a copy job, and finishes, which returns the
user to the app they came from.

## Opening files

Pictures go through Coil and videos through Media3, both reading straight from the `FileSystem`, so
server files stream without downloading first. PDFs open with Android's `PdfRenderer`, from the
cache for server files. Other files open in another app through a `FileProvider` URI, after server
files are fetched to the cache.

## Updates

When the app opens, at most once a day, `updates/` reads GitHub's latest release and compares its
version with the installed one. Downloading the new `tansu.apk` is a job like any other; the file is
checked against the release's SHA-256, then handed to Android's `PackageInstaller`. That needs the
`REQUEST_INSTALL_PACKAGES` permission, and Android asks the user once to allow tansu to install
apps. See [releasing.md](releasing.md).

## Servers and secrets

Server details (name, kind, host, port, share or folder, username) are kept in DataStore.
Passwords and private keys are encrypted with AES-GCM under a non-exportable Android Keystore key,
decrypted only to connect, never logged, and excluded from backups and device transfer. SFTP host
keys are pinned per server.
