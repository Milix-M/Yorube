import { Arrow, Header, Footer, type Locale } from "./site-chrome";
import { ScreenshotGallery } from "./screenshot-gallery";
import { assetPath } from "@/lib/paths";

const content = {
  ja: {
    lead: "Hermes Agentの非公式Androidクライアントです。",
    description:
      "自分のHermes APIサーバーに接続してAndroidから会話を続けられます。画像の添付、実行中の追加指示、承認への回答に対応しています。",
    requires: "Android 8.0以降 · Hermes APIサーバーとAPIキーが必要です",
    download: "APKをダウンロード",
    features: "機能",
    stories: [
      {
        screen: "images",
        title: "画像を添えて相談する",
        text: "写真やスクリーンショットを1枚あたり5 MBまで、最大3枚添付できます。送信した画像は会話内に表示され、タップすると拡大できます。",
        detail: "画像を扱えるHermesサーバーとモデルが必要です。",
      },
      {
        screen: "running",
        title: "実行中に指示を追加する",
        text: "回答を待つ間に追加の指示を送れます。次のメッセージを送信待ちにしたり、実行を停止したりすることもできます。",
        detail: "承認依頼や追加の質問にも会話画面から回答できます。",
      },
    ],
    cards: [
      [
        "履歴・検索・ピン留め",
        "サイドバーから会話を検索し、よく使う会話をピン留めできます。新しい返信がある会話には未読の印が付きます。",
      ],
      [
        "Markdown・コード表示",
        "見出しやリスト、コードを含む回答を表示します。コードブロックはコピーできます。",
      ],
      [
        "会話ごとの通知",
        "完了や承認待ちを会話タイトル付きで通知します。アプリで表示中の会話の通知を抑え、ほかの会話の更新を知らせます。",
      ],
      [
        "接続・表示設定",
        "サーバーのURLとAPIキーを保存し、接続をテストできます。Cloudflare Accessの認証、日本語・英語などの表示言語にも対応しています。",
      ],
    ],
    commands: "会話から使えるコマンド",
    commandRows: [
      ["/queue", "次のメッセージを送信待ちにする"],
      ["/title", "会話のタイトルを変更する"],
      ["/model", "使用するモデルを変更する"],
    ],
    setup: "使いはじめ方",
    steps: [
      [
        "サーバーを用意する",
        "Hermes APIサーバーを起動し、端末から接続できるURLとAPIキーを確認します。",
      ],
      [
        "APKをインストールする",
        "GitHub ReleasesからAPKをダウンロードしてAndroid端末にインストールします。ソースからのビルドもできます。",
      ],
      [
        "接続設定をする",
        "設定画面でURLとAPIキーを保存し、接続テストを実行します。",
      ],
    ],
    docs: "Hermes Agentのドキュメント ↗",
    guide: "ビルド手順 ↗",
    imageAlt: "Yorubeの画像添付画面",
    runningAlt: "Yorubeの実行中の会話画面",
    caption: "開発版の画面 · サンプルデータ",
  },
  en: {
    lead: "An unofficial Android client for Hermes Agent.",
    description:
      "Connect to your own Hermes API server and continue conversations from Android. Attach images, add instructions during a run and respond to approvals.",
    requires: "Android 8.0+ · Requires a Hermes API server and API key",
    download: "Download APK",
    features: "Features",
    stories: [
      {
        screen: "images",
        title: "Add images to your conversation",
        text: "Attach up to three photos or screenshots, up to 5 MB each. Sent images appear in the conversation; tap one to enlarge it.",
        detail: "Requires a Hermes server and model that support images.",
      },
      {
        screen: "running",
        title: "Give instructions during a run",
        text: "Send another instruction while waiting for a response. Queue your next message, or stop the current run.",
        detail:
          "Respond to approval requests and follow-up questions directly in the conversation.",
      },
    ],
    cards: [
      [
        "History, search and pins",
        "Find conversations in the sidebar and pin the ones you use often. An unread indicator marks conversations with new replies.",
      ],
      [
        "Markdown and code",
        "Read responses with headings, lists and formatted code. Copy code blocks with a tap.",
      ],
      [
        "Conversation notifications",
        "See the conversation title when a run completes or needs approval. Notifications are quiet for the conversation you have open, while other conversations can still notify you.",
      ],
      [
        "Connection and display settings",
        "Save your server URL and API key, then test the connection. Cloudflare Access authentication and display languages including Japanese and English are supported.",
      ],
    ],
    commands: "Commands in your conversation",
    commandRows: [
      ["/queue", "Queue the next message"],
      ["/title", "Change the conversation title"],
      ["/model", "Change the model"],
    ],
    setup: "Setup",
    steps: [
      [
        "Prepare your server",
        "Start a Hermes API server and check its URL and API key. Your Android device must be able to reach it.",
      ],
      [
        "Install the APK",
        "Download the APK from GitHub Releases and install it on your Android device. You can also build it from source.",
      ],
      [
        "Configure the connection",
        "Save the server URL and API key in Settings, then run the connection test.",
      ],
    ],
    docs: "Hermes Agent documentation ↗",
    guide: "Build instructions ↗",
    imageAlt: "An attached image in a Yorube conversation",
    runningAlt: "A running conversation in Yorube",
    caption: "Development build · sample data",
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
                href="https://github.com/Milix-M/Yorube/releases"
                target="_blank"
                rel="noreferrer"
              >
                {c.download} <Arrow />
              </a>
              <a
                className="text-link"
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
          <div className="feature-stories">
            {c.stories.map((story, index) => (
              <article className="feature-story" key={story.screen}>
                <div className="story-copy">
                  <h3>{story.title}</h3>
                  <p>{story.text}</p>
                  <p>{story.detail}</p>
                </div>
                <figure>
                  <a
                    href={assetPath(
                      `/screenshots/${locale}-${story.screen}.png`,
                    )}
                    target="_blank"
                    rel="noreferrer"
                  >
                    <img
                      src={assetPath(
                        `/screenshots/${locale}-${story.screen}.png`,
                      )}
                      width="720"
                      height="1280"
                      alt={index === 0 ? c.imageAlt : c.runningAlt}
                      loading="lazy"
                    />
                  </a>
                  <figcaption>{c.caption}</figcaption>
                </figure>
              </article>
            ))}
          </div>
          <div className="feature-grid">
            {c.cards.map(([title, text]) => (
              <article className="feature" key={title}>
                <h3>{title}</h3>
                <p>{text}</p>
              </article>
            ))}
          </div>
          <div className="commands">
            <h3>{c.commands}</h3>
            <dl>
              {c.commandRows.map(([command, text]) => (
                <div key={command}>
                  <dt>
                    <code>{command}</code>
                  </dt>
                  <dd>{text}</dd>
                </div>
              ))}
            </dl>
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
