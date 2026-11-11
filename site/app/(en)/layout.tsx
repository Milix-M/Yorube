import { pageMetadata } from "@/lib/metadata";
import "../globals.css";
export const metadata = pageMetadata("en");
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
