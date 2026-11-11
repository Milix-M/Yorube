# 公開手順

対象ブランチは `develop` です。アプリの紹介サイトはGitHub Pagesで配信します。アプリの署名・正式リリースはサイトの公開とは別に実施します。

## ライセンスと配布先

- アプリ本体はMIT Licenseです。全文はルートの `LICENSE` にあります。第三者の条件は `third_party/` のとおり独立して適用されます。ランチャーアイコンの出典・権利は公開前に確認してください。
- 配布先。まずGitHub Releasesでの署名済みAPK配布を想定した手順です。Google Playへの登録は行っていません。
- サポート窓口。現在は公開GitHub Issuesです。秘密情報を扱う非公開の連絡先を設ける場合は実際に運用できる窓口を追加してください。

## リリース前の確認

1. 認証・通信・送信復旧の修正（PR #31）と公開準備の変更をdevelopに取り込みます。
2. `legal/privacy.json` を確認し、`./gradlew :app:generateThirdPartyNotices` で実依存からライセンスを自動収集・生成します。未検出や、内包コードの補足情報が更新されていない場合は検証で停止します。生成したリポジトリ文書とアプリ内表示を確認してください。
3. `./gradlew :app:verifyThirdPartyNotices :app:jacocoTestReport :app:jacocoCoverageVerification :app:assembleRelease :app:lintRelease` を実行します。
4. 実機で新規インストール、設定、接続テスト、画像添付、承認、通知、バックグラウンド復帰、オフラインの法的文書表示を確認します。
5. バージョン名・コードを更新します。タグだけを変更して既存のバージョン番号を再配布しないでください。
6. APKの署名を `apksigner verify --print-certs` で確認し、公開予定の証明書のSHA-256を別の信頼できる経路でも案内します。
7. 正式な配布URLと対応環境をREADMEなどの配布案内に反映します。過去のdebug APKを新しい修正版として案内しないでください。

## 署名鍵とGitHub Releases

次のRepository secretsをGitHubの設定から登録します。秘密鍵やパスワードをIssue、PR、チャット、Git管理下のファイルに貼り付けないでください。

- `YORUBE_SIGNING_STORE_BASE64`
- `YORUBE_SIGNING_STORE_PASSWORD`
- `YORUBE_SIGNING_KEY_ALIAS`
- `YORUBE_SIGNING_KEY_PASSWORD`

鍵は所有者が安全な場所で生成・バックアップし、今後の更新でも同じ鍵を使います。署名鍵の生成・登録や正式リリースの発行は、この公開準備には含めていません。

`develop` に存在する確認済みコミットへ `v*` タグを付けると、release workflowが検証・署名・公開します。本体の `LICENSE` が欠落している場合は公開処理を停止します。APK、SHA256SUMS、プライバシー文書、第三者表示、MPL対象のPublic Suffix List原本、本体LICENSEを同時に配布します。

## 過去のdebug版からの移行

過去のdebug署名と正式版の署名が異なる場合、そのまま上書きインストールできません。必要な接続情報を安全に控え、旧版を削除して正式版をインストールします。削除により端末内の設定・未送信データは失われます。サーバーに保存された会話はサーバー側で管理されます。新しい開発版は `.debug` の別IDです。

## Google Playを利用する場合

公開時点の対象API要件・アプリ署名・データセーフティ申告・プライバシーポリシーURL・ストア素材を確認してください。現在のtargetSdkは34で、Play向けの適合を確認済みという意味ではありません。フォームの申告内容は、接続先サーバーや配布版の実装と一致させます。

## サイトの更新

サイトのソースはリポジトリ内の `site/` で管理し、https://milix-m.github.io/Yorube/ に配信します。黒基調の日英サイトで、プライバシー・配布状況のページは設けません。アプリ内の文書は引き続きアプリ側で管理します。

GitHub Pagesの公開元を **GitHub Actions** に設定します。`develop` 向けのPRで静的ビルドとリンク・画像・言語・ライセンス同期を検証し、`develop` への取り込み後に `.github/workflows/site.yml` が公開します。`main` は使用しません。初回公開は検証した静的ファイルを `gh-pages` に配置して行い、その後はActionsを公開元とします。

ライセンス更新時は `cd site && node scripts/sync-app-legal.mjs ..` を実行し、同期結果もコミットします。`npm ci && npm run build && npm run check` でPages用の出力を確認できます。詳しくは [サイトのREADME](../site/README.md) を参照してください。
