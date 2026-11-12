# Releasing

Tansu is never on a store. It's sideloaded: the owner shares the APK with people they know, and
the app updates itself from GitHub Releases.

## Versions

- `versionName` is semantic: `1.2.0`. It lives in `gradle.properties` as `tansu.version`.
- `versionCode` is derived from it (`major × 10000 + minor × 100 + patch`), so it always rises.
  Android refuses an update whose code isn't higher than the installed one.
- Every change someone would notice gets one plain line under "Unreleased" in `CHANGELOG.md`, in
  the same commit. A release turns that section into its notes.

## The signing key

Android installs an update only if it's signed with the same key as the app already installed.

- One release keystore, made once (12 Nov 2026), kept outside the repo, with a backup somewhere
  safe. On the owner's PC it's in `%USERPROFILE%\tansu-release-key\`, with a note on keeping it.
- CI gets it as repository secrets: `TANSU_KEYSTORE_BASE64`, `TANSU_KEYSTORE_PASSWORD` and
  `TANSU_KEY_PASSWORD`. The key's alias, `tansu`, isn't secret and is set in the workflow. `app/build.gradle.kts` signs a release build only
  when `TANSU_KEYSTORE` (the keystore's path) and those passwords are set.
- The certificate's SHA-256 is in `.github/release-certificate.sha256`. The release workflow
  refuses to publish an APK signed by anything else.
- **Lose it and every installed copy has to be uninstalled before a new one goes on**, losing its
  saved servers. Treat it like a house key.

## Branches

- `main` is where the work happens. Every push runs CI.
- `release` is what tansu's users get, and the only source of updates. Moving it forward publishes
  a version; nothing is committed to it directly.

## Cutting a release

1. On `main`, raise the version and move "Unreleased" in `CHANGELOG.md` under it.
2. Fast-forward `release` to that commit: `git push origin main:release`.
3. The release workflow (`.github/workflows/release.yml`) checks the version is newer than every
   release so far, writes the notes from `CHANGELOG.md` (`scripts/release-notes.mjs`), runs every
   check, builds the signed APK (minified with R8), checks its signature, tags the commit
   (`v1.2.0`), and publishes a GitHub Release with the notes, `tansu.apk` and `tansu.apk.sha256`.
   Then it refreshes the downloads page.
4. The asset keeps the same name in every release, so one link always fetches the newest:
   `https://github.com/frankgrobbelaar/tansu/releases/latest/download/tansu.apk`.

## The downloads page

[frankgrobbelaar.github.io/tansu](https://frankgrobbelaar.github.io/tansu/) is the one link to send
people. It's drawn in Kiri and says, plainly:

- what tansu is, with a Download button for the newest `tansu.apk`, its version and size;
- that tansu isn't on the Play Store or any other store, on purpose: it's a personal app shared
  with people the owner knows, so Android asks before installing it;
- how to install it the first time (the steps below), and that updates then arrive in the app;
- that it's open source, signed by the owner, and has no sign-in, ads or tracking;
- what's new in each version, newest first, and that it needs Android 14 or newer.

As with Kura and Maku, `scripts/downloads-page.mjs` builds it from the GitHub releases and
`.github/workflows/downloads.yml` publishes it on GitHub Pages after every release, and by hand.

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
  file against `tansu.apk.sha256`, then hands it to Android's installer. Until that lands in the
  app, an update is installed from the downloads page over the version already there.
- The first time, Android asks the user to let tansu install apps. Android then checks the update
  is signed with the same key and keeps every setting and saved server.

## Android developer verification

Google announced that, from 2026, apps installed on certified Android phones must come from a
registered developer: first in a few countries, then everywhere in 2027. A free limited-distribution
account covers sharing with up to 20 devices without ID or a fee, and installs over ADB keep
working. Check the current rules and register before they apply where tansu's users live.
