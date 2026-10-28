# Yorube

[Hermes Agent](https://github.com/nousresearch/hermes-agent) の非公式 Android クライアントです。

名前の **Yorube**（ヨルベ）は「寄る辺」、つまり頼りにできる場所に由来します。サーバー上のエージェントに、いつでも頼れる窓口を、という思いを込めています。

## できること

| 機能 | 内容 |
| --- | --- |
| チャット | 送信・中断・実行中の一言 (steer)・再接続つきの SSE ストリーミング受信 |
| 進捗表示 | 思考中インジケータ、ツール実行の折りたたみログ (失敗も控えめな一行表示) |
| 承認フロー | 危険コマンドの承認ダイアログ、実行中の質問への回答 |
| 履歴 | 日付セクションつきサイドバー、ピン留め、検索、削除 |
| 他面セッション | Discord 等が所有する会話は読み取り専用で開き、ワンタップで分岐 (fork) して続行 |
| 通知 | FCM 中継経由の完了通知、タップで該当会話を開く |
| 表示言語 | 日本語・English・中文・한국어＋システム既定の切替え (設定画面) |

## 必要なもの

Hermes API サーバー (`gateway/platforms/api_server.py`) を HTTPS で公開したものが必要です。推奨は Cloudflare Tunnel と Cloudflare Access です。Hermes 本体は `127.0.0.1:8642` のまま置きます。トンネルで `https://hermes.example.com` に出す構成です。サーバー側では `API_SERVER_ENABLED=true` と `API_SERVER_KEY` を設定します。Android 8.0 (API 26) 以上が必要です。

## 動かし方

1. アプリの設定画面で `Base URL` と `APIキー` を入れます
   - Cloudflare Access で保護している場合は `Client ID / Client Secret` も入れます (全要求に付与されます)
2. `接続試験` で `health` と機能検出 (`run_status` / `run_events_sse` / `run_stop` / `run_steer` / …) を確認します
3. 会話を開いて送信します。中断は ■ ボタンを使います。実行中の一言は入力欄の ↑ ボタン (steer) で送ります
4. 通知が要る場合は [`server/fcm-relay/README.md`](server/fcm-relay/README.md) に従い中継を立てます。Firebase の4項目 (Project ID / App ID / APIキー / Sender ID) は設定画面に入れます。`google-services.json` は不要です

## 構成

```
app/                   Androidクライアント (Kotlin + Jetpack Compose)
  data/api/            HermesApi: REST + SSE、型、事象の解釈
  data/store/          設定の保存 (DataStore)、言語ロケール
  data/fcm/            FCM受信と中継登録
  ui/                  一覧、会話、設定、ナビゲーション
server/fcm-relay/      完了通知の中継
```

## 他面所有の会話について

Hermes サーバーはセッションの単一所有者を強制します。これは並行実行によるセッション破壊を防ぐためです。
CLI / Discord 等が所有するセッションへの書き込みは `permission_denied` で拒否されます。
これはサーバー側の仕様で、アプリの設定では変えられません。
アプリではその会話を読み取り専用で開き、「分岐して続ける」ダイアログから自分のコピーに分岐して続けられます。
アプリから送信に成功した会話は端末内に「アプリ所有」として記録されます。その会話は他面非表示をオンにしても隠れません。

## 再接続について

通信が切れたら自動でつなぎ直し、実行中の場合は状態を確認して続きから表示します。何も進まない状態が続く場合はあきらめてエラーを出します。

## ビルド

```sh
./gradlew :app:assembleDebug
```

CI (`.github/workflows/android.yml`) でも同じものを作ります。成果物は debug APK です。
単体テストは `./gradlew :app:testDebugUnitTest`、カバレッジ検証は `./gradlew :app:jacocoCoverageVerification` です。

## 既知の制限

他面所有セッションの一覧可否は、サーバーが所有者メタデータを返さない場合、過去の `permission_denied` 記録に依存します。SSE 購読は前面のみです。バックグラウンドでは Doze で切れるため、復帰時は状態取得で補います。`unknown` 終端の再送はしません (二重実行防止)。新規会話を始めてください。
