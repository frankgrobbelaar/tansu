// Builds tansu's downloads page: the newest APK with how to install it, and every version's notes.
// The downloads workflow publishes it on GitHub Pages after each release.
//
//   node scripts/downloads-page.mjs <folder>                     # reads the releases from GitHub
//   node scripts/downloads-page.mjs <folder> --releases <file>   # from a saved list, to preview
//
// See docs/releasing.md.
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const REPOSITORY = "frankgrobbelaar/tansu";

// Tansu's mark: a chest of three drawers on its wood tile, as app/src/main/res/drawable/mark.xml.
const MARK = `<svg viewBox="0 0 48 48" width="40" height="40" aria-hidden="true">
  <rect width="48" height="48" rx="14" fill="#8a5a2b"/>
  <rect x="12" y="11" width="24" height="26" rx="4" fill="none" stroke="#fbf7f1" stroke-width="2.4"/>
  <path d="M12 19.7h24M12 28.3h24" stroke="#fbf7f1" stroke-width="2.4"/>
  <path d="M22 15.4h4M22 24h4M22 32.6h4" stroke="#fbf7f1" stroke-width="2.4" stroke-linecap="round"/>
</svg>`;

export function escapeHtml(text) {
  return String(text)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function inline(text) {
  return escapeHtml(text)
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2">$1</a>')
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`]+)`/g, "<code>$1</code>");
}

/** The little Markdown the changelog uses: paragraphs and lists whose items wrap. */
export function markdown(text) {
  const html = [];
  let list = null;
  let paragraph = [];
  const close = () => {
    if (paragraph.length) html.push(`<p>${inline(paragraph.join(" "))}</p>`);
    if (list) html.push(`<ul>${list.map((item) => `<li>${inline(item)}</li>`).join("")}</ul>`);
    paragraph = [];
    list = null;
  };
  for (const raw of String(text).replaceAll("\r\n", "\n").split("\n")) {
    const line = raw.trim();
    const item = /^[-*] (.+)$/.exec(line);
    if (!line) {
      close();
    } else if (item) {
      if (paragraph.length) close();
      list ??= [];
      list.push(item[1]);
    } else if (list && /^\s/.test(raw)) {
      list[list.length - 1] += ` ${line}`;
    } else {
      if (list) close();
      paragraph.push(line);
    }
  }
  close();
  return html.join("\n");
}

/** [major, minor, patch], or null when the text isn't a version like 1.2.3. */
export function parseVersion(text) {
  const match = /^(\d+)\.(\d+)\.(\d+)$/.exec(String(text));
  return match ? match.slice(1, 4).map(Number) : null;
}

function compareVersions(a, b) {
  const [x, y] = [parseVersion(a), parseVersion(b)];
  for (let part = 0; part < 3; part++) if (x[part] !== y[part]) return x[part] - y[part];
  return 0;
}

