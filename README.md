<div align="center">

# tansu

**箪笥 · a calm file manager for your phone and your own servers**

A tansu is a Japanese chest of drawers. This one holds your phone's files and the servers you keep
them on, and lets you move them between the two without fuss.

[**Download the latest APK**](https://frankgrobbelaar.github.io/tansu/) ·
[What's new](CHANGELOG.md) · [How it's built](docs/README.md)

[![CI](https://github.com/frankgrobbelaar/tansu/actions/workflows/ci.yml/badge.svg)](https://github.com/frankgrobbelaar/tansu/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/frankgrobbelaar/tansu?label=release&color=8A5A2B)](https://github.com/frankgrobbelaar/tansu/releases/latest)
[![Android 14 or newer](https://img.shields.io/badge/Android-14%2B-5E6B4E)](https://frankgrobbelaar.github.io/tansu/)
[![MIT license](https://img.shields.io/badge/license-MIT-A89A8A)](LICENSE)

<img src="docs/screenshots/browse.png" width="23%" alt="A NAS drawer: Backups, Documents, Films, Music and Photos">
<img src="docs/screenshots/photos.png" width="23%" alt="The NAS's Photos folder as a grid of pictures">
<img src="docs/screenshots/transfer.png" width="23%" alt="A copy to the NAS under way: a quarter done, a minute left">
<img src="docs/screenshots/save-to.png" width="23%" alt="Save to, picking a folder on the NAS for a picture shared from another app">

</div>

## What it does

- Browses your phone's storage from the start.
- Adds your own SMB and SFTP servers, at home or over Tailscale. Each becomes a drawer you browse
  just like the phone.
- Cuts, copies, pastes, renames, deletes and shares files, on the phone and on servers alike.
- Takes any number of files shared from Gallery, My Files or any other app, and saves them to any
  drawer.
- Shows every job as it runs: what it's doing, how far it got and how long is left. Jobs keep going
  when you leave the app, and a copy that breaks off carries on from where it was.
- Opens pictures, videos and PDFs itself, streaming them from servers; anything else opens in
  another app.
- Updates itself from GitHub, checking once a day unless you turn that off.

<p align="center">
  <img src="docs/screenshots/notification.png" width="60%" alt="tansu's notification: copying 1 file to NAS, 48 MB of 168 MB, 2 minutes left, with Pause and Cancel">
</p>

## Light or dark

Kiri, tansu's look, is sand and paper by day and warm near-black by night, with a wood-brown accent
and sage beside it. It follows the phone, or you can pick one, or use your wallpaper's colours.

<p align="center">
  <img src="docs/screenshots/dark-welcome.png" width="23%" alt="The first run in dark: your phone and your servers, in one chest">
  <img src="docs/screenshots/dark-browse.png" width="23%" alt="The NAS drawer in dark">
  <img src="docs/screenshots/dark-photos.png" width="23%" alt="The NAS's Photos folder as a grid, in dark">
  <img src="docs/screenshots/viewer.png" width="23%" alt="A picture from the NAS, open full screen">
</p>

## What it doesn't do

No sign-in, accounts, ads, tracking or automatic backups. Server passwords stay on your phone,
encrypted. Besides your own servers, tansu only talks to GitHub, to look for a new version.

## Getting it

Tansu isn't on any store, on purpose. The
[downloads page](https://frankgrobbelaar.github.io/tansu/) always has the newest APK and explains
installing it the first time. Tansu needs Android 14 or newer.

## Status

Being built in iterations, tracked in [docs/plan.md](docs/plan.md); what changed in each version
is in [CHANGELOG.md](CHANGELOG.md). The [spec](docs/spec.md), the
[architecture](docs/architecture.md) and the [design](docs/design.md) are in
[docs/](docs/README.md). The screenshots are from the emulator, with made-up pictures.

## License

[MIT](LICENSE). Tansu is a personal project and doesn't accept contributions, but you're welcome to
fork it.
