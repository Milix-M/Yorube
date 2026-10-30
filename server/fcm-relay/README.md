# FCM relay

Yorube のプッシュ通知を支える外付けの中継である。Hermes の run 完了を1件のFCM通知に変える。途中で承認要求（approval.request）が来たら、完了を待たずに承認通知も即時に送る。Hermes本体の改修は要らない。

## 動かし方

```sh
pip install -r requirements.txt
export HERMES_BASE_URL=https://hermes-host:8642
export HERMES_API_KEY=...
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
python relay.py  # :8090
```

任意: `RELAY_AUTH_TOKEN` を置くと `/watch` にBearer認証が要る。`PORT` で待受を変えられる。

## 仕組み

Androidはrun開始時に `POST /watch {run_id, session_id, fcm_token}` を投げる。中継は `GET /v1/runs/{id}/events` を終端まで読み、FCMデータメッセージを送る。本文は先頭200字に絞る。タップで該当会話を開く。

送るデータメッセージには `type` が付く。完了時は `type=run_finished`、承認待ちでは `type=approval`（本文はコマンド、なければ説明の先頭200字）で、同じ `request_id` には1回だけ送る。承認に答えた後の完了通知も従来どおり届く。

## 必要な手続き

持ち主の操作が要る:

1. Firebaseプロジェクトの作成
2. Androidアプリの登録 (パッケージ `com.hermes.client`)
3. サービスアカウント鍵の発行と `GOOGLE_APPLICATION_CREDENTIALS` への配置
4. アプリ側設定画面のFirebase4項目 (Project ID, App ID, API key, Sender ID) の入力
