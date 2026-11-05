# Yorube

[Hermes Agent](https://github.com/nousresearch/hermes-agent) の非公式 Android クライアントです。サーバー上のHermesに接続し、チャットや実行状況の確認、承認・質問への回答ができます。

- 会話の履歴・検索・ピン留め
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
- 実行中に追加指示や `/queue` を送るには、「Enterで送信する」をオンにしてください。失敗・中断・結果不明の実行後は、キューを自動送信しません。

## ビルド

JDK 17とAndroid SDKを用意し、次を実行します。

```sh
./gradlew :app:assembleDebug
```

Windowsでは `gradlew.bat :app:assembleDebug` を使います。APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。

## 詳細

- [対応コマンドと引数](docs/COMMANDS.md)
- [設計・API仕様](docs/DESIGN.md)
- [修正内容と検証範囲](docs/PR-AUDIT.md)
