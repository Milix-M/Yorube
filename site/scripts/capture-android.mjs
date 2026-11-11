import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";
import { spawnSync, execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
const app = path.resolve(process.argv[2] || "");
if (!process.argv[2] || !fs.existsSync(path.join(app, "app/build.gradle.kts")))
  throw Error(
    "Usage: node scripts/capture-android.mjs /path/to/Yorube [Gradle flags]",
  );
const site = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const temporary = path.join(
  app,
  "app/src/test/java/com/hermes/client/ui/SiteScreenshotCaptureTest.kt",
);
if (fs.existsSync(temporary))
  throw Error(
    "Capture test already exists; preserve it before running this helper.",
  );
const temp = fs.mkdtempSync(path.join(os.tmpdir(), "yorube-capture-"));
const init = path.join(temp, "capture.init.gradle");
const output = path.join(site, "public/screenshots");
fs.writeFileSync(
  init,
  'allprojects { tasks.withType(Test).configureEach { systemProperty "yorube.capture.output", ' +
    JSON.stringify(output) +
    " } }\n",
);
try {
  fs.copyFileSync(
    path.join(site, "scripts/android-capture/SiteScreenshotCaptureTest.kt"),
    temporary,
  );
  const result = spawnSync(
    path.join(app, "gradlew"),
    [
      ":app:testDebugUnitTest",
      "--tests",
      "com.hermes.client.ui.SiteScreenshotCaptureTest",
      "-I",
      init,
      "--no-daemon",
      "--console=plain",
      ...process.argv.slice(3),
    ],
    { cwd: app, stdio: "inherit" },
  );
  if (result.status !== 0) throw Error("Android capture failed.");
  const images = {};
  for (const locale of ["ja", "en"])
    for (const screen of ["chat", "drawer", "settings"]) {
      const name = locale + "-" + screen + ".png";
      const bytes = fs.readFileSync(path.join(output, name));
      if (bytes.length < 1000) throw Error("Empty capture: " + name);
      images[name] = createHash("sha256").update(bytes).digest("hex");
    }
  fs.writeFileSync(
    path.join(output, "provenance.json"),
    JSON.stringify(
      {
        repository: "https://github.com/Milix-M/Yorube",
        commit: execFileSync("git", ["rev-parse", "HEAD"], {
          cwd: app,
          encoding: "utf8",
        }).trim(),
        method:
          "Production Compose screens rendered with Robolectric native graphics, Android API 30. Sample server data; dark theme; 412 × 892 dp at xhdpi. PNGs are direct Android View.draw output, without visual editing.",
        images,
      },
      null,
      2,
    ) + "\n",
  );
} finally {
  fs.rmSync(temporary, { force: true });
  fs.rmSync(temp, { recursive: true, force: true });
}
