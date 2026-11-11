import { pageMetadata } from "@/lib/metadata";
import { HomePage } from "@/components/home-page";
export const metadata = pageMetadata("en");
export default function Page() {
  return <HomePage locale="en" />;
}
