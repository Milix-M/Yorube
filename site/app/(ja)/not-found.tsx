import Link from "next/link";
import { LegalShell } from "@/components/site-chrome";
export default function NotFound() {
  return (
    <LegalShell title="ページが見つかりません">
      <p>リンクが変更されたか、ページが存在しません。</p>
      <Link className="button primary" href="/">
        ホームへ戻る
      </Link>
    </LegalShell>
  );
}
