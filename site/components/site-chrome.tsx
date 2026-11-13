import Image from "next/image";
import Link from "next/link";
import { assetPath } from "@/lib/paths";
export type Locale = "ja" | "en";
export const route = (locale: Locale, path = "") =>
  (locale === "en" ? "/en" : "") + (path ? "/" + path + "/" : "/");
export function Arrow() {
  return (
    <svg
      width="19"
      height="19"
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
    >
      <path
        d="M4 12h15m-6-6 6 6-6 6"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
export function Brand({ locale = "ja" }: { locale?: Locale }) {
  return (
    <Link
      href={route(locale)}
      className="brand"
      aria-label={locale === "ja" ? "Yorube ホーム" : "Yorube home"}
    >
      <Image
        unoptimized
        src={assetPath("/favicon.svg")}
        width={33}
        height={33}
        alt=""
      />
      <span>Yorube</span>
    </Link>
  );
}
export function Header({
  locale = "ja",
  page = "",
}: {
  locale?: Locale;
  page?: string;
}) {
  const en = locale === "en";
  return (
    <>
      <a href="#main" className="skip-link">
        {en ? "Skip to content" : "本文へ移動"}
      </a>
      <header className="site-header wrap">
        <Brand locale={locale} />
        <nav aria-label={en ? "Main navigation" : "メインナビゲーション"}>
          <Link href={route(locale) + "#features"}>
            {en ? "Features" : "機能"}
          </Link>
          <Link href={route(locale) + "#start"}>
            {en ? "Setup" : "使いはじめ方"}
          </Link>
          <a
            className="language-link"
            href={assetPath(route(en ? "ja" : "en", page))}
            hrefLang={en ? "ja" : "en"}
            lang={en ? "ja" : "en"}
          >
            {en ? "日本語" : "English"}
          </a>
          <a
            className="nav-github"
            href="https://github.com/Milix-M/Yorube/tree/develop"
            target="_blank"
            rel="noreferrer"
          >
            GitHub ↗
          </a>
        </nav>
      </header>
    </>
  );
}
export function Footer({ locale = "ja" }: { locale?: Locale }) {
  const en = locale === "en";
  return (
    <footer className="site-footer wrap">
      <div>
        <Brand locale={locale} />
        <small>© 2026 Milix-M</small>
      </div>
      <nav aria-label={en ? "Footer navigation" : "フッターナビゲーション"}>
        {[
          ["licenses", en ? "Licenses" : "ライセンス"],
          ["support", en ? "Support" : "サポート"],
        ].map(([page, label]) => (
          <Link key={page} href={route(locale, page)}>
            {label}
          </Link>
        ))}
      </nav>
      <p className="unaffiliated">
        {en
          ? "Yorube is an unofficial client and is not affiliated with Nous Research. Third-party names belong to their respective owners."
          : "YorubeはNous Researchの公式製品ではありません。Hermes Agentおよび第三者の名称はそれぞれの権利者に帰属します。"}
      </p>
    </footer>
  );
}
export function LegalShell({
  title,
  children,
  locale = "ja",
  page = "",
}: {
  title: string;
  children: React.ReactNode;
  locale?: Locale;
  page?: string;
}) {
  return (
    <>
      <Header locale={locale} page={page} />
      <main id="main" className="legal wrap">
        <Link href={route(locale)} className="back-link">
          ← Yorube
        </Link>
        <h1>{title}</h1>
        {children}
      </main>
      <Footer locale={locale} />
    </>
  );
}
