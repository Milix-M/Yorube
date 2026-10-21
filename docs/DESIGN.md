# Hermes Agent Androidクライアント 設計書

- 版: 1.0
- 日付: 2026-10-21
- 前提: サーバーは別に用意する。Androidはクライアントである。

## 1. 背景

Hermes Agentはサーバー上で動く自律エージェントである。本体はNousResearch製で、MIT公開されている。CLIとGatewayとAPI Serverを持つ。Android公式対応はTermux向けAPTのみで、現行版は壊れている。背景実行もDozeで落とされる。そこで端末内実行は選ばない。サーバーに置いたHermesへAndroidからつなぐ方式を選ぶ。

## 2. 目的

外出先からHermesを使えるようにする。チャットの送受信ができる。実行の様子が見える。承認を求められたら答えられる。これを片手で済ませることが目的である。

## 3. 非目的

次は含めない。端末内の推論、常駐Gateway、Termuxの内包、Desktop会話への直書き、FCM中継の初版同梱である。通知中継はM3に回す。

## 4. 全体構成

```
Androidアプリ ── HTTPS/SSE ── Hermes API Server (/v1/*, /api/*)
                                  └── (M3) 完了検知 → FCM中継 → Android通知
```

* 実行、道具、記憶、cronはサーバー側に置く。
* Androidは表示と入力と承認を受け持つ。
* APIは `runs` 系に寄せる。二重実装はしない。

## 5. 前提条件

* 対象は `gateway/platforms/api_server.py` である。
* サーバー側で `API_SERVER_ENABLED=true` とする。`API_SERVER_KEY` を `~/.hermes/.env` に置く。
* 公開はHTTPSで行う。Tailscaleかリバースプロキシを使う。`127.0.0.1:8642` の直晒しはしない。
* 接続時は `GET /health` と `GET /v1/capabilities` で対応を確認する。

## 6. API対応表

| 用途 | 呼び先 | 内容 |
| --- | --- | --- |
| 生存確認 | `GET /health` | `{status: ok}` を見る |
| 機能検出 | `GET /v1/capabilities` | runs、承認、steerの有無を見る |
| モデル一覧 | `GET /v1/models` | 互換用の最小一覧である |
| 選択肢 | `GET /api/model/options` | 価格と対応表を見る。`?refresh=1` で更新する |
| 技能と道具 | `GET /v1/skills`、`GET /v1/toolsets` | 表示用である |
| 開始 | `POST /v1/runs` | 入力と継続指定を送る。`run_id` が返る |
| 状態 | `GET /v1/runs/{id}` | 状態、出力、利用量、実行組を見る |
| 購読 | `GET /v1/runs/{id}/events` | SSEで進捗を受ける |
| 承認 | `POST /v1/runs/{id}/approval` | 要求に応じた回答を送る |
| 補足 | `POST /v1/runs/{id}/steer` | 実行中に文を足す |
| 中断 | `POST /v1/runs/{id}/stop` | 停止を求める |
| 釈明 | `POST /v1/runs/{id}/clarify` | 質問に答える |
| 不明の確定 | `POST /v1/runs/{id}/resolve-unknown` | 再起動時のみ使う |
| 会話 | `GET /api/sessions`、`GET /api/sessions/{id}/messages` | 一覧と履歴の表示に使う |

認証はすべて `Authorization: Bearer $API_SERVER_KEY` である。

## 7. 実行の進め方

### 7.1 開始

`POST /v1/runs` に次を送る。`input` は必須である。`session_id` は継続時に送る。新規は空けて、返った値を使う。以降はその値を送り続ける。

```json
{
  "input": "今週の変更を要約して",
  "session_id": "api-xxxx",
  "model": "hermes-agent",
  "provider": "openrouter"
}
```

応答は `202` である。`{run_id, status}` が返る。`Idempotency-Key` は毎回別のUUIDにする。同じ鍵の使い回しはしない。

### 7.2 状態

`GET /v1/runs/{id}` の `status` は次を取る。`queued`、`running`、`waiting_for_approval`、`completed`、`failed`、`cancelled`、`interrupted`、`unknown` である。`output` が最終文である。`usage` が利用量である。`runtime` が実際に使われた組である。請求表示には `runtime` を使う。

### 7.3 継続の決まり

* 主手段は `session_id` である。新規は空欄にし、返値を保持する。
* `X-Hermes-Session-Id` は写しの指定に使う。`X-Hermes-Session-Key` は長期記憶の範囲指定に使う。複数人では後者を人ごとに分ける。
* 圧縮で世代が進んだら古いIDは使わない。状態応答の `session_id` を正とする。
* `previous_response_id` の連鎖も使える。ただし承認との相性は `session_id` が良い。基本は `session_id` とする。

## 8. ストリーミング

`GET /v1/runs/{id}/events` をSSEで受ける。10秒ごとにkeepaliveの注釈行が来る。`:` で始まる行は捨てる。

