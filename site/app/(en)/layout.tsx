import type { Metadata } from "next";
import { assetPath } from "@/lib/paths";
import "../globals.css";
export const metadata: Metadata = {
  metadataBase: new URL("https://milix-m.github.io"),
  title: {
    default: "Yorube — Android client for Hermes Agent",
    template: "%s | Yorube",
  },
  description:
    "An unofficial Android client for Hermes Agent. Chat, images, instructions, approvals and notifications.",
  icons: { icon: assetPath("/favicon.svg") },
  openGraph: {
    title: "Yorube — Android client for Hermes Agent",
    description: "An unofficial Android client for Hermes Agent.",
    locale: "en_US",
    type: "website",
  },
};
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
