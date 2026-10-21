# FCM relay (M3)

Hermesのrun完了を1件のFCM通知に変える外付けの中継である。Hermes本体の改修は要らない。

## 動かし方

```sh
pip install -r requirements.txt
export HERMES_BASE_URL=https://hermes-host:8642
export HERMES_API_KEY=...
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
python relay.py  # :8090
```

任意: `RELAY_AUTH_TOKEN` を置くと `/watch` にBearer認証が要る。`PORT` で待受変更。

## 仕組み

Androidがrun開始時に `POST /watch {run_id, session_id, fcm_token}` を投げる。中継は `GET /v1/runs/{id}/events` を終端まで読み、FCMデータメッセージを1件送る。本文は先頭200字、タップで該当会話を開く。

## 必要な手続き (持ち主の操作が要る)

1. Firebaseプロジェクトの作成
2. Androidアプリの登録 (パッケージ `com.hermes.client`)
3. サービスアカウント鍵の発行と `GOOGLE_APPLICATION_CREDENTIALS` への配置
4. アプリ側設定画面のFirebase4項目 (Project ID, App ID, API key, Sender ID) の入力
