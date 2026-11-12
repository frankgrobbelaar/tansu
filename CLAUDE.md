# CLAUDE.md

## Project

Tansu (箪笥) is a personal Android file manager. Access to a home NAS over SMB is a first-class feature, both on the home network and remotely via Tailscale. Tailscale runs as the system VPN through its own app, so tansu doesn't integrate with it; it just connects to the NAS by its tailnet name or IP.

It exists because the file managers on the Play Store are poorly written, dated-looking, paid or full of ads. Tansu should be the opposite:

- Clean, modern UI.
- Solid, well-structured code.
- No ads, tracking, analytics or paywalls.

## Repository

- Public and MIT-licensed. Doesn't accept contributions, but forks are welcome.
- Work directly on `main`. No feature branches or PRs, apart from the ones Dependabot opens.
- LF line endings everywhere except Windows scripts (`*.bat`, `*.cmd`), enforced by `.gitattributes`. Editor settings are in `.editorconfig`.

## Tech stack

Not decided yet. Add the stack and the build, test and lint commands here once it's chosen.
