import { languages } from "@/lib/paths";
import { LegalShell, Arrow } from "@/components/site-chrome";
export const metadata = {
  title: "サポート",
  alternates: { languages: languages("support") },
};
export default function Support() {
  return (
    <LegalShell page="support" title="サポート">
      <p>不具合の報告や機能の提案は、GitHub Issuesで受け付けています。</p>
      <a
        className="button primary"
        href="https://github.com/Milix-M/Yorube/issues"
        target="_blank"
        rel="noreferrer"
      >
        GitHub Issuesを開く <Arrow />
      </a>
      <h2>接続できないとき</h2>
      <ol>
        <li>端末からサーバーのURLに接続できるか確認します。</li>
        <li>
          APIキーと、必要な場合はCloudflare Accessの認証情報を確認します。
        </li>
        <li>設定を保存してから接続テストを実行します。</li>
        <li>
          通知やバックグラウンド動作の場合は、Androidの権限・省電力設定も確認します。
        </li>
      </ol>
      <h2>報告に添える情報</h2>
      <p>
        アプリのバージョン、Androidのバージョン、再現手順、期待した動作と実際の動作を添えると、調査しやすくなります。デバッグログを共有する場合は内容を確認してください。
      </p>
      <div className="notice-box">
        <strong>Issueは公開されます。</strong>
        <p>
          APIキー、Client
          Secret、会話内容、個人情報や内部サーバーの情報をそのまま貼り付けないでください。
        </p>
      </div>
      <h2>サーバーに関すること</h2>
      <p>
        モデルの利用料金、サーバーの保存データやログの削除、接続先固有の問題は、サーバー運営者または利用するサービスの窓口に確認してください。
      </p>
    </LegalShell>
  );
}
