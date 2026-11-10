# Third-party materials

`dependencies.json` records the 77 resolved Android release runtime artifacts reviewed on 2026-11-10, their matching published sources, observed copyright headers, and additional embedded components. The inventory is conservative before R8 shrinking; it is not a claim that every class remains in the final APK.

Full license texts and upstream NOTICE files are retained in `licenses/` and `notices/`. In particular, the Kotlin standard library contains ThreeTenBP (BSD-3-Clause), Boost math (BSL-1.0), and Apache-licensed GWT/Guava code; OkHttp contains the MPL-2.0 Public Suffix List and Apache-licensed Twitter HPACK code. Boost is included conservatively; its license has an executable-object-code exception.

## Public Suffix List source

`sources/public_suffix_list.dat` is the unmodified editable source from OkHttp commit `4984568367caaf359b82c452bd28b5e192824d1c` (parent-4.12.0). The original MPL header is preserved. Re-serializing its 9,499 rules and 8 exceptions reproduces the packaged uncompressed OkHttp resource exactly. Run:

```sh
python3 scripts/verify_publicsuffix.py third_party/sources/public_suffix_list.dat /path/to/okhttp-4.12.0.jar
```

The matching source is included in the APK and as a release attachment. This makes it available alongside the binary even if a remote repository changes.

## Updating dependencies

1. Run `./gradlew :app:exportReleaseDependencies` and compare `app/build/reports/legal/release-dependencies.txt`.
2. Review the exact published POM, matching sources, embedded NOTICE/license files, and native components. Do not infer that transitive content shares a library's top-level license.
3. Update the inventory and source/license materials.
4. Run `python3 scripts/generate_legal.py`.
5. Run `./gradlew :app:verifyThirdPartyNotices` and `python3 scripts/generate_legal.py --check`.

The Gradle check catches added, removed, and upgraded resolved artifacts. It does not replace a review of newly copied source, icons, fonts, or native toolchain code. Build/test tools and Android system libraries are outside this APK inventory.

## Artwork and scope

`ic_hero_spark.xml` is derived from Material Symbols auto_awesome and is covered by the included Apache license and attribution. Confirm the origin and rights of the custom launcher star with its author before public release. No copied Hermes server code was found; using its HTTP API does not relicense Yorube.

Yorube-original code is licensed under the MIT License in the repository's root LICENSE. Third-party components retain their respective terms; the project's MIT License does not override them.
