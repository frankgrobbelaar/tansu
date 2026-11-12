# Development

## Setting up a PC

- Install Android Studio. Its bundled JDK 21 runs Gradle, and Gradle fetches everything else,
  including the Android SDK platform it needs.
- Open the repo in Android Studio, or use the commands below from the repo's root.
- Turn on the pre-commit hook once per clone: `git config core.hooksPath .githooks`. It runs
  formatting and detekt before each commit, since `main` has no pull requests to catch them.

## Commands

| To | Run |
| --- | --- |
| Run every check CI runs, and build the debug app | `./gradlew check assembleDebug` |
| Fix formatting | `./gradlew spotlessApply` |
| Run the unit tests | `./gradlew testDebugUnitTest` |
| Install on a phone or the emulator | `./gradlew installDebug` |

## The checks

| Check | What it does | Where it's set |
| --- | --- | --- |
| Spotless | ktfmt (kotlinlang style) for Kotlin and Gradle scripts; trailing whitespace and final newlines elsewhere | `build.gradle.kts` |
| detekt | Code smells and complexity, plus compose-rules for Compose. Any issue fails the build | `config/detekt/detekt.yml` |
| Android Lint | Warnings are errors. Checks for newer library versions are off: Dependabot handles those | `app/build.gradle.kts` |
| Kotlin | Compiler warnings are errors | `app/build.gradle.kts` |
| Unit tests | JUnit 4, Robolectric (Android 16, the newest it runs) and Compose UI tests | `app/src/test` |

detekt is on a 2.0 alpha because compose-rules needs it and the 1.x line predates Kotlin 2.4. Move
to 2.0 stable when it ships.

## The emulator

The `Pixel_8` AVD runs Android 17, at the Galaxy S24's size class. To run it without a window, as
the checks on the design boards do:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Pixel_8 -no-window -no-audio
```

`adb exec-out screencap -p > screen.png` takes a screenshot to compare with the boards.

## Versions

Dependabot opens one grouped pull request a week for Gradle libraries and one for GitHub Actions.
Each must pass CI before it's merged. The versions themselves live in
[gradle/libs.versions.toml](../gradle/libs.versions.toml).
