# Third-party licenses

## Automatic collection

Run `./gradlew :app:generateThirdPartyNotices`. AboutLibraries 15.3.0 reads resolved Gradle/POM metadata and downloads full SPDX license texts. The collector filters the result against the actual `releaseRuntimeClasspath` binary artifacts, scans JAR/AAR archives (including nested JARs) for embedded LICENSE/NOTICE/COPYING/COPYRIGHT text, and records SHA-256 hashes. No runtime UI dependency is added.

Generated outputs:

- `aboutlibraries.json`: automatically collected metadata and full license texts for the actual runtime artifacts.
- `dependencies.json`: normalized inventory, binary hashes and embedded notices.
- `THIRD_PARTY_NOTICES.txt`: repository-readable full notices, identical to the APK asset of that name.
- `app/src/main/assets/legal/LICENSES.txt`: the app's MIT license followed by the same third-party notices, shown offline in Settings.

Every Android pre-build runs `:app:verifyThirdPartyNotices`. It recollects actual dependency metadata and checks the committed outputs. Missing dependencies, missing/unapproved licenses, missing full texts, stale outputs, and unsupported local/project artifacts fail instead of being silently omitted. CI also tests these failure cases. New license types require updating `license-policy.json` after review.

## Bundled components and scope

Published Maven metadata does not describe every copied source file, native component or artwork license. Automatic collection does not establish exhaustive legal clearance. `supplemental.json` separately records known bundled components (including Kotlin's ThreeTenBP/Boost/GWT/Guava code, OkHttp's Public Suffix List/HPACK and the Markdown renderer's extended spans), source evidence and upstream notice files. These are additions to the automatic collector, not its input dependency list. Version and binary hashes are checked on every run; dependency changes invalidate the corresponding evidence and stop verification until reviewed. No new library can be assumed free of embedded components solely because its POM has a license.

Material Symbols auto_awesome (`ic_hero_spark.xml`) is attributed under Apache-2.0. Confirm the origin/rights of the custom launcher artwork before public release. Build-time/test tools and Android platform implementations are outside the APK inventory. The inventory is conservative before R8 shrinking.

## Public Suffix List source

`sources/public_suffix_list.dat` is the unmodified editable source from OkHttp commit `4984568367caaf359b82c452bd28b5e192824d1c` (parent-4.12.0), retaining its MPL notice. Its source hash and the packaged payload hash are checked. To verify the source serialization:

```sh
python3 scripts/verify_publicsuffix.py third_party/sources/public_suffix_list.dat /path/to/okhttp-4.12.0.jar
```

The source is also included in the APK. Releases attach only the APK; the source and license notices remain available in the APK and this repository. Yorube-original code is MIT licensed; third-party terms remain independent.

## Updating

1. Change the dependency versions and run `:app:generateThirdPartyNotices`.
2. Resolve missing/unapproved metadata and re-review invalidated bundled-component evidence. Do not bypass errors with blanket exclusions.
3. Review and commit all generated output changes together.
4. Run `:app:verifyThirdPartyNotices` and `python3 -m unittest discover -s scripts/tests`.
5. Sync the site using its `scripts/sync-app-legal.mjs` helper.
