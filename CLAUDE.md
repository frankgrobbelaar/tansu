# CLAUDE.md

## Project

Tansu (箪笥) is a personal Android file manager. Access to a home NAS is a first-class feature, both on the home network and remotely via Tailscale. Tailscale runs as the system VPN through its own app, so tansu doesn't integrate with it; it just connects to the NAS by its tailnet name or IP.

It exists because the file managers on the Play Store are poorly written, dated-looking, paid or full of ads. Tansu should be the opposite:

- Clean, modern UI.
- Solid, well-structured code.
- No ads, tracking, analytics or paywalls.

## Repository

- Public and MIT-licensed. Doesn't accept contributions, but forks are welcome.
- Work directly on `main`. No feature branches or PRs, apart from the ones Dependabot opens.
- LF line endings everywhere except Windows scripts (`*.bat`, `*.cmd`), enforced by `.gitattributes`. Editor settings are in `.editorconfig`.

## Tech stack

- Native Android: Kotlin + Jetpack Compose. Minimum SDK 34; the main device is a Galaxy S24.
- Storage backends, all behind one file-system interface: phone storage, SMB (smbj) and SFTP (sshj). NFS is deliberately out of scope.
- Build, test and lint commands go here once the project is scaffolded.

## Code style

Lean, simple, clean-to-read code comes first. Linting and formatting are strict: zero warnings, no baselines.

- Formatting: ktfmt (kotlinlang style) via Spotless, which also covers `.gradle.kts`, XML, YAML and Markdown.
- Static analysis: detekt with zero issues allowed, plus the compose-rules plugin.
- Android Lint and the Kotlin compiler treat warnings as errors.
- CI fails on any finding, and a pre-commit hook runs Spotless and detekt.

## Principles

### Android (Kotlin + Compose)

1. Unidirectional data flow: one immutable `UiState` per screen, exposed as a `StateFlow`. Composables are stateless and take state and callbacks as parameters.
2. Clear layers: UI → ViewModel → repository → data source, with one owner per piece of data. The UI never touches smbj, sshj or `java.nio` directly.
3. Main-safe structured concurrency: suspend functions move themselves to `Dispatchers.IO`. Work is scoped and cancellable; no `GlobalScope`.
4. Design for flaky networks: timeouts on every remote call, reconnects on dropped sessions, typed errors the UI can explain.
5. Respect the lifecycle: `collectAsStateWithLifecycle()`, survive process death, and run long transfers as user-initiated jobs or a foreground service, never in a ViewModel.
6. Fast lists: immutable UI models, stable lazy-list keys, no sorting or filtering during composition. Judge performance on release builds.
7. Secure by default: credentials encrypted with an Android Keystore key and excluded from backups, SFTP host keys saved on first connect and checked after, no secrets in logs, minimal permissions.
8. One design system: Material 3 theme tokens, dynamic colour, dark theme, edge-to-edge, predictive back, accessible touch targets and content descriptions.
9. Test at the right level: unit tests against a fake file system, backend tests against real Samba and OpenSSH servers, a few Compose UI tests for key flows. CI runs it all.
10. Simple until it hurts: one module, manual dependency injection, Jetpack first. Every extra library has to earn its place.

### General

1. Keep it simple.
2. You aren't gonna need it: build for today's requirements.
3. Don't repeat yourself, but wait until something repeats three times before abstracting it.
4. High cohesion, low coupling.
5. Make illegal states unrepresentable.
6. Fail fast and loudly; never swallow exceptions.
7. Write for the reader: clear names, small functions, comments that explain why.
8. Make it work, then right, then fast. Optimise only what measurements show is slow.
9. Small, safe steps: every commit builds and passes the tests.
10. Leave it better than you found it.
