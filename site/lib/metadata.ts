import type { Metadata } from "next";
import { assetPath, languages } from "./paths";

type Locale = "ja" | "en";
type Page = "" | "licenses" | "support";

const copy = {
  ja: {
    "": {
      title: "Yorube | Hermes Agentの非公式Androidクライアント",
      description:
        "自分のHermes APIサーバーに接続して使う非公式のAndroidクライアントです。チャット、画像送信、実行中の追加指示、承認への回答、通知に対応しています。",
    },
    licenses: {
      title: "ライセンス | Yorube",
      description:
        "YorubeのMIT Licenseと、アプリで使うライブラリの著作権表示・ライセンス本文を確認できます。",
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
        "An unofficial Android client for your own Hermes API server. Chat, send images, add instructions during a run, respond to approvals and receive notifications.",
    },
    licenses: {
      title: "Licenses | Yorube",
      description:
        "Read Yorube’s MIT License and the copyright notices and license texts for libraries used by the Android app.",
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
