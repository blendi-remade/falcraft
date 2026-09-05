// Runs INSIDE headless Chrome. Holds the Director WebRTC session, draws the
// incoming video to a fixed-size canvas, and ships JPEG frames + status to
// the bridge over a local WebSocket. The Minecraft mod never touches WebRTC.
import { createFalClient } from "@fal-ai/client";
import { wma } from "@fal-ai/client/realtime";

const DIRECTOR = "minimax/h3-max/director";
// Fixed output size: the mod allocates its texture once and never resizes.
const OUT_W = 640;
const OUT_H = 360;
const FPS = 15;
const JPEG_QUALITY = 0.82;
/** Director sessions cap at ~2 minutes; we re-tune with the running context. */
const MAX_RECONNECTS = 50;

const params = new URLSearchParams(location.search);
const key = params.get("key");
const wsUrl = params.get("ws") || "ws://127.0.0.1:4783/page";
// With --audio the bridge runs a headed Chrome and we let the <video> element
// play the broadcast's audio track out loud.
const AUDIO = params.get("audio") === "1";

const fal = createFalClient({ credentials: key });
const video = document.getElementById("v");
const canvas = document.getElementById("c");
canvas.width = OUT_W;
canvas.height = OUT_H;
const ctx = canvas.getContext("2d", { alpha: false });

let ws = null;
let session = null;
let promptVersion = 1;
let channel = null; // { prompt, resolution }
let steers = []; // recent steer texts, carried across reconnects
let pump = null;
let reconnects = 0;
let closingOnPurpose = false;

const log = (...args) => console.log("[page]", ...args);
const send = (obj) => {
  if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
};

// --- bridge socket ---------------------------------------------------------
function connectBridge() {
  ws = new WebSocket(wsUrl);
  ws.binaryType = "arraybuffer";
  ws.onopen = () => {
    log("bridge connected");
    send({ type: "state", state: session ? "live" : "off" });
  };
  ws.onmessage = (event) => {
    let msg;
    try {
      msg = JSON.parse(event.data);
    } catch {
      return;
    }
    if (msg.type === "tune") tune(msg);
    else if (msg.type === "steer") steer(msg.text);
    else if (msg.type === "off") off();
  };
  ws.onclose = () => {
    log("bridge socket closed; retrying");
    setTimeout(connectBridge, 1000);
  };
}

// --- director session -----------------------------------------------------
async function tune(msg) {
  await off(true);
  channel = { prompt: msg.prompt, resolution: msg.resolution || "480p" };
  steers = [];
  reconnects = 0;
  openSession(channel.prompt);
}

function openSession(configurePrompt) {
  closingOnPurpose = false;
  promptVersion = 1;
  send({ type: "state", state: "connecting" });
  log("opening session");
  session = fal.realtime.open(wma(DIRECTOR), {
    receive: ["video", "audio"],
    onMedia: (stream) => {
      video.srcObject = stream;
      video.muted = !AUDIO;
      video.volume = 1;
      video.play().catch((e) => log("play failed", e?.message));
      startPump();
      send({ type: "state", state: "live" });
    },
    onData: (raw) => {
      let m;
      try {
        m = JSON.parse(raw);
      } catch {
        return;
      }
      switch (m.type) {
        case "chunk":
          send({
            type: "chunk",
            index: m.chunk_index,
            buffer: m.buffer_depth_seconds,
            genSeconds: m.generation_seconds,
          });
          break;
        case "prompt_applied":
          send({ type: "steer_applied" });
          break;
        case "prompt_rejected":
          send({ type: "notice", text: "The channel refused that. Try another." });
          break;
        case "error":
          if (m.code === "stale_prompt_version") {
            promptVersion += 1;
          } else {
            send({ type: "notice", text: `Signal error: ${m.code}` });
            log("director error", m);
          }
          break;
        case "stream_exhausted":
          log("stream exhausted", m.reason);
          if (!closingOnPurpose) reconnect();
          break;
        default:
          break;
      }
    },
    onState: (state) => {
      log("realtime", state);
      if ((state === "closed" || state === "failed") && !closingOnPurpose && channel) {
        reconnect();
      }
    },
    onError: (error) => log("realtime error", error?.message ?? error),
  });
  session.send({
    type: "configure",
    protocol_version: 1,
    prompt_version: promptVersion,
    prompt: configurePrompt,
    resolution: channel.resolution,
    aspect_ratio: "16:9",
    memory: 12,
  });
}

let reconnecting = false;
async function reconnect() {
  if (reconnecting || !channel) return;
  reconnecting = true;
  stopPump();
  send({ type: "state", state: "lost" });
  try {
    await session?.close();
  } catch {
    /* already gone */
  }
  session = null;
  if (reconnects++ >= MAX_RECONNECTS) {
    send({ type: "state", state: "off" });
    reconnecting = false;
    return;
  }
  // Carry the audience's recent steers into the new session's opening so
  // the show picks up roughly where it left off.
  const recap = steers.slice(-3).join(" Then: ");
  const prompt = recap
    ? `${channel.prompt} The broadcast continues where it left off. Recently: ${recap}.`
    : channel.prompt;
  setTimeout(() => {
    reconnecting = false;
    if (channel) openSession(prompt);
  }, 800);
}

function steer(text) {
  if (!session || !text) return;
  steers.push(text);
  if (steers.length > 12) steers.shift();
  promptVersion += 1;
  session.send({ type: "prompt", prompt_version: promptVersion, prompt: text });
  send({ type: "steer_sent" });
}

async function off(silent = false) {
  closingOnPurpose = true;
  stopPump();
  const s = session;
  session = null;
  channel = silent ? channel : null;
  if (s) {
    try {
      s.send({ type: "stop" });
    } catch {
      /* ignore */
    }
    try {
      await s.close();
    } catch {
      /* ignore */
    }
  }
  if (!silent) {
    channel = null;
    send({ type: "state", state: "off" });
  }
}

// --- frame pump -----------------------------------------------------------
function startPump() {
  stopPump();
  pump = setInterval(() => {
    if (!ws || ws.readyState !== 1 || ws.bufferedAmount > 512 * 1024) return;
    if (video.readyState < 2 || video.videoWidth === 0) return;
    ctx.drawImage(video, 0, 0, OUT_W, OUT_H);
    canvas.toBlob(
      (blob) => {
        if (!blob || !ws || ws.readyState !== 1) return;
        blob.arrayBuffer().then((buf) => ws.send(buf));
      },
      "image/jpeg",
      JPEG_QUALITY
    );
  }, 1000 / FPS);
}

function stopPump() {
  if (pump) clearInterval(pump);
  pump = null;
}

connectBridge();