| 事象 | 扱い |
| --- | --- |
| `message.delta` | 応答文の断片として追記する |
| `message.interim` | 途中経過として薄く出す。`already_streamed` が真なら重ねない |
| `tool.started`、`tool.completed` | 道具の開始と終了として出す。`preview` は短い |
| `subagent.start`、`subagent.complete` | 委譲の開始と終了として出す |
| `approval.request` | 承認ダイアログを開く。`request_id` と世代を保持する |
| `run.completed`、`run.failed`、`run.cancelled`、`run.interrupted` | 終端として確定表示する。`pending_steer` があれば次の入力に回す |

切断したら状態を取り直す。未終端なら付け直す。輸送用の一時置き場は5分で消える。長期離脱は状態取得で補う。終端の判定は終端事象で行う。`finish_reason` での代用はしない。

## 9. 承認フロー

1. `approval.request` を受けたらダイアログを開く。
2. コマンド表示は秘匿整形済みである。そのまま出す。
3. `POST /v1/runs/{id}/approval` に `{request_id, execution_generation, choice}` を送る。`choice` は `once`、`session`、`always`、`deny` のいずれかである。
4. 世代ずれで `409` になったら状態を取り直す。最新の `pending_controls` に従う。
5. 回答後は購読を続ける。承認でrunを作り直さない。

## 10. 画面設計

### 10.1 会話一覧

`GET /api/sessions` を出す。新規開始ボタンを持つ。削除と分岐はM2以降で足す。

### 10.2 チャット

本文、入力欄、送信、steer、stopを持つ。モデル名を表示する。実行中は進捗を出す。承認時はダイアログを重ねる。終端時は確定表示にする。

### 10.3 設定

BaseURL、APIキー、モデルを持つ。接続試験と再取得のボタンを置く。失効時はここに戻す。

## 11. 状態遷移

`idle` から `started` に進む。次に `running` になる。`waiting_for_approval` では承認を優先する。終端は `completed`、`failed`、`cancelled` のいずれかである。`unknown` では再送しない。確定手順か新規会話に回す。

## 12. 認証と保存

* APIキーは暗号化領域に置く。平文ファイルに置かない。ログに出さない。
* Cloudflare Accessを使う場合はClient IDとClient Secretも同じ場所に置く。全要求に `CF-Access-Client-Id` と `CF-Access-Client-Secret` を付ける。HermesのAPI鍵認証はその内側で従来どおり行う。
* BaseURLは `https://` のみ許す。例外はデバッグビルドに限る。
* 失効時は自動再試行をしない。設定画面に誘導する。

## 13. エラー処理

| 状態 | 扱い |
| --- | --- |
| `401` | 鍵の誤りとして設定に誘導する |
| `404 run_not_found` | 一覧に戻す |
| `409 admission_conflict`、`409 idempotency_key_conflict` | 再送せず状態を取り直す |
| `409 unknown_execution` | 確定手順の対象か見る。対象外なら新規会話を促す |
| `503 runtime_draining`、`503 storage_unavailable` | 新規を作らず、状態取得のみ続ける |
| SSE切断 | 状態取得を行う。終端なら確定し、未終端なら付け直す |

## 14. モデル指定

`model`、`provider`、`model_options` を送る。`model` 単独は無視される場合がある。確実に切り替える場合は `provider` を添える。応答の `runtime` を正とする。

## 15. 通知 (M3)

Hermes単体にFCM送付はない。方式は中継である。サーバー側で完了を受け、Firebase Admin SDKで投げる。中継はHermes本体を改修せず、完了検知の外付けで足す。Android側はSDK導入、トークン登録、通知チャネル、タップ遷移を用意する。本文全文は載せない。会話IDと件名に留める。M1ではSSEのみとする。

FCMはFirebase Cloud Messagingの略である。GoogleのAndroid向け通知の仕組みである。アプリが閉じていてもサーバー側から起こせる。常駐接続より電池に優しい。

## 16. 技術選定

* 言語はKotlin、UIはJetpack Compose、通信はOkHttp、並行処理はCoroutinesとFlow、保存はDataStore、画像はCoilである。
* 対応はAndroid 8.0以上、arm64を目安にする。
* 常駐接続はしない。前面での購読と切断時の状態取得で持たせる。

## 17. 工程

* M0 接続基盤: 設定、試験、機能検出、モデル選択
* M1 会話: 開始、表示、中断、再接続、履歴
* M2 操作: 承認、steer、clarify、誤り整理
* M3 通知: 中継、登録、タップ遷移

## 18. リスクと制約

* Dozeで裏の接続は切れる。切断復帰を前提に作る。
* 他面の所有物は触れない。CLIやDesktopの会話への直書きは `409` になる。共有が必要ならGateway WS側の仕事であり、本設計の範囲外である。
* 複数人は分離が要る。`X-Hermes-Session-Key` なしに共用しない。
* `unknown` の再送はしない。二重実行になる。

## 19. 受け入れ条件

* M1: 送信から終端表示まで通る。付け直しで重複実行しない。stopが `cancelled` で終わる。
* M2: 承認要求に答えて再開する。世代ずれで壊れない。
* M3: 閉じた状態で通知が届く。タップで該当会話を開ける。
