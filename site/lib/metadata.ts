import type { Metadata } from "next";
import { assetPath, languages } from "./paths";

type Locale = "ja" | "en";
type Page = "" | "support";

const copy = {
  ja: {
    "": {
      title: "Yorube | Hermes Agentの非公式Androidクライアント",
      description:
        "自分のHermes APIサーバーに接続する非公式Androidクライアントです。画像付きの会話、実行中の追加指示、送信待ち、承認への回答、履歴検索、未読表示、会話ごとの通知に対応しています。",
    },
    support: {
      title: "サポート | Yorube",
      description:
        "不具合の報告や機能の提案はGitHub Issuesで受け付けています。接続できないときの確認手順と報告に添える情報を案内しています。",
    },
  },
  en: {
    "": {
      title: "Yorube | Unofficial Android client for Hermes Agent",
      description:
        "An unofficial Android client for your own Hermes API server. Chat with images, steer or queue messages, respond to approvals, search history and see unread replies and conversation notifications.",
    },
    support: {
      title: "Support | Yorube",
      description:
        "Report bugs and suggest features through GitHub Issues. Find connection troubleshooting steps and the information to include in a report.",
    },
  },
} satisfies Record<
  Locale,
  Record<Page, { title: string; description: string }>
>;

export function pageMetadata(locale: Locale, page: Page = ""): Metadata {
  const { title, description } = copy[locale][page];
  const url = languages(page)[locale];
  return {
    metadataBase: new URL("https://milix-m.github.io"),
    title,
    description,
    icons: { icon: assetPath("/favicon.svg") },
    alternates: { canonical: url, languages: languages(page) },
    openGraph: {
      title,
      description,
      url,
      locale: locale === "ja" ? "ja_JP" : "en_US",
      type: "website",
    },
    twitter: { card: "summary", title, description },
  };
}
