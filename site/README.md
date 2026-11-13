# Yorube website

Black-based, bilingual Japanese/English site, statically exported with Next.js and hosted on GitHub Pages at https://milix-m.github.io/Yorube/.

The source lives in `site/` on the app repository's `develop` branch. No server, Sites account, Cloudflare Worker, analytics, external fonts or credentials are required. The website has no privacy or distribution-status pages.

## Development

Use Node.js 22 or later. From `site/`:

```sh
npm ci
node scripts/sync-app-legal.mjs ..
npm run dev
```

Open http://localhost:3000/Yorube/. The default `NEXT_PUBLIC_BASE_PATH` is `/Yorube`; set it to an empty string for domain-root hosting. Rebuild whenever it changes. Plain links and assets include this prefix; Next.js navigation adds it automatically.

```sh
npm run build
npm run check
```

`out/` contains the complete site. Checks cover both languages, internal links and assets, removed routes, and matching generated app notices. The license page covers the Android app; website source is MIT, and its dependencies retain their own terms.

## Publishing

`.github/workflows/site.yml` validates pull requests targeting `develop`. Changes merged into `develop` build and deploy to GitHub Pages using its `github-pages` environment. The Pages publishing source must be **GitHub Actions**. The workflow uses GitHub's short-lived token and does not require a personal access token or modify `main`.

The first publication can use the checked static output in a `gh-pages` branch, then switch the Pages source to GitHub Actions. Subsequent deployments come from `develop`; the bootstrap branch is not the maintained source.

## Languages and app screenshots

Japanese pages are at `/Yorube/`, English pages at `/Yorube/en/`. The language switch preserves the current page. Keep both languages updated.

`public/screenshots/` contains six unedited Android-rendered PNGs: chat, sidebar and settings in Japanese/English. `provenance.json` records the app commit, method and hashes. They show production Compose screens rendered by Robolectric native graphics with sample data, not photographs of a physical device.

Refresh them with JDK 17 and the Android SDK configured:

```sh
node scripts/capture-android.mjs ..
```

Additional Gradle flags can follow the app path. The helper temporarily installs the capture fixture, runs six captures and removes it afterward. It does not change production app code.
