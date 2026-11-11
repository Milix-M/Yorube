"use client";
import { useState } from "react";
import type { Locale } from "./site-chrome";
import { assetPath } from "@/lib/paths";
export function ScreenshotGallery({ locale }: { locale: Locale }) {
  const [screen, setScreen] = useState("chat");
  const en = locale === "en";
  const screens = [
    ["chat", en ? "Chat" : "会話"],
    ["drawer", en ? "Sidebar" : "サイドバー"],
    ["settings", en ? "Settings" : "設定"],
  ];
  const label = screens.find(([key]) => key === screen)![1];
  return (
    <div className="screenshot-gallery">
      <a
        href={assetPath(`/screenshots/${locale}-${screen}.png`)}
        target="_blank"
        rel="noreferrer"
        aria-label={
          en
            ? `Open ${label.toLowerCase()} screenshot at full size`
            : `${label}画面を原寸で開く`
        }
      >
        <img
          className="app-screenshot"
          src={assetPath(`/screenshots/${locale}-${screen}.png`)}
          width="824"
          height="1784"
          alt={
            en ? `Yorube ${label.toLowerCase()} screen` : `Yorubeの${label}画面`
          }
        />
      </a>
      <div
        className="demo-switcher"
        role="group"
        aria-label={en ? "App screenshots" : "アプリ画面"}
      >
        {screens.map(([key, text]) => (
          <button
            key={key}
            aria-pressed={screen === key}
            onClick={() => setScreen(key)}
          >
            {text}
          </button>
        ))}
      </div>
      <p className="demo-caption">
        {en
          ? "Development build · sample data"
          : "開発版の画面 · サンプルデータ"}
      </p>
    </div>
  );
}
