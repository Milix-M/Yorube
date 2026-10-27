# Hermes Client — Hermes Agent Androidクライアント

サーバー上で動く [Hermes Agent](https://github.com/NousResearch/hermes) を外出先から使うための Android クライアントです。
実行・記憶・道具はすべてサーバー側に置きます。端末は **表示と入力と承認** を片手で済ませることに集中します。
設計の詳細は [`docs/DESIGN.md`](docs/DESIGN.md) を見てください。

## できること

- **チャット**: 送信・中断・実行中の一言 (steer)・再接続つきの SSE ストリーミング受信
- **進捗表示**: 思考中インジケータ、ツール実行の折りたたみログ (失敗も控えめな一行表示)
- **承認フロー**: 危険コマンドの承認ダイアログ、実行中の質問への回答
- **履歴**: 日付セクションつきサイドバー、ピン留め、検索、削除
- **他面セッション**: Discord 等が所有する会話は読み取り専用で開き、ワンタップで分岐 (fork) して続行
- **通知**: FCM 中継経由の完了通知、タップで該当会話を開く
- **表示言語**: 日本語・English・中文・한국어＋システム既定の切替え (設定画面)

## 必要なもの

- Hermes API サーバー (`gateway/platforms/api_server.py`) を HTTPS で公開したもの
  - 推奨: Cloudflare Tunnel + Cloudflare Access。Hermes 本体は `127.0.0.1:8642` のまま置き、トンネルで `https://hermes.example.com` に出します
  - サーバー側で `API_SERVER_ENABLED=true` と `API_SERVER_KEY` を設定します
- Android 8.0 (API 26) 以上

## 動かし方

1. アプリの設定画面で **Base URL** と **APIキー** を入れます
   - Cloudflare Access で保護している場合は **Client ID / Client Secret** も入れます (全要求に付与されます)
2. **接続試験** で `health` と機能検出 (`run_status` / `run_events_sse` / `run_stop` / `run_steer` / …) を確認します
3. 会話を開いて送信します。中断は ■ ボタンを使います。実行中の一言は入力欄の ↑ ボタン (steer) で送ります
4. 通知が要る場合は [`server/fcm-relay/README.md`](server/fcm-relay/README.md) に従い中継を立てます。Firebase の4項目 (Project ID / App ID / APIキー / Sender ID) は設定画面に入れます。`google-services.json` は不要です

### 設定の補足

| 項目 | 内容 |
| --- | --- |
| 言語 | システム既定・日本語・English・中文・한국어。アプリ内表示と言語別の全メッセージに適用されます |
| 他の画面の会話も表示する | Discord 等が所有する会話を履歴に出すか。オフにすると隠れます (所有不明時は判定できた分のみ) |
| Enterで送信する | オンで Enter 送信・Shift+Enter 改行。オフ (既定) では Enter は常に改行、送信は ↑ ボタンです |

## 構成

```
app/                   Androidクライアント (Kotlin + Jetpack Compose)
  data/api/            HermesApi: REST + SSE、型、事象の解釈
  data/store/          設定の保存 (DataStore)、言語ロケール
  data/fcm/            FCM受信と中継登録
  ui/                  一覧、会話、設定、ナビゲーション
server/fcm-relay/      完了通知の中継
```

対応範囲: 接続、会話、承認・steer・clarify、通知。

## 他面所有の会話について

Hermes サーバーはセッションの単一所有者を強制します。これは並行実行によるセッション破壊を防ぐためです。
CLI / Discord 等が所有するセッションへの書き込みは `permission_denied` で拒否されます。
これはサーバー側の仕様で、アプリの設定では変えられません。
アプリではその会話を読み取り専用で開きます。**分岐して続ける** ダイアログから自分のコピーに分岐して続けられます。
アプリから送信に成功した会話は端末内に「アプリ所有」として記録されます。他面非表示をオンにしても隠れません。

## 再接続について

切断したら状態を取り直します。未終端なら付け直します (輸送用の一時置き場は5分です)。
進捗イベントが届くたびに再接続予算が回復します。そのため、長時間実行の断続的な切断は自動で回復します。

## ビルド

```sh
./gradlew :app:assembleDebug
```

CI (`.github/workflows/android.yml`) でも同じものを作ります。成果物は debug APK です。
単体テストは `./gradlew :app:testDebugUnitTest`、カバレッジ検証は `./gradlew :app:jacocoCoverageVerification` です。

## ブランチ

`main` ← `develop` ← 作業ブランチ。設計書は PR #1 で取り込み済み。

## 既知の制限

- 他面所有セッションの一覧可否は、サーバーが所有者メタデータを返さない場合、過去の `permission_denied` 記録に依存します
- SSE 購読は前面のみです。裏では Doze で切れるため、復帰時は状態取得で補います
- `unknown` 終端の再送はしません (二重実行防止)。新規会話を始めてください
