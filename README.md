# tansu

Tansu (箪笥): a personal Android file manager

A tansu is a Japanese chest of drawers. This one holds your phone's files and the servers you keep
them on, and lets you move them between the two without fuss.

## What it does

- Browses your phone's storage from the start.
- Adds your own SMB and SFTP servers, at home or over Tailscale. Each becomes a drawer you browse
  just like the phone.
- Cuts, copies, pastes, renames, deletes and shares files, on the phone and on servers alike.
- Takes any number of files shared from Gallery, My Files or any other app, and saves them to any
  drawer.
- Shows every job as it runs: what it's doing, how far it got and how long is left. Jobs keep going
  when you leave the app.
- Opens pictures, videos and PDFs itself, streaming them from servers; anything else opens in
  another app.
- Updates itself from GitHub, checking once a day unless you turn that off.

## What it doesn't do

No sign-in, accounts, ads, tracking or automatic backups. Server passwords stay on your phone,
encrypted.

## Getting it

Tansu isn't on any store, on purpose. The
[downloads page](https://frankgrobbelaar.github.io/tansu/) always has the newest APK and explains
installing it the first time. Tansu needs Android 14 or newer.

## Status

Being built in iterations, tracked in [docs/plan.md](docs/plan.md); what changed in each version
is in [CHANGELOG.md](CHANGELOG.md). The [spec](docs/spec.md), the
[architecture](docs/architecture.md) and the [design](docs/design.md) are in
[docs/](docs/README.md).

## License

[MIT](LICENSE). Tansu is a personal project and doesn't accept contributions, but you're welcome to fork it.
