import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
if (!process.argv[2])
  throw new Error("Usage: node scripts/sync-app-legal.mjs /path/to/Yorube");
const root = path.resolve(process.argv[2]);
process.chdir(fileURLToPath(new URL("..", import.meta.url)));
fs.mkdirSync("data", { recursive: true });
fs.mkdirSync("public/legal", { recursive: true });
fs.copyFileSync(
  path.join(root, "third_party/dependencies.json"),
  "data/dependencies.json",
);
for (const file of ["THIRD_PARTY_NOTICES.txt", "public_suffix_list.dat"]) {
  fs.copyFileSync(
    path.join(root, "app/src/main/assets/legal", file),
    path.join("public/legal", file),
  );
}
const license = path.join(root, "LICENSE");
const name = fs.readFileSync(license, "utf8").split(/\r?\n/)[0];
fs.writeFileSync(
  "data/app-license.json",
  JSON.stringify({ name }, null, 2) + "\n",
);
fs.copyFileSync(license, "public/legal/LICENSE.txt");
fs.copyFileSync(license, "LICENSE");
console.log("Synced app legal documents.");

for (const file of ["dependencies.json", "aboutlibraries.json"]) {
  fs.copyFileSync(
    path.join(root, "third_party", file),
    path.join("public/legal", file),
  );
}