function day(iso) {
  const options = { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" };
  return new Date(iso).toLocaleDateString("en-GB", options);
}

/** Published versions, newest first, each with its APK and checksum file. */
export function versions(releases) {
  return releases
    .filter((release) => !release.draft && !release.prerelease)
    .map((release) => ({ release, version: String(release.tag_name).replace(/^v/, "") }))
    .filter(({ version }) => parseVersion(version))
    .sort((a, b) => compareVersions(b.version, a.version))
    .map(({ release, version }) => ({
      version,
      date: day(release.published_at),
      notes: release.body ?? "",
      page: release.html_url,
      apk: release.assets?.find((asset) => asset.name === "tansu.apk") ?? null,
    }));
}

function size(bytes) {
  return `${(bytes / 1e6).toFixed(1)} MB`;
}

function checksum(apk) {
  return apk?.digest?.startsWith("sha256:") ? apk.digest.slice(7) : null;
}

function hero(latest, repository) {
  if (!latest?.apk) {
    return `<section class="hero" aria-labelledby="latest">
  <p class="eyebrow">Downloads</p>
  <h1 id="latest">Tansu is on its way</h1>
  <p class="lede">No version is out yet. The first one will be here, ready to download, with how to install it.</p>
  <div class="actions"><a class="button" href="${repository}">Tansu on GitHub</a></div>
</section>`;
  }
  const sum = checksum(latest.apk);
  return `<section class="hero" aria-labelledby="latest">
  <p class="eyebrow">Version ${escapeHtml(latest.version)} · ${escapeHtml(latest.date)}</p>
  <h1 id="latest">Your phone and your servers, in one chest</h1>
  <p class="lede">A file manager for Android that browses the phone and your own SMB and SFTP servers, at home or over Tailscale. No sign-in, no ads, no tracking.</p>
  <div class="actions">
    <a class="button primary" href="${escapeHtml(latest.apk.browser_download_url)}">Download tansu</a>
    <a class="button" href="#install">How to install</a>
  </div>
  <p class="file">tansu.apk · ${size(latest.apk.size)} · Android 14 or newer${sum ? `<span class="hash">SHA-256 ${escapeHtml(sum)}</span>` : ""}</p>
</section>`;
}

function earlier(older) {
  if (!older.length) return "";
  const rows = older
    .map(
      (version) => `<details class="version">
  <summary><span class="name">${escapeHtml(version.version)}</span><span class="date">${escapeHtml(version.date)}</span></summary>
  <div class="body">${markdown(version.notes)}
    <p class="small">${version.apk ? `<a href="${escapeHtml(version.apk.browser_download_url)}">tansu.apk ${escapeHtml(version.version)}</a> · ` : ""}<a href="${escapeHtml(version.page)}">Release on GitHub</a></p>
  </div>
</details>`,
    )
    .join("\n");
  return `<section class="panel" aria-labelledby="older"><h2 id="older">Earlier versions</h2>${rows}</section>`;
}

export function renderPage(releases, repository = `https://github.com/${REPOSITORY}`) {
  const [latest, ...older] = versions(releases);
  const whatsNew = latest
    ? `<section class="panel" aria-labelledby="new"><h2 id="new">What's new in ${escapeHtml(latest.version)}</h2>${markdown(latest.notes)}</section>`
    : "";
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Tansu downloads</title>
<meta name="description" content="Download tansu, a file manager for Android that browses your phone and your own SMB and SFTP servers.">
<link rel="icon" href="data:image/svg+xml,${encodeURIComponent(MARK.replace(' width="40" height="40"', ""))}">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Figtree:wght@400;500;600;700;800&display=swap" rel="stylesheet">
<style>
  /* Kiri, tansu's look (docs/design.md): sand and paper, wood-brown and sage. */
  :root {
    color-scheme: light dark;
    --paper: #fbf7f1; --raised: #fffdf9; --sand: #f1e9de; --edge: #dccfbe;
    --ink: #2b2622; --ink-soft: #4f453c; --ink-muted: #6a5f55;
    --wood: #8a5a2b; --on-wood: #ffffff; --wood-soft: #f3dfc6; --on-wood-soft: #4a2e12;
    --sage: #e3e8d5; --on-sage: #2c3520;
    --sans: Figtree, system-ui, sans-serif; --mono: ui-monospace, "Cascadia Mono", monospace;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --paper: #1b1815; --raised: #242019; --sand: #2a251f; --edge: #4a4139;
      --ink: #ede4d9; --ink-soft: #d6cabd; --ink-muted: #bfb2a3;
      --wood: #e2b07a; --on-wood: #432a0e; --wood-soft: #5c3d1e; --on-wood-soft: #faddba;
      --sage: #3e4a31; --on-sage: #d9e4c6;
    }
  }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--paper); color: var(--ink); font: 16px/1.6 var(--sans);
    font-variant-numeric: tabular-nums; }
  a { color: var(--wood); }
  main, header, footer { width: min(820px, 100% - 32px); margin-inline: auto; }
  header { display: flex; align-items: center; gap: 12px; padding: 28px 0 20px; }
  header .word { font-weight: 800; font-size: 22px; letter-spacing: -0.01em; }
  header .kanji { color: var(--ink-muted); font-size: 15px; }
  header nav { margin-left: auto; }
  header nav a { color: var(--ink-muted); text-decoration: none; font-weight: 600; }
  main { display: grid; gap: 18px; }
  .hero { background: var(--wood-soft); color: var(--on-wood-soft); border-radius: 28px; padding: 36px 32px; }
  .eyebrow { display: inline-block; margin: 0 0 14px; padding: 4px 12px; border-radius: 999px;
    background: var(--paper); color: var(--ink-soft); font-size: 13px; font-weight: 700; }
  h1 { margin: 0; font-size: clamp(30px, 6vw, 44px); line-height: 1.1; font-weight: 800; letter-spacing: -0.02em; }
  .lede { margin: 12px 0 0; max-width: 36em; color: var(--on-wood-soft); }
  .actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 24px; }
  .button { display: inline-flex; align-items: center; justify-content: center; min-height: 48px;
    padding: 0 24px; border-radius: 999px; font-weight: 700; text-decoration: none;
    border: 1px solid var(--edge); color: var(--ink); background: var(--raised); }
  .button.primary { background: var(--wood); color: var(--on-wood); border-color: var(--wood); }
  .button:focus-visible, a:focus-visible, summary:focus-visible { outline: 2px solid var(--wood); outline-offset: 3px; }
  .file { margin: 16px 0 0; display: grid; gap: 2px; font-size: 13px; color: var(--on-wood-soft); }
  .hash { font: 12px/1.6 var(--mono); overflow-wrap: anywhere; opacity: 0.8; }
  .panel { background: var(--raised); border: 1px solid var(--edge); border-radius: 20px; padding: 24px 28px; }
  .panel.note { background: var(--sage); color: var(--on-sage); border-color: transparent; }
  .panel.note p, .panel.note li { color: var(--on-sage); }
  h2 { margin: 0 0 8px; font-size: 21px; font-weight: 800; letter-spacing: -0.01em; }
  p, li { color: var(--ink-soft); }
  ul, ol { margin: 8px 0 0; padding-left: 22px; }
  li { margin: 6px 0; }
  li::marker { color: var(--wood); font-weight: 700; }
  code { font: 14px var(--mono); background: var(--sand); padding: 1px 6px; border-radius: 6px; }
  .small { font-size: 14px; }
  details { border-top: 1px solid var(--edge); padding-top: 12px; margin-top: 12px; }
  summary { cursor: pointer; display: flex; gap: 10px; font-weight: 700; color: var(--ink); }
  summary .date { margin-left: auto; color: var(--ink-muted); font-weight: 500; }
  footer { padding: 28px 0 48px; font-size: 14px; }
  footer p { color: var(--ink-muted); margin: 4px 0; }
  @media (max-width: 560px) { .hero { padding: 28px 22px; } .panel { padding: 20px; } }
