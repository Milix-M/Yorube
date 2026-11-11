import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";

const base = process.env.NEXT_PUBLIC_BASE_PATH ?? "/Yorube";
const output = path.resolve("out");
const pages = ["", "licenses", "support", "en", "en/licenses", "en/support"];
let checked = 0;
for (const page of pages) {
  const html = fs.readFileSync(path.join(output, page, "index.html"), "utf8");
  assert.match(
    html,
    new RegExp(`<html lang="${page.startsWith("en") ? "en" : "ja"}"`),
  );
  assert.doesNotMatch(
    html,
    /プライバシー|配布状況|signed distribution is in preparation|Release guide/,
  );
  for (const [, attribute] of html.matchAll(/(?:href|src)="([^"]+)"/g)) {
    if (!attribute.startsWith("/")) continue;
    const url = new URL(
      attribute.replaceAll("&amp;", "&"),
      "https://milix-m.github.io",
    );
    assert.ok(
      !base || url.pathname === base || url.pathname.startsWith(`${base}/`),
      `Unprefixed asset/link: ${attribute}`,
    );
    const relative = decodeURIComponent(
      url.pathname.slice(base.length),
    ).replace(/^\//, "");
    const target = path.join(output, relative);
    assert.ok(fs.existsSync(target), `Missing exported target: ${attribute}`);
    if (fs.statSync(target).isDirectory()) {
      assert.ok(
        fs.existsSync(path.join(target, "index.html")),
        `Missing page: ${attribute}`,
      );
    }
    checked++;
  }
}
for (const removed of [
  "privacy",
  "download",
  "en/privacy",
  "en/download",
  "legal/PRIVACY.txt",
  "legal/PRIVACY.en.txt",
]) {
  assert.ok(
    !fs.existsSync(path.join(output, removed)),
    `Removed route/file was exported: ${removed}`,
  );
}
assert.ok(
  fs
    .readFileSync("../third_party/THIRD_PARTY_NOTICES.txt")
    .equals(fs.readFileSync("public/legal/THIRD_PARTY_NOTICES.txt")),
  "Site notices differ from repository notices",
);
assert.equal(
  JSON.parse(fs.readFileSync("public/legal/dependencies.json", "utf8"))
    .schema_version,
  2,
);
console.log(
  `Checked ${pages.length} bilingual pages and ${checked} internal asset/link references.`,
);
