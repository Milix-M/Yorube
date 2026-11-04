# スラッシュコマンド

Hermesの組み込みコマンド104個を認識し、そのうち35個に操作を割り当てています。引数や動作は以下の範囲に限ります。未対応の操作はチャット本文としてエージェントへ送らず、未対応と表示します。

一覧の照合先は [Hermesのコマンド定義](https://github.com/NousResearch/hermes-agent/blob/27cbcb122ff47a1d6878cf3c390b24395f98ee14/hermes_cli/commands.py) です。操作の可否は同じ版の [APIルート](https://github.com/NousResearch/hermes-agent/blob/27cbcb122ff47a1d6878cf3c390b24395f98ee14/gateway/platforms/api_server.py) に基づいています。古いサーバーで存在しないAPIを呼んだ場合はエラーを表示します。

| コマンド | Yorubeでの動作 |
| --- | --- |
| `/help`、`/palette` | 対応する操作を表示 |
| `/commands [ページ]` | 未対応を含む一覧を20件ずつ表示 |
| `/new [名前]`、`/clear` | 新しい会話へ移動。`/reset` は `/new` の別名 |
| `/stop` | 現在の会話の実行を中断。他の会話やサーバー全体は停止しない |
| `/steer テキスト` | 実行中に指示を追加。別名は `/s` |
| `/retry` | 最後のユーザーメッセージを新しい実行として再送 |
| `/queue テキスト` | 実行中なら次のターンに保存。待機中なら即時送信 |
| `/queue list　/queue clear　/queue rm 番号` | 保存したプロンプトの一覧・全削除・削除 |
| `/queue edit 番号 テキスト　/queue move 移動元 移動先` | 保存したプロンプトの編集・並べ替え。番号は1始まり |
| `/branch [名前]` | 現在の会話を分岐して開く。別名は `/fork` |
| `/sessions` | 会話一覧とIDを表示 |
| `/resume 名前またはID` | 会話を開く。候補が複数あるときはIDの指定を求める |
| `/title [名前]` | 会話名を表示・変更 |
| `/history`、`/redraw` | 実行していない会話の履歴を再読込 |
| `/save` | 会話のテキストをAndroidの共有画面に渡す。ファイル名や形式の指定には未対応 |
| `/copy` | 最後の応答をクリップボードへコピー |
| `/prompt テキスト` | 入力欄に展開。送信はユーザーが行う。別名は `/compose` |
| `/model [モデル]` | 現在の設定と候補を表示、または次回以降のモデルを変更 |
| `/model --provider プロバイダー モデル` | プロバイダーも変更。モデル名の `/` はそのまま保持 |
| `/reasoning [none\|minimal\|low\|medium\|high\|xhigh\|max\|ultra]` | 次回以降の推論量を指定。実際の対応範囲はサーバーとモデルに依存 |
| `/fast [normal\|fast\|status]` | 次回以降の `service_tier` を `default` または `priority` に設定。`off`・`on` も使用可能 |
| `/status`、`/usage` | 会話の状態、直近の実行のトークン使用量を表示 |
| `/config`、`/version` | 接続先ホストとモデル、Yorubeのバージョンを表示。秘密情報は表示しない |
| `/skills [list]`、`/skills search 語句`、`/skills show 名前` | インストール済みスキルの一覧・検索・説明を表示。`inspect` も使用可能 |
| `/reload-skills` | サーバーからスキル一覧を再取得。サーバーの設定変更や再読込は行わない |
| `/toolsets`、`/tools [list]` | ツールセットを表示。設定変更には未対応 |
| `/bg プロンプト` | 別の会話で実行し、バックグラウンドで監視 |
| `/agents` | このアプリが監視中の実行を表示。別名は `/tasks` |
| `/approve [once\|session\|always]`、`/deny` | 現在の承認要求へ回答。サーバーが提示した選択肢のみ使用可能 |
| `/discard [実行ID]` | `unknown` の実行を、サーバーの世代番号を確認して破棄。プロンプトは再送しない |
| `/cron [list]`、`/cron show ID` | 定期実行の一覧・詳細を表示 |
| `/cron add 名前 \| スケジュール \| プロンプト` | 定期実行を追加。`create` も使用可能 |
| `/cron edit ID 名前 \| スケジュール \| プロンプト` | 定期実行を変更 |
| `/cron pause ID`、`/cron resume ID`、`/cron run ID`、`/cron remove ID` | 定期実行の一時停止・再開・即時実行・削除 |

キューは接続先と会話ごとに保存し、画面を離れても処理を続けます。先行する実行が正常完了した場合にだけ次を送信します。定期実行のスケジュール書式はサーバー側の仕様に従います。

`/compress`、`/undo`、`/yolo`、`/memory`、サーバー上のファイル操作、CLIの表示変更など、対応するAPIやアプリの操作がないものは未対応です。動的なスキル名、プラグイン独自コマンド、ユーザー定義コマンドも自動実行しません。`/export` はHermesのプロファイル出力を指すため、会話の共有である `/save` の別名にはしていません。
