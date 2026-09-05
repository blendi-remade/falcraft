// falcraft-bridge: holds a MiniMax H3 Max Director WebRTC session inside
// headless Chrome and relays it to the Minecraft mod over localhost.
//
//   mod  ⇄  ws://127.0.0.1:4783/mod   (JPEG frames down, JSON control up)
//   page ⇄  ws://127.0.0.1:4783/page  (headless Chrome running the fal client)
//
//   node bridge.mjs [--key FAL_KEY] [--port 4783] [--show] [--audio]
//
//   --audio plays the broadcast's audio through your speakers from the bridge's
//   Chrome (headless Chrome has no audio sink, so this opens a real window,
//   parked off-screen). --show opens it on-screen so you can watch the page.
//
// The key is read from --key, FAL_KEY / FAL_API_KEY, ../run/.env, the
// Minecraft .env, or config/falcraft/api-key.txt (same places the mod looks).

import { createServer } from "node:http";
import { existsSync, readFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { chromium } from "playwright-core";
import { WebSocketServer } from "ws";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const flag = (name) => {
  const i = args.indexOf(name);
  return i !== -1 ? args[i + 1] : undefined;
};
const PORT = Number(flag("--port") ?? 4783);
const PAGE_PORT = PORT + 1;
const SHOW = args.includes("--show");
const AUDIO = args.includes("--audio");

// --- key ---------------------------------------------------------------
function readEnvKey(file) {
  if (!existsSync(file)) return null;
  for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
    const m = line.match(/^\s*(FAL_KEY|FAL_API_KEY)\s*=\s*(.+)\s*$/);
    if (m) return m[2].trim().replace(/^["']|["']$/g, "");
  }
  return null;
}
function readFileKey(file) {
  return existsSync(file) ? readFileSync(file, "utf8").trim() || null : null;
}
const mcDir =
  process.platform === "win32"
    ? path.join(process.env.APPDATA ?? "", ".minecraft")
    : process.platform === "darwin"
      ? path.join(os.homedir(), "Library", "Application Support", "minecraft")
      : path.join(os.homedir(), ".minecraft");
const KEY =
  flag("--key") ??
  process.env.FAL_KEY ??
  process.env.FAL_API_KEY ??
  readFileKey(path.join(HERE, "..", "run", "config", "falcraft", "api-key.txt")) ??
  readEnvKey(path.join(HERE, "..", "run", ".env")) ??
  readEnvKey(path.join(HERE, "..", ".env")) ??
  readFileKey(path.join(mcDir, "config", "falcraft", "api-key.txt")) ??
  readEnvKey(path.join(mcDir, ".env"));
if (!KEY) {
  console.error("No fal key found. Pass --key, set FAL_KEY, or run /fal setkey in game first.");
  process.exit(1);
}

// --- bundle the page ---------------------------------------------------
console.log("bundling page…");
const bundle = await build({
  entryPoints: [path.join(HERE, "page", "page.js")],
  bundle: true,
  format: "esm",
  platform: "browser",
  target: "es2022",
  write: false,
  logLevel: "silent",
});
const pageJs = bundle.outputFiles[0].text;
const pageHtml = readFileSync(path.join(HERE, "page", "page.html"), "utf8");

const pageServer = createServer((req, res) => {
  if (req.url?.startsWith("/page.js")) {
    res.writeHead(200, { "content-type": "text/javascript" });
    res.end(pageJs);
  } else {
    res.writeHead(200, { "content-type": "text/html" });
    res.end(pageHtml);
  }
});
await new Promise((r) => pageServer.listen(PAGE_PORT, "127.0.0.1", r));

// --- relay -------------------------------------------------------------
const wss = new WebSocketServer({ port: PORT, host: "127.0.0.1" });
let page = null;
const mods = new Set();
let lastState = "off";
let pendingControl = []; // control messages sent before the page connected

wss.on("connection", (socket, req) => {
  const role = req.url?.startsWith("/page") ? "page" : "mod";
  if (role === "page") {
    page = socket;
    console.log("page connected");
    for (const msg of pendingControl) socket.send(msg);
    pendingControl = [];
    socket.on("message", (data, isBinary) => {
      if (isBinary) {
        for (const mod of mods) if (mod.readyState === 1 && mod.bufferedAmount < 1_000_000) mod.send(data, { binary: true });
      } else {
        const text = data.toString();
        try {
          const m = JSON.parse(text);
          if (m.type === "state") {
            lastState = m.state;
            console.log("state:", m.state);
          }
        } catch {
          /* pass through */
        }
        for (const mod of mods) if (mod.readyState === 1) mod.send(text);
      }
    });
    socket.on("close", () => {
      if (page === socket) page = null;
      console.log("page disconnected");
    });
  } else {
    mods.add(socket);
    console.log(`mod connected (${mods.size})`);
    socket.send(JSON.stringify({ type: "state", state: lastState }));
    socket.on("message", (data) => {
      const text = data.toString();
      try {
        const m = JSON.parse(text);
        console.log("control:", m.type, m.text ?? (m.prompt ? m.prompt.slice(0, 80) + "…" : ""));
      } catch {
        return;
      }
      if (page && page.readyState === 1) page.send(text);
      else pendingControl.push(text);
    });
    socket.on("close", () => {
      mods.delete(socket);
      console.log(`mod disconnected (${mods.size})`);
    });
  }
});

console.log(`bridge listening on ws://127.0.0.1:${PORT}/mod`);

// --- headless chrome ---------------------------------------------------
async function launchBrowser() {
  // Audio needs a headed Chrome; without --show, park the window off-screen.
  const headed = SHOW || AUDIO;
  const opts = {
    headless: !headed,
    // Playwright mutes Chrome by default; drop that switch when we want sound.
    ignoreDefaultArgs: AUDIO ? ["--mute-audio"] : [],
    args: [
      ...(headed && !SHOW ? ["--window-position=-32000,-32000", "--window-size=320,180"] : []),
      "--autoplay-policy=no-user-gesture-required",
      "--use-fake-ui-for-media-stream",
      "--disable-web-security",
      "--no-sandbox",
    ],
  };
  try {
    return await chromium.launch({ ...opts, channel: "chrome" });
  } catch (e) {
    console.log("system chrome not found via channel, trying edge…", e?.message);
    return chromium.launch({ ...opts, channel: "msedge" });
  }
}

const browser = await launchBrowser();
const tab = await browser.newPage({ viewport: { width: 960, height: 540 } });
tab.on("console", (m) => {
  const t = m.text();
  if (!t.startsWith("[page]")) return;
  console.log(t);
});
tab.on("pageerror", (e) => console.log("page error:", e.message));
const url = `http://127.0.0.1:${PAGE_PORT}/?key=${encodeURIComponent(KEY)}&ws=${encodeURIComponent(`ws://127.0.0.1:${PORT}/page`)}${AUDIO ? "&audio=1" : ""}`;
await tab.goto(url);
console.log(AUDIO ? "audio: playing through this machine's speakers" : "audio: off (start with --audio to hear the broadcast)");
console.log("chrome ready. In Minecraft: /fal tv <channel>, then type !anything to steer.");

const shutdown = async () => {
  console.log("shutting down");
  try {
    await browser.close();
  } catch {
    /* ignore */
  }
  process.exit(0);
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
