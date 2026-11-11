import { languages } from "@/lib/paths";
import { HomePage } from "@/components/home-page";
export const metadata = { alternates: { languages: languages() } };
export default function Page() {
  return <HomePage locale="en" />;
}
