# hermes_app — Hermes Agent Androidクライアント

サーバー上のHermes Agentを使うAndroidクライアントである。実行はサーバー側、端末は表示と入力と承認を受け持つ。設計は `docs/DESIGN.md` を見る。

## 構成

```
app/                   Androidクライアント (Kotlin + Compose)
  data/api/            HermesApi: REST + SSE、型、事象の解釈
  data/store/          設定の保存 (DataStore)
  data/fcm/            FCM受信と中継登録
  ui/                  一覧、会話、設定の3画面
server/fcm-relay/      完了通知の中継 (M3)
```

対応範囲: M0接続、M1会話、M2承認・steer・clarify、M3通知。

## 動かし方

1. サーバー側で `API_SERVER_ENABLED=true` と `API_SERVER_KEY` を置き、HTTPSで公開する。
2. アプリの設定画面でBase URLとAPIキーを入れる。接続試験で `health` と機能検出を確認する。
3. 会話を開き、送信する。中断はStop、実行中の一言は入力欄の `→` でsteerする。
4. 通知が要る場合は `server/fcm-relay/README.md` に従い中継を立てる。Firebaseの4項目はアプリの設定画面に入れる。`google-services.json` は要らない。

## ビルド

```sh
./gradlew :app:assembleDebug
```

CI (`.github/workflows/android.yml`) でも同じものを作る。成果物はdebug APKである。

## ブランチ

`main` ← `develop` ← 作業ブランチ。設計書はPR #1で取り込み済み。
