import { Arrow, Header, Footer, type Locale } from "./site-chrome";
import { ScreenshotGallery } from "./screenshot-gallery";
const content = {
  ja: {
    lead: "Hermes Agentの非公式Androidクライアント。",
    description:
      "自分のHermes APIサーバーに接続して、チャットや画像送信、実行中の追加指示、承認への回答ができます。",
    requires: "Android 8.0以降 · Hermes APIサーバーとAPIキーが必要です",
    features: "機能",
    cards: [
      [
        "チャット・画像添付",
        "Markdown表示と会話履歴に対応。画像は1枚5 MB、最大3枚まで添付できます。",
      ],
      [
        "追加指示・送信予約",
        "実行中に追加の指示を送ったり、次のメッセージをキューに登録したりできます。",
      ],
      [
        "承認・通知",
        "承認依頼や追加の質問にアプリから回答。実行完了や確認待ちを通知で受け取れます。",
      ],
    ],
    setup: "使いはじめ方",
    steps: [
      [
        "サーバーを用意する",
        "Hermes APIサーバーを起動し、端末から接続できるURLとAPIキーを確認します。",
      ],
      [
        "APKをインストールする",
        "GitHubの手順に従ってAPKをビルドし、Android端末にインストールします。",
      ],
      [
        "接続設定をする",
        "設定画面でURLとAPIキーを保存し、接続テストを実行します。",
      ],
    ],
    docs: "Hermes Agentのドキュメント ↗",
    guide: "ビルド手順 ↗",
  },
  en: {
    lead: "An unofficial Android client for Hermes Agent.",
    description:
      "Connect to your own Hermes API server to chat, send images, add instructions during a run and respond to approvals.",
    requires: "Android 8.0+ · Requires a Hermes API server and API key",
    features: "Features",
    cards: [
      [
        "Chat and images",
        "Read Markdown responses and revisit conversations. Attach up to three images, up to 5 MB each.",
      ],
      [
        "Instructions and queue",
        "Send additional instructions during a run, or queue the next message.",
      ],
      [
        "Approvals and notifications",
        "Respond to approvals and follow-up questions. Get notifications when a run completes or needs your input.",
      ],
    ],
    setup: "Setup",
    steps: [
      [
        "Prepare your server",
        "Start a Hermes API server and check its URL and API key. Your Android device must be able to reach it.",
      ],
      [
        "Install the APK",
        "Build the APK using the instructions on GitHub, then install it on your Android device.",
      ],
      [
        "Configure the connection",
        "Save the server URL and API key in Settings, then run the connection test.",
      ],
    ],
    docs: "Hermes Agent documentation ↗",
    guide: "Build instructions ↗",
  },
};
export function HomePage({ locale }: { locale: Locale }) {
  const c = content[locale];
  return (
    <>
      <Header locale={locale} />
      <main id="main">
        <section className="hero wrap">
          <div className="hero-copy">
            <h1>Yorube</h1>
            <p className="hero-lead">{c.lead}</p>
            <p className="hero-description">{c.description}</p>
            <div className="hero-actions">
              <a
                className="button primary"
                href="https://github.com/Milix-M/Yorube/tree/develop"
                target="_blank"
                rel="noreferrer"
              >
                GitHub <Arrow />
              </a>
            </div>
            <p className="requirements">{c.requires}</p>
          </div>
          <ScreenshotGallery locale={locale} />
        </section>
        <section className="feature-section wrap" id="features">
          <div className="section-heading">
            <h2>{c.features}</h2>
          </div>
          <div className="feature-grid">
            {c.cards.map(([title, text]) => (
              <article className="feature" key={title}>
                <h3>{title}</h3>
                <p>{text}</p>
              </article>
            ))}
          </div>
        </section>
        <section className="start-section wrap" id="start">
          <div className="section-heading">
            <h2>{c.setup}</h2>
          </div>
          <ol className="steps">
            {c.steps.map(([title, text], index) => (
              <li key={title}>
                <span>0{index + 1}</span>
                <h3>{title}</h3>
                <p>{text}</p>
                {index === 0 && (
                  <a
                    href="https://hermes-agent.nousresearch.com/docs/"
                    target="_blank"
                    rel="noreferrer"
                  >
                    {c.docs}
                  </a>
                )}
                {index === 1 && (
                  <a
                    href="https://github.com/Milix-M/Yorube/blob/develop/README.md"
                    target="_blank"
                    rel="noreferrer"
                  >
                    {c.guide}
                  </a>
                )}
              </li>
            ))}
          </ol>
        </section>
      </main>
      <Footer locale={locale} />
    </>
  );
}
