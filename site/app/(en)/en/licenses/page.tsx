import { pageMetadata } from "@/lib/metadata";
import { LicensesPage } from "@/components/licenses-page";
export const metadata = pageMetadata("en", "licenses");
export default function Page() {
  return <LicensesPage locale="en" />;
}
