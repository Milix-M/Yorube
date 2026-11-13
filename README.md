# Yorube

[Hermes Agent](https://github.com/nousresearch/hermes-agent) の非公式 Android クライアントです。サーバー上のHermesに接続し、チャットや実行状況の確認、承認・質問への回答ができます。

- 会話の履歴・検索・ピン留め
- 画像の添付（JPEG・PNG・WebP・GIF、1枚5 MB・最大3枚）
- 複数会話のバックグラウンド受信と、完了・承認・質問の端末内通知
- スラッシュコマンドによる操作（[対応コマンド](docs/COMMANDS.md)）

## はじめ方

Android 8.0以上と、HTTPSで接続できるHermes APIサーバーが必要です。サーバー側で `API_SERVER_ENABLED=true` と `API_SERVER_KEY` を設定してください。

1. アプリの設定で `Base URL` と `APIキー` を入力し、保存します。Cloudflare Accessを使う場合は `Client ID / Client Secret` も入力します。
2. `接続試験` で接続を確認します。
3. Androidの通知を許可し、アプリの「通知を有効にする」をオンにします。
4. 新しい会話を開いてメッセージを送ります。

## 利用上の注意

- バックグラウンド受信中は常駐通知が表示されます。省電力制御で通知が遅れる場合があります。強制停止や端末の再起動後は、アプリを開いて監視を再開してください。
- 通知対象は、このアプリが追跡している実行です。他のクライアントや定期実行から始まった処理は自動で監視しません。
- CLI・Discordなどが所有する会話は読み取り専用です。「分岐して続ける」で続行できます。
- 実行中は入力欄の矢印から追加指示や `/queue` を送れます。停止は隣の停止ボタンです。失敗・中断・結果不明の実行後は、キューを自動送信しません。
- 接続先URLには最終的なHTTPS URLを指定してください。認証情報・クエリ・フラグメントを含むURLは使えません。認証情報を転送しないよう、HTTPリダイレクトは追跡しません。端末内の開発サーバーには `localhost`・`127.0.0.1`・`[::1]`、Androidエミュレーターからホストには `10.0.2.2` でHTTP接続できます。
- APIキーとCloudflareの認証情報はAndroid Keystoreの鍵で暗号化します。以前の平文設定は最初の読み込み時に移行します。
- 送信内容と実行キーは通信前に暗号化して保存します。応答が届かなかった送信は、サーバーが永続的な重複防止と有効な保存期間を通知する場合に限り、同じ内容・キーで復旧します。保証できない場合は再送を止めるため、サーバーの履歴を確認してください。
- 画像送信には、`/v1/runs` の `input` に画像を含むメッセージ配列を受け付けるHermesサーバーと、画像に対応したモデルが必要です。画像のBase64変換や本文を含めた送信データは合計10 MBまでです。超過時は画像や本文を保持するため、減らして再送できます。履歴検索の対象は会話名とIDです。

## ビルド

JDK 17とAndroid SDKを用意し、次を実行します。

```sh
./gradlew :app:assembleDebug
```

Windowsでは `gradlew.bat :app:assembleDebug` を使います。APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。

開発版のアプリIDは `com.hermes.client.debug` です。配布版とは別にインストールされ、設定も分かれます。

## 配布版

配布版は `:app:assembleRelease` で作ります。署名には非公開の鍵を使い、次の環境変数で指定します。指定しない場合は検証用の未署名APKになります。

| 環境変数 | 内容 |
| --- | --- |
| `YORUBE_SIGNING_STORE_FILE` | 署名用キーストアのパス |
| `YORUBE_SIGNING_STORE_PASSWORD` | キーストアのパスワード |
| `YORUBE_SIGNING_KEY_ALIAS` | 鍵のエイリアス |
| `YORUBE_SIGNING_KEY_PASSWORD` | 鍵のパスワード |

GitHubのReleaseワークフローでは、キーストアをBase64にした `YORUBE_SIGNING_STORE_BASE64` と、上記のパスワード・エイリアスをRepository Secretsに登録します。`main` に取り込まれたコミットへ、アプリのバージョン名と一致するタグ（例: `v0.9.3`）を付けると、検証後に署名済みAPKを公開します。手動実行では、公開せずに署名済み配布物を作成できます。[公開手順](docs/PUBLISHING.md)を参照してください。

配布用の[公開証明書](legal/release-signing-certificate.pem)と[SHA-256フィンガープリント](legal/release-signing-certificate.sha256)をリポジトリで公開しています。APKの署名がこの証明書と一致すること、同梱文書がAPK内の文書と一致することを配布物の生成時に検証します。

以前配布したdebug APKと新しい配布版では署名が異なるため、上書き更新できません。以前のアプリをアンインストールしてから配布版を入れ、接続設定を再入力してください。アンインストールすると端末内のキューも消えます。開発版との併用は可能です。

## 詳細

- [対応コマンドと引数](docs/COMMANDS.md)
- [設計・API仕様](docs/DESIGN.md)
- [修正内容と検証範囲](docs/PR-AUDIT.md)

## プライバシー・ライセンス・公開準備

- [プライバシーポリシー](PRIVACY.md)
- [第三者ソフトウェアの表示と更新手順](third_party/README.md)
- [公開手順・署名・旧版からの移行](docs/PUBLISHING.md)

設定画面からプライバシーとオープンソースライセンスをオフラインで閲覧できます。本体は[MIT License](LICENSE)で公開しています。第三者ライブラリの利用条件はそれぞれの表示に従います。

依存ライセンスはAboutLibrariesでreleaseの実依存から自動収集します。`./gradlew :app:generateThirdPartyNotices` で、リポジトリとアプリ内表示に使う文書を同時生成します。通常のビルドでも再収集と一致検証を行います。内包コードの補足情報と自動検出の範囲は[更新手順](third_party/README.md)を参照してください。

## 紹介サイト

[GitHub Pages](https://milix-m.github.io/Yorube/)で日本語・英語の紹介サイトを配信します。ソースと更新方法は [site/README.md](site/README.md) にあります。
