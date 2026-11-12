// Writes a release's notes from CHANGELOG.md, for the release workflow.
//
//   node scripts/release-notes.mjs <version> <file>
//
// Stops with an error when the changelog has no section for the version, or an empty one: every
// release says what changed. See docs/releasing.md.
import { readFileSync, writeFileSync } from "node:fs";

const [version, file] = process.argv.slice(2);
if (!version || !file) {
  console.error("Usage: node scripts/release-notes.mjs <version> <file>");
  process.exit(1);
}

/** The text under "## <version> - <date>", up to the next "## " heading, or null. */
export function sectionFor(changelog, version) {
  const lines = changelog.replaceAll("\r\n", "\n").split("\n");
  const start = lines.findIndex((line) => line.startsWith(`## ${version} - `));
  if (start === -1) return null;
  const rest = lines.slice(start + 1);
  const end = rest.findIndex((line) => line.startsWith("## "));
  return (end === -1 ? rest : rest.slice(0, end)).join("\n").trim();
}

const notes = sectionFor(readFileSync("CHANGELOG.md", "utf8"), version);
if (!notes) {
  console.error(`::error::CHANGELOG.md has nothing under "## ${version} - <date>".`);
  process.exit(1);
}
writeFileSync(file, `${notes}\n`);
console.log(`Notes for ${version}: ${notes.split("\n").length} lines.`);
