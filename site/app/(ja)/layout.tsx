import type { Metadata } from "next";
import { assetPath } from "@/lib/paths";
import "../globals.css";
export const metadata: Metadata = {
  metadataBase: new URL("https://milix-m.github.io"),
  title: {
    default: "Yorube — Hermes AgentのAndroidクライアント",
    template: "%s | Yorube",
  },
  description:
    "Hermes Agentの非公式Androidクライアント。チャット、画像送信、追加指示、承認、通知に対応。",
  icons: { icon: assetPath("/favicon.svg") },
  openGraph: {
    title: "Yorube — Hermes AgentのAndroidクライアント",
    description: "Hermes Agentの非公式Androidクライアント。",
    locale: "ja_JP",
    type: "website",
  },
};
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
