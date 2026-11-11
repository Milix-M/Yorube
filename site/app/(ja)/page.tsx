import { pageMetadata } from "@/lib/metadata";
import { HomePage } from "@/components/home-page";
export const metadata = pageMetadata("ja");
export default function Page() {
  return <HomePage locale="ja" />;
}
