import Link from "next/link";
import { LegalShell } from "@/components/site-chrome";
export default function Page() {
  return (
    <LegalShell locale="en" title="Page not found">
      <p>The page may have moved or does not exist.</p>
      <Link className="button primary" href="/en">
        Back to home
      </Link>
    </LegalShell>
  );
}
