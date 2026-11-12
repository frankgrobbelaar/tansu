# Releasing

Tansu is never on a store. It's sideloaded: the owner shares the APK with people they know, and
the app updates itself from GitHub Releases.

## Versions

- `versionName` is semantic: `1.2.0`.
- `versionCode` is derived from it (`major × 10000 + minor × 100 + patch`), so it always rises.
  Android refuses an update whose code isn't higher than the installed one.
- Every change someone would notice gets one plain line under "Unreleased" in `CHANGELOG.md`, in
  the same commit. A release turns that section into its notes.

## The signing key

Android installs an update only if it's signed with the same key as the app already installed.

- One release keystore, made once, kept outside the repo, with a backup somewhere safe.
- CI gets it as repository secrets: the keystore (base64), its password, the key alias and the key
  password.
- **Lose it and every installed copy has to be uninstalled before a new one goes on**, losing its
  saved servers. Treat it like a house key.

## Cutting a release

1. Move "Unreleased" in `CHANGELOG.md` under the new version, and push a tag such as `v1.2.0`.
2. The release workflow builds the signed release APK (minified with R8) and publishes a GitHub
   Release with the notes, `tansu.apk` and `tansu.apk.sha256`.
3. The asset keeps the same name in every release, so one link always fetches the newest:
   `https://github.com/frankgrobbelaar/tansu/releases/latest/download/tansu.apk`.

## First install

For the people you share it with:

1. Open the link on the phone and download `tansu.apk`.
2. Android asks to allow the browser to install apps. Allow it once.
3. On Samsung phones, Auto Blocker has to be off while installing. Play Protect may warn about an
   app it doesn't know; choose to install anyway.
4. Open tansu and give it All files access.

## Updates

- When tansu opens, at most once a day, it asks GitHub for the latest release. That request carries
  nothing about the user, and the check can be turned off in Settings › About.
- A newer version shows in Settings with its notes. Download and install runs as a job, checks the
  file against `tansu.apk.sha256`, then hands it to Android's installer.
- The first time, Android asks the user to let tansu install apps. Android then checks the update
  is signed with the same key and keeps every setting and saved server.

## Android developer verification

Google announced that, from 2026, apps installed on certified Android phones must come from a
registered developer: first in a few countries, then everywhere in 2027. A free limited-distribution
account covers sharing with up to 20 devices without ID or a fee, and installs over ADB keep
working. Check the current rules and register before they apply where tansu's users live.
