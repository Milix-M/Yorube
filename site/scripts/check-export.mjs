import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";

const base = process.env.NEXT_PUBLIC_BASE_PATH ?? "/Yorube";
const output = path.resolve("out");
const pages = ["", "licenses", "support", "en", "en/licenses", "en/support"];
let checked = 0;
const descriptions = new Set();
for (const page of pages) {
  const html = fs.readFileSync(path.join(output, page, "index.html"), "utf8");
  assert.match(
    html,
    new RegExp(`<html lang="${page.startsWith("en") ? "en" : "ja"}"`),
  );
  assert.doesNotMatch(
    html,
    /プライバシー|配布状況|よくある質問|Frequently asked questions|signed distribution is in preparation|Release guide/,
  );
  const title = html.match(/<title>([^<]+)<\/title>/)?.[1];
  assert.ok(title?.includes("Yorube"), `Missing site title: ${page}`);
  assert.doesNotMatch(title, /[—―─]/);
  const metadata = new Map();
  for (const [tag] of html.matchAll(/<meta\b[^>]*>/g)) {
    const attrs = Object.fromEntries(
      [...tag.matchAll(/([\w:-]+)="([^"]*)"/g)].map(([, key, value]) => [
        key,
        value,
      ]),
    );
    metadata.set(attrs.name || attrs.property, attrs.content);
  }
  const description = metadata.get("description");
  assert.ok(description, `Missing page description: ${page}`);
  assert.ok(
    !descriptions.has(description),
    `Page inherited another page's description: ${page}`,
  );
  descriptions.add(description);
  for (const key of ["og:title", "twitter:title"]) {
    assert.equal(
      metadata.get(key),
      title,
      `Sharing title differs from page title: ${page}`,
    );
  }
  for (const key of ["og:description", "twitter:description"]) {
    assert.equal(
      metadata.get(key),
      description,
      `Sharing description differs from page description: ${page}`,
    );
  }
  const expectedUrl = `https://milix-m.github.io${base}/${page ? page + "/" : ""}`;
  assert.equal(metadata.get("og:url"), expectedUrl);
  const canonical = html.match(/<link rel="canonical" href="([^"]+)"/);
  assert.equal(canonical?.[1], expectedUrl);
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
  `Checked ${pages.length} bilingual pages, their titles/descriptions/sharing metadata and ${checked} internal asset/link references.`,
);
