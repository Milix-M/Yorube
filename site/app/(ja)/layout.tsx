import { pageMetadata } from "@/lib/metadata";
import "../globals.css";
export const metadata = pageMetadata("ja");
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ja">
      <body>{children}</body>
    </html>
  );
}
