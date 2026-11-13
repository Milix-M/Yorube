# 公開手順

アプリは `develop` から `main` へのPRで確認し、`main` に取り込まれたコミットからリリースします。紹介サイトは `develop` からGitHub Pagesへ公開します。

## ライセンスと配布先

- アプリ本体はMIT Licenseです。全文はルートの `LICENSE` にあります。第三者の条件は `third_party/` のとおり独立して適用されます。ランチャーアイコンの出典・権利は公開前に確認してください。
- 配布先。まずGitHub Releasesでの署名済みAPK配布を想定した手順です。Google Playへの登録は行っていません。
- サポート窓口。現在は公開GitHub Issuesです。秘密情報を扱う非公開の連絡先を設ける場合は実際に運用できる窓口を追加してください。

## リリース前の確認

1. 公開する変更をdevelopにまとめ、mainへのPRでCIと動作を確認します。
2. `./gradlew :app:generateThirdPartyNotices` で実依存からライセンスを自動収集・生成します。未検出や、内包コードの補足情報が更新されていない場合は検証で停止します。生成したリポジトリ文書とAPKの同梱文書、アプリ・サイトからのGitHubリンクを確認してください。
3. `./gradlew :app:verifyThirdPartyNotices :app:jacocoTestReport :app:jacocoCoverageVerification :app:assembleRelease :app:lintRelease` を実行します。
4. 配布用署名のAPKを新規インストールし、設定、接続テスト、画像添付、承認、通知、バックグラウンド復帰、GitHubへのライセンスリンクを確認します。仮想端末と実機のどちらで確認したかを記録します。
5. バージョン名・コードを更新します。タグだけを変更して既存のバージョン番号を再配布しないでください。
6. `bash scripts/package-release.sh` で署名・バージョン・同梱文書を検証し、配布物を生成します。`app/build/outputs/distribution` で `sha256sum --check SHA256SUMS` を実行します。
7. 正式な配布URLと対応環境をREADMEなどの配布案内に反映します。過去のdebug APKを新しい修正版として案内しないでください。

## 署名鍵とGitHub Releases

次のRepository secretsをGitHubの設定から登録します。秘密鍵やパスワードをIssue、PR、チャット、Git管理下のファイルに貼り付けないでください。

- `YORUBE_SIGNING_STORE_BASE64`
- `YORUBE_SIGNING_STORE_PASSWORD`
- `YORUBE_SIGNING_KEY_ALIAS`
- `YORUBE_SIGNING_KEY_PASSWORD`

鍵とパスワードは非公開の場所へバックアップし、今後の更新でも同じ鍵を使います。公開する証明書を `legal/release-signing-certificate.pem`、DER形式の証明書のSHA-256を `legal/release-signing-certificate.sha256` に保存します。この2ファイルには秘密鍵を含めません。配布物の生成時に、APKの署名と公開証明書が一致しなければ停止します。

### 公開せずに配布物を確認する

GitHub Actionsのreleaseを手動実行します。対象ブランチは `develop` または `main` です。

```sh
gh workflow run release.yml --ref develop
```

成功した実行の `yorube-release` Artifactから署名済みAPKと検証用ファイルを取得します。手動実行ではタグやGitHub Releaseを作成しません。

GitHub Releasesへの添付は `yorube.apk` だけです。証明書、ビルド情報、チェックサムなどはCIの検証用に留めます。APKのアプリID・バージョン・署名、デバッグ不可の設定、APK内のライセンス文書との一致を公開前に検証します。本体と第三者のライセンス、Public Suffix List原本はAPK内とリポジトリで確認できます。

### v0.9.3を公開する

PRをmainへ取り込み、CIが成功したコミットを確認してからタグを付けます。次のタグをpushすると配布物がGitHub Releasesへ公開されます。`v0.*` とハイフンを含むタグはプレリリース扱いです。

```sh
git fetch origin main
git log -1 origin/main
git tag v0.9.3 origin/main
git push origin v0.9.3
```

タグのコミットが `origin/main` に含まれない場合、タグとアプリのバージョン名が異なる場合、署名・法的文書・テスト・カバレッジの検証が失敗した場合は公開を停止します。

## 過去のdebug版からの移行

過去のdebug署名と正式版の署名が異なる場合、そのまま上書きインストールできません。必要な接続情報を安全に控え、旧版を削除して正式版をインストールします。削除により端末内の設定・未送信データは失われます。サーバーに保存された会話はサーバー側で管理されます。新しい開発版は `.debug` の別IDです。

## Google Playを利用する場合

公開時点の対象API要件・アプリ署名・データセーフティ申告・プライバシーポリシーURL・ストア素材を確認してください。現在のtargetSdkは34で、Play向けの適合を確認済みという意味ではありません。フォームの申告内容は、接続先サーバーや配布版の実装と一致させます。

## サイトの更新

サイトのソースはリポジトリ内の `site/` で管理し、https://milix-m.github.io/Yorube/ に配信します。黒基調の日英サイトです。オープンソースライセンスは、アプリ・サイトともGitHubの自動生成文書へリンクします。

GitHub Pagesの公開元を **GitHub Actions** に設定します。`develop` 向けのPRで静的ビルドとリンク・画像・言語・メタデータを検証し、`develop` への取り込み後に `.github/workflows/site.yml` が公開します。

`site/` で `npm ci`、`npm run build`、`npm run check` を実行して、Pages用の出力を確認します。詳しくは [サイトのREADME](../site/README.md) を参照してください。
