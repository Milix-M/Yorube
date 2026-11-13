# Yorube website

Black-based, bilingual Japanese/English site, statically exported with Next.js and hosted on GitHub Pages at https://milix-m.github.io/Yorube/.

The source lives in `site/` on the app repository's `develop` branch. No application server, hosting account, analytics, external fonts or credentials are required.

## Development

Use Node.js 22 or later. From `site/`:

```sh
npm ci
npm run dev
```

Open http://localhost:3000/Yorube/. The default `NEXT_PUBLIC_BASE_PATH` is `/Yorube`; set it to an empty string for domain-root hosting. Rebuild whenever it changes. Plain links and assets include this prefix; Next.js navigation adds it automatically.

```sh
npm run build
npm run check
```

`out/` contains the complete site. Checks cover both languages, page and sharing metadata, internal links and assets, removed routes, and the GitHub license link. Open-source license links point to the app repository's automatically generated notices; the site does not maintain a separate inventory. Website source is MIT, and its dependencies retain their own terms.

## Publishing

`.github/workflows/site.yml` validates pull requests targeting `develop`. Changes merged into `develop` build and deploy to GitHub Pages using its `github-pages` environment. Set the Pages publishing source to **GitHub Actions**. The workflow uses GitHub's short-lived token and does not require a personal access token or modify `main`.

## Languages and app screenshots

Japanese pages are at `/Yorube/`, English pages at `/Yorube/en/`. The language switch preserves the current page. Keep both languages updated.

`public/screenshots/` contains unedited screenshots of the actual Android app: chat, sidebar, settings, sent images and running conversations, in Japanese and English. `provenance.json` records their source, capture method and hashes. Screenshots use a development build and sample conversations; they are not photographs of a physical device.

For updated captures, install the debug APK in an Android virtual device, connect it to a synthetic test server, use the app normally, and capture with `adb exec-out screencap -p`. Do not embed credentials or real conversations. Keep the original PNGs and update the provenance hashes. The older `scripts/capture-android.mjs` fixture remains available for Android-rendered component previews; it is not the source of the current device screenshots.
