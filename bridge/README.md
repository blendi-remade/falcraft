# falcraft-bridge (FalTV)

Streams a live, continuously generated TV broadcast onto an in-world canvas in
Minecraft, rendered by [MiniMax H3 Max Director](https://fal.ai/models/minimax/h3-max/director).

The Director API is WebRTC only, which Java cannot speak natively. This bridge
holds the WebRTC session inside headless Chrome, draws the incoming video to a
canvas, and relays JPEG frames to the mod over a local WebSocket. The mod
decodes them straight into the live canvas texture.

```
Minecraft mod  ⇄  ws://127.0.0.1:4783/mod   ⇄  bridge (Node + headless Chrome)  ⇄  fal Director (WebRTC)
   JPEG frames ←──────────────────────────────── page draws <video> to a 640x360 canvas @15fps
   {tune|steer|off} ─────────────────────────────→ session.send({type:"prompt"})
```

## Run it

```bash
cd bridge
npm install
npm start          # add --show to watch the headless page, --key <FAL_KEY> to override
npm run start:audio   # same, plus the broadcast's audio through your speakers
```

The bridge finds your fal key the same way the mod does: `--key`, `FAL_KEY` /
`FAL_API_KEY`, `../run/.env`, `../run/config/falcraft/api-key.txt`, or the
`.minecraft` copies. It uses your installed Chrome or Edge (no browser
download).

Then in Minecraft:

```
/fal tv news          # or: minecraft, cooking, nature, horror, space, wrestling, aquarium
/fal tv a cooking show where everything is on fire   # freeform, any words
!the anchor gets breaking news about a dragon         # steer the live show from chat
/fal tv off
```

Hold the TV map it gives you and right-click a wall to hang it (same controls
as any falcraft canvas: H/N size, G rotate, F snap).

## Notes

- **Cost**: Director bills per second of stream with a 60-second minimum, so a
  channel costs a couple of dollars for a couple of minutes. `/fal tv off` stops
  it; the canvas freezes on the last frame.
- **Sessions cap at ~2 minutes.** The bridge auto-retunes with the show's
  recent steers so the broadcast continues; the mod shows a brief "signal
  break" while it reconnects.
- **One session per machine.** Run one bridge at a time.
- **Audio**: `npm run start:audio` (or `--audio`) plays the broadcast's audio
  through your speakers from the bridge's Chrome. It is not positional in the
  world yet; in-world OpenAL playback is a possible follow-up.