</style>
</head>
<body>
<header>${MARK}<span class="word">tansu</span><span class="kanji" lang="ja">箪笥</span><nav><a href="${repository}">GitHub</a></nav></header>
<main>
${hero(latest, repository)}
<section class="panel note" aria-labelledby="why">
  <h2 id="why">Not on the Play Store, on purpose</h2>
  <p>Tansu is a personal app, shared with people its author knows. It isn't on the Play Store or any other store, and it never will be. That's why your phone asks before installing it, and why it updates from here rather than from a store.</p>
  <p>It's open source and signed by its author: every version comes from the same code you can read on GitHub, and Android only installs an update signed with the same key.</p>
</section>
<section class="panel" id="install" aria-labelledby="how">
  <h2 id="how">How to install</h2>
  <ol>
    <li>On your phone, tap <strong>Download tansu</strong> above.</li>
    <li>Open the downloaded <code>tansu.apk</code>. Android asks whether your browser may install apps: allow it, just this once.</li>
    <li>On a Samsung phone, turn <strong>Auto Blocker</strong> off while you install (Settings › Security and privacy › Auto Blocker), and back on after.</li>
    <li>If Play Protect says it doesn't know the app, choose to install anyway.</li>
    <li>Open tansu and give it <strong>All files access</strong>, so it can show your phone's files.</li>
  </ol>
  <p>To update, download the newest version here and install it over the one you have: your servers and settings stay.</p>
</section>
<section class="panel" aria-labelledby="what">
  <h2 id="what">What tansu does</h2>
  <ul>
    <li>Browses your phone's storage, and your own SMB and SFTP servers, at home or over Tailscale.</li>
    <li>Cuts, copies, pastes, renames, shares and deletes, on the phone and on servers alike, with every job's progress in the app and in a notification.</li>
    <li>Keeps server passwords and keys encrypted on your phone. No sign-in, no accounts, no ads and no tracking: it talks only to your servers, and to GitHub to look for a new version.</li>
  </ul>
</section>
${whatsNew}
${earlier(older)}
</main>
<footer>
  <p>Tansu is free software under the <a href="${repository}/blob/main/LICENSE">MIT licence</a>. Figtree is under the SIL Open Font License.</p>
</footer>
</body>
</html>
`;
}

async function fetchReleases(repository) {
  const headers = { Accept: "application/vnd.github+json", "User-Agent": "tansu-downloads-page" };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const response = await fetch(`https://api.github.com/repos/${repository}/releases?per_page=100`, { headers });
  if (!response.ok) throw new Error(`GitHub answered ${response.status} for ${repository}'s releases.`);
  return response.json();
}

async function main() {
  const [folder, flag, file] = process.argv.slice(2);
  if (!folder) throw new Error("Usage: node scripts/downloads-page.mjs <folder> [--releases <file>]");
  const repository = process.env.GITHUB_REPOSITORY ?? REPOSITORY;
  const releases = flag === "--releases" ? JSON.parse(readFileSync(file, "utf8")) : await fetchReleases(repository);
  mkdirSync(folder, { recursive: true });
  writeFileSync(join(folder, "index.html"), renderPage(releases, `https://github.com/${repository}`));
  console.log(`Wrote the downloads page with ${versions(releases).length} versions.`);
}

const runDirectly = process.argv[1] && resolve(process.argv[1]).toLowerCase() === fileURLToPath(import.meta.url).toLowerCase();
if (runDirectly) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
