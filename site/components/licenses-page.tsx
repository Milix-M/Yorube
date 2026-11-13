import { LegalShell, type Locale } from "./site-chrome";
import inventory from "@/data/dependencies.json";
import appLicense from "@/data/app-license.json";
import { assetPath } from "@/lib/paths";
export function LicensesPage({ locale }: { locale: Locale }) {
  const en = locale === "en";
  return (
    <LegalShell
      locale={locale}
      page="licenses"
      title={en ? "Licenses" : "ライセンス"}
    >
      <h2>{en ? "Yorube" : "Yorube本体"}</h2>
      <p>
        {en ? "Yorube is available under the " : "Yorubeは"}
        <a href={assetPath("/legal/LICENSE.txt")}>{appLicense.name}</a>
        {en ? "." : "で公開しています。"}
      </p>
      <h2>{en ? "Third-party software" : "第三者のソフトウェア"}</h2>
      <p>
        {en
          ? "Yorube uses libraries including AndroidX, Kotlin and OkHttp. Their respective terms apply independently of Yorube’s own license."
          : "YorubeはAndroidX、Kotlin、OkHttpなどのライブラリを利用しています。各ライブラリの利用条件はYorube本体のライセンスとは別に適用されます。"}
      </p>
      <p>
        <a href={assetPath("/legal/THIRD_PARTY_NOTICES.txt")}>
          {en
            ? "Full third-party copyright notices and license texts"
            : "第三者の著作権表示・ライセンス全文"}
        </a>
      </p>
      <p>
        {en
          ? "In addition to Apache 2.0, the notices include BSD 3-Clause for ThreeTenBP and BSL 1.0 for Boost within Kotlin, and MPL 2.0 for the Public Suffix List within OkHttp. You can also read the original texts offline in the app’s Settings."
          : "Apache 2.0のほか、Kotlinに含まれるThreeTenBPのBSD 3-ClauseやBoostのBSL 1.0、OkHttpに含まれるPublic Suffix ListのMPL 2.0などのライセンス本文を同梱しています。原文はアプリの設定画面でもオフラインで読めます。"}
      </p>
      <p>
        {en
          ? "The dependency inventory and license texts are collected automatically with AboutLibraries. Embedded-component notices are maintained separately with version and artifact checks."
          : "依存ライブラリの一覧とライセンス本文はAboutLibrariesで自動収集しています。ライブラリに含まれるコンポーネントの補足情報は別に管理し、バージョンとアーカイブを照合しています。"}
      </p>
      <p>
        <a href={assetPath("/legal/dependencies.json")} download>
          {en
            ? "Download the machine-generated inventory (JSON)"
            : "自動生成した依存ライブラリ一覧を保存（JSON）"}
        </a>
      </p>
      <h2>{en ? "Public Suffix List source" : "Public Suffix Listのソース"}</h2>
      <p>
        {en
          ? "The Public Suffix List included in OkHttp 4.12.0 is covered by MPL 2.0. Download the corresponding editable source below. Its original license notice is preserved and its contents are unmodified."
          : "OkHttp 4.12.0に含まれるPublic Suffix ListはMPL 2.0の対象です。対応する編集可能な原本を以下から保存できます。元のライセンス表示を保持しており、内容は変更していません。"}
      </p>
      <p>
        <a href={assetPath("/legal/public_suffix_list.dat")} download>
          {en
            ? "Download the corresponding Public Suffix List source"
            : "対応するPublic Suffix List原本を保存"}
        </a>
      </p>
      <p>
        {en ? "Source: OkHttp commit " : "出典: OkHttpコミット "}
        <code>4984568367caaf359b82c452bd28b5e192824d1c</code>
        {en
          ? ". Rebuilding this source has been verified to reproduce the data in the packaged resource."
          : "。この原本から生成したデータが同梱されるリソースと一致することを検証しています。"}
      </p>
      <h2>{en ? "App dependencies" : "アプリの依存ライブラリ"}</h2>
      <p className="updated">
        {en
          ? `Automatically generated from ${inventory.dependencies.length} resolved release artifacts, before build-time shrinking.`
          : `リリース用の依存ライブラリ${inventory.dependencies.length}件から自動生成した一覧です。ビルド時の縮小処理前の情報を記載しています。`}
      </p>
      <table>
        <thead>
          <tr>
            <th>{en ? "Library / version" : "ライブラリ / バージョン"}</th>
            <th>{en ? "Declared license" : "宣言されたライセンス"}</th>
          </tr>
        </thead>
        <tbody>
          {inventory.dependencies.map((d) => (
            <tr key={d.coordinate}>
              <td>
                {d.project_url ? (
                  <a href={d.project_url}>{d.coordinate}</a>
                ) : (
                  d.coordinate
                )}
              </td>
              <td>{d.declared_license_spdx.join(", ")}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <h2>{en ? "Names and icons" : "名称とアイコン"}</h2>
      <p>
        {en
          ? "Hermes Agent is a Nous Research project. Yorube is an unofficial client and does not imply affiliation or endorsement. App icons derived from Material Symbols are included in the Apache 2.0 attribution."
          : "Hermes AgentはNous Researchのプロジェクトです。Yorubeは非公式クライアントであり、提携や公式な承認を示すものではありません。アプリ内のMaterial Symbols由来のアイコンもApache 2.0の表示に含めています。"}
      </p>
    </LegalShell>
  );
}
