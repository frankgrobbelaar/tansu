// Turns the Tansu Design canvas's boards (.dc.html) into plain HTML pages in docs/design/, so every
// board opens in a browser without the canvas.
//
//   node scripts/design-snapshot.mjs --design <folder>
//
// The canvas as Claude's Artifact tool saves it: a folder holding project/canvas.json. Its Main
// board becomes index.html and every other board a page of its own, named in kebab case
// (CutCopyPaste → cut-copy-paste.html). The Kit boards are parts the others mount with
// <dc-import>; they're drawn into the pages that use them, not written out alone.
// See docs/design.md.
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const CANVASES = {
  design: "https://claude.ai/artifact/HCxYiAaQqE7Qr8RqXmuDXB",
};
const out = join(dirname(fileURLToPath(import.meta.url)), "..", "docs", "design");
const today = new Date().toISOString().slice(0, 10);

const folders = foldersIn(process.argv.slice(2));
if (!folders) {
  console.error("Usage: node scripts/design-snapshot.mjs --design <folder>");
  process.exit(1);
}
let written = 0;

if (folders.design) {
  const design = readCanvas(folders.design);
  mkdirSync(out, { recursive: true });
  for (const file of design.order) {
    const name = file.replace(".dc.html", "");
    if (name.startsWith("Kit")) continue;
    writeFileSync(join(out, pageName(name)), toPage(design, file, CANVASES.design));
    written += 1;
  }
}

console.log(`Wrote ${written} pages to ${out}`);

/** The canvases' folders by name (`--design <folder>`), or nothing when the arguments don't read. */
function foldersIn(args) {
  const found = {};
  for (let index = 0; index < args.length; index += 2) {
    const name = args[index].replace(/^--/, "");
    if (!(name in CANVASES) || !args[index + 1]) return null;
    found[name] = args[index + 1];
  }
  return Object.keys(found).length > 0 ? found : null;
}

function readCanvas(folder) {
  const index = JSON.parse(readFileSync(join(folder, "project", "canvas.json"), "utf8"));
  const source = (file) => readFileSync(join(folder, "project", file), "utf8");
  return { ...index, source };
}

function pageName(board) {
  if (board === "Main") return "index.html";
  return `${board.replace(/([a-z0-9])([A-Z])/g, "$1-$2").toLowerCase()}.html`;
}

function toPage(canvas, file, url) {
  const source = canvas.source(file);
  const title = canvas.boards[file]?.title ?? file;
  const helmet = source.match(/<helmet>([\s\S]*?)<\/helmet>/)?.[1] ?? "";
  const body = expand(boardOf(source), renderValues(source), canvas).replace(
    /href="([\w.-]+)\.dc\.html"/g,
    (_, target) => `href="${pageName(target)}"`,
  );
  return `<!doctype html>
<!-- Tansu design snapshot of "${title}" (${url}), taken ${today}.
     Change the canvas, then re-run scripts/design-snapshot.mjs; don't edit this file. -->
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} · Tansu design</title>
${helmet.trim()}
</head>
<body>
${body.trim()}
</body>
</html>
`;
}

/** A board's markup without its helmet, and without handlers (a drag, a ref) that only the canvas runs. */
function boardOf(source) {
  return source
    .match(/<x-dc>([\s\S]*?)<\/x-dc>/)[1]
    .replace(/<helmet>[\s\S]*?<\/helmet>/, "")
    .replace(/\s(?:ref|on[A-Z]\w*)="\{\{[^}]+\}\}"/g, "");
}

// A board's script builds the data its template repeats over. It only computes
// values, so it runs in an empty sandbox with nothing but the base class it needs.
function renderValues(source, props = {}) {
  const script = source.match(/<script type="text\/x-dc"[^>]*>([\s\S]*?)<\/script>/)?.[1];
  if (!script) return {};
  const sandbox = vm.createContext({
    DCLogic: class {
      constructor() {
        this.props = props;
        this.state = {};
      }
    },
  });
  return vm.runInContext(`${script}\n;new Component().renderVals()`, sandbox, { timeout: 1000 });
}

// Expands <sc-for>, <sc-if>, <dc-import> and {{holes}} the way the canvas renders them.
function expand(html, scope, canvas) {
  let result = "";
  let rest = html;
  for (;;) {
    const open = rest.match(/<sc-(for|if)\b([^>]*)>|<dc-import\b([^>]*)><\/dc-import>/);
    if (!open) return result + fillHoles(rest, scope);
    result += fillHoles(rest.slice(0, open.index), scope);
    if (open[3] !== undefined) {
      result += mount(open[3], scope, canvas);
      rest = rest.slice(open.index + open[0].length);
      continue;
    }
    const [tag, kind, attributes] = open;
    const close = findClose(rest, kind, open.index + tag.length);
    const inner = rest.slice(open.index + tag.length, close);
    const binding = (name) =>
      attributes.match(new RegExp(`\\b${name}="\\{\\{([^}]+)\\}\\}"`))?.[1].trim();

    if (kind === "for") {
      const as = attributes.match(/\bas="([^"]+)"/)[1];
      for (const item of lookup(scope, binding("list")) ?? []) {
        result += expand(inner, { ...scope, [as]: item }, canvas);
      }
    } else if (lookup(scope, binding("value"))) {
      result += expand(inner, scope, canvas);
    }
    rest = rest.slice(close + `</sc-${kind}>`.length);
  }
}

// A <dc-import> draws its sibling board with the attributes as props: `name` picks the board,
// `hint-*` only matter while the canvas loads, and kebab-case names become camelCase.
function mount(attributes, scope, canvas) {
  let board = null;
  const props = {};
  for (const [, key, value] of attributes.matchAll(/([\w-]+)="([^"]*)"/g)) {
    if (key === "name") board = value;
    else if (!key.startsWith("hint-")) props[camelCase(key)] = propValue(value, scope);
  }
  const source = canvas.source(`${board}.dc.html`);
  return expand(boardOf(source), renderValues(source, props), canvas);
}

function propValue(value, scope) {
  const whole = value.match(/^\{\{([^}]+)\}\}$/);
  if (whole) return lookup(scope, whole[1].trim());
  return value.replace(/\{\{([^}]+)\}\}/g, (_, path) => String(lookup(scope, path.trim()) ?? ""));
}

function camelCase(name) {
  return name.replace(/-(\w)/g, (_, letter) => letter.toUpperCase());
}

function findClose(html, kind, from) {
  const tags = new RegExp(`<sc-${kind}\\b|</sc-${kind}>`, "g");
  tags.lastIndex = from;
  let depth = 1;
  for (let tag = tags.exec(html); tag; tag = tags.exec(html)) {
    depth += tag[0].startsWith("</") ? -1 : 1;
    if (depth === 0) return tag.index;
  }
  throw new Error(`<sc-${kind}> is never closed`);
}

function fillHoles(html, scope) {
  return html.replace(/\{\{([^}]+)\}\}/g, (_, path) => escapeHtml(lookup(scope, path.trim()) ?? ""));
}

function lookup(scope, path) {
  return path.split(".").reduce((value, key) => value?.[key], scope);
}

function escapeHtml(value) {
  const entities = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" };
  return String(value).replace(/[&<>"]/g, (character) => entities[character]);
}
