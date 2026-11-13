import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";

const base = process.env.NEXT_PUBLIC_BASE_PATH ?? "/Yorube";
const output = path.resolve("out");
const pages = ["", "support", "en", "en/support"];
const screenshots = JSON.parse(
  fs.readFileSync("public/screenshots/provenance.json", "utf8"),
);
assert.match(screenshots.commit, /^[0-9a-f]{40}$/);
for (const locale of ["ja", "en"]) {
  for (const screen of ["chat", "drawer", "settings", "images", "running"]) {
    const name = `${locale}-${screen}.png`;
    const png = fs.readFileSync(`public/screenshots/${name}`);
    assert.equal(
      createHash("sha256").update(png).digest("hex"),
      screenshots.images[name],
      `Stale screenshot provenance: ${name}`,
    );
    assert.equal(png.readUInt32BE(16), 720);
    assert.equal(png.readUInt32BE(20), 1280);
  }
}
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
    /プライバシー|Privacy|privacy policy|配布状況|よくある質問|Frequently asked questions|signed distribution is in preparation|Release guide/,
  );
  const title = html.match(/<title>([^<]+)<\/title>/)?.[1];
  assert.ok(title?.includes("Yorube"), `Missing site title: ${page}`);
  assert.doesNotMatch(title, /[—―─]/);
  assert.match(
    html,
    /href="https:\/\/github.com\/Milix-M\/Yorube\/blob\/develop\/third_party\/THIRD_PARTY_NOTICES.txt"/,
  );
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
  "licenses",
  "download",
  "en/privacy",
  "en/licenses",
  "en/download",
  "legal/PRIVACY.txt",
  "legal/PRIVACY.en.txt",
]) {
  assert.ok(
    !fs.existsSync(path.join(output, removed)),
    `Removed route/file was exported: ${removed}`,
  );
}
console.log(
  `Checked ${pages.length} bilingual pages, their titles/descriptions/sharing metadata and ${checked} internal asset/link references.`,
);
