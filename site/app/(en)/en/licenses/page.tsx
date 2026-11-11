import { languages } from "@/lib/paths";
import { LicensesPage } from "@/components/licenses-page";
export const metadata = {
  title: "Licenses",
  alternates: { languages: languages("licenses") },
};
export default function Page() {
  return <LicensesPage locale="en" />;
}
