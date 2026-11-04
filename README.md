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
| 他インターフェイスセッション | Discord 等が所有する会話は読み取り専用で開き、ワンタップで分岐 (fork) して続行 |
| 通知 | 完了・承認・質問を端末内で通知。タップで該当会話を開く。FCM・中継サーバーは不要 |
| バックグラウンド | フォアグラウンドサービスで複数の実行を監視し、切断後は状態取得とSSEの再接続を行う |
| コマンド | 104個を認識し、35個にAPIまたは端末内の操作を割り当て。[対応範囲](docs/COMMANDS.md) |
| 表示言語 | 日本語・English・中文・한국어＋システム既定の切替え (設定画面) |

## 必要なもの

Hermes API サーバー (`gateway/platforms/api_server.py`) を HTTPS で公開したものが必要です。推奨は Cloudflare Tunnel と Cloudflare Access です。Hermes 本体は `127.0.0.1:8642` のまま置きます。トンネルで `https://hermes.example.com` に出す構成です。サーバー側では `API_SERVER_ENABLED=true` と `API_SERVER_KEY` を設定します。Android 8.0 (API 26) 以上が必要です。

## 動かし方

1. アプリの設定画面で `Base URL` と `APIキー` を入れます
   - Cloudflare Access で保護している場合は `Client ID / Client Secret` も入れます
2. `接続試験` で `health` と機能検出 (`run_status` / `run_events_sse` / `run_stop` / `run_steer` / …) を確認します
3. 会話を開いて送信します。中断は ■ ボタンを使います。実行中の一言は入力欄の ↑ ボタン (steer) で送ります
4. Androidの通知を許可し、設定画面の通知をオンにします。Firebaseの登録や外部の通知サービスは不要です

## 構成

```
app/                   Androidクライアント (Kotlin + Jetpack Compose)
  data/api/            HermesApi: REST + SSE、型、事象の解釈
  data/store/          設定の保存 (DataStore)、言語ロケール
  data/stream/         実行の監視、再接続、バックグラウンドサービス
  data/notifications/  端末内での通知
  ui/                  一覧、会話、設定、ナビゲーション
```

## 他インターフェイスから開始した会話について

Hermes サーバーはセッションの単一所有者を強制します。これは並行実行によるセッション破壊を防ぐためです。
CLI / Discord 等が所有するセッションへの書き込みは `permission_denied` で拒否されます。
これはサーバー側の仕様で、アプリの設定では変えられません。
アプリではその会話を読み取り専用で開き、「分岐して続ける」ダイアログから自分のコピーに分岐して続けられます。
アプリから送信に成功した会話は端末内に「アプリ所有」として記録されます。その会話は他インターフェイス非表示をオンにしても隠れません。

## ビルド

```sh
./gradlew :app:assembleDebug
```

CI (`.github/workflows/android.yml`) でも同じものを作ります。成果物は debug APK です。
単体テストは `./gradlew :app:testDebugUnitTest`、カバレッジ検証は `./gradlew :app:jacocoCoverageVerification` です。

Linux ARM64 の単体テストは、Robolectric 4.17、Conscrypt 2.9.0、`nativeruntime-dist-compat` 1.0.20 を使います。
Robolectric 4.17 の標準ローダーは Linux ARM64 を未対応として扱うため、テスト用の `LinuxArm64NativeRuntimeLoader` を SPI で登録し、公式配布の ARM64 ライブラリ・フォント・ICU データを読み込みます。対応範囲は API 26〜34 です。他のホストでは標準ローダーを使います。
テストを省略したりネイティブ描画を無効化したりする設定は不要です。Linux ARM64 対応のテストランナーへ更新する際は、このローダーも見直してください。

## 既知の制限

他インターフェイスセッションの一覧可否は、サーバーが所有者メタデータを返さない場合、過去の `permission_denied` 記録に依存します。

バックグラウンドでのSSE受信には常駐通知が表示されます。ネットワーク切断後は再接続し、アプリのプロセスが再作成された場合は保存した実行IDから監視を再開します。端末の強制停止中や再起動後は、アプリを開くまで監視を再開できません。Dozeやメーカー独自の省電力制御で通信が止まっている間は通知も遅れます。必要に応じてAndroidの設定からYorubeのバッテリー制限を見直してください。[AndroidのDoze仕様](https://developer.android.com/training/monitoring-device-state/doze-standby)

現在のtargetSdkは34です。将来35以上へ変更する場合は、`dataSync` サービスの24時間あたり6時間の制限と `onTimeout` 対応を見直す必要があります。[フォアグラウンドサービスの時間制限](https://developer.android.com/develop/background-work/services/fgs/timeout)

SSEの再送バッファから消えた途中経過は復元できません。完了時はサーバーの最終出力に合わせます。`unknown` と判定された実行や失敗・中断した実行のあとに、キューのプロンプトを自動送信することはありません。結果不明の実行を確認したうえで `/discard` を使うか、新しい会話を始めてください。

通知はこの端末が監視している実行が対象です。他のクライアントや定期実行から始まった処理を、自動的に発見して通知する機能はありません。旧バージョンのFirebase設定は読み込まれても通信には使いません。
