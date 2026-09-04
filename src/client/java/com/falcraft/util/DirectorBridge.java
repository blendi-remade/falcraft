package com.falcraft.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FalTV client: talks to the local falcraft-bridge (a headless-Chrome sidecar
 * that holds the MiniMax H3 Max Director WebRTC session) over a plain
 * WebSocket. JPEG frames come down as binary and are decoded straight into the
 * live canvas texture; JSON control comes down as text.
 *
 * There is no WebRTC in Java; the bridge does all of it. This class only needs
 * {@code java.net.http.WebSocket}, already on the classpath.
 *
 * Single active channel at a time (one Director session per bridge).
 */
public final class DirectorBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("DirectorBridge");
    private static final Gson GSON = new Gson();
    private static final String BRIDGE_URL = "ws://127.0.0.1:4783/mod";

    private static final DirectorBridge INSTANCE = new DirectorBridge();

    public static DirectorBridge get() {
        return INSTANCE;
    }

    private volatile WebSocket socket;
    private volatile boolean connecting;
    private volatile String activeId;
    private volatile String state = "off";
    // Buffers binary fragments of one frame until the final fragment arrives.
    private final ByteArrayOutputStream frameBuf = new ByteArrayOutputStream(64 * 1024);
    private final AtomicReference<Runnable> onReady = new AtomicReference<>();

    private DirectorBridge() {}

    public String getState() {
        return state;
    }

    public String getActiveId() {
        return activeId;
    }

    public boolean isConnected() {
        return socket != null;
    }

    /**
     * Tunes the bridge to a channel: ensures the socket is up, registers the
     * live canvas id as the frame target, and sends the opening prompt.
     */
    public void tune(String id, String openingPrompt, String resolution) {
        this.activeId = id;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "tune");
        msg.addProperty("prompt", openingPrompt);
        msg.addProperty("resolution", resolution);
        Runnable send = () -> sendText(GSON.toJson(msg));
        if (socket != null) {
            send.run();
        } else {
            onReady.set(send);
            connect();
        }
    }

    /** Sends a steer (a chat "!..." line, or a preset nudge) to the live show. */
    public void steer(String text) {
        if (socket == null || activeId == null) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "steer");
        msg.addProperty("text", text);
        sendText(GSON.toJson(msg));
    }

    /** Stops the current channel; the canvas freezes on its last frame. */
    public void off() {
        String id = activeId;
        activeId = null;
        if (socket != null) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "off");
            sendText(GSON.toJson(msg));
        }
        if (id != null) {
            Minecraft.getInstance().execute(() -> ImageCanvasManager.stopLive(id));
        }
        state = "off";
    }

    private synchronized void connect() {
        if (socket != null || connecting) return;
        connecting = true;
        try {
            HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(BRIDGE_URL), new Listener())
                    .whenComplete((ws, err) -> {
                        connecting = false;
                        if (err != null) {
                            LOGGER.warn("Bridge connect failed: {}", err.getMessage());
                            feedback("§c[fal] TV bridge not running. Start it: §fnpm start§c in falcraft/bridge");
                        }
                    });
        } catch (Exception e) {
            connecting = false;
            LOGGER.warn("Bridge connect threw", e);
        }
    }

    private void sendText(String text) {
        WebSocket ws = socket;
        if (ws != null) ws.sendText(text, true);
    }

    private void handleControl(String text) {
        JsonObject m;
        try {
            m = GSON.fromJson(text, JsonObject.class);
        } catch (Exception e) {
            return;
        }
        if (m == null || !m.has("type")) return;
        String type = m.get("type").getAsString();
        switch (type) {
            case "state" -> {
                state = m.has("state") ? m.get("state").getAsString() : state;
                switch (state) {
                    case "connecting" -> feedback("§d[fal] Tuning the channel…");
                    case "live" -> feedback("§a[fal] ✓ On air. Type §f!something§a in chat to steer the show.");
                    case "lost" -> feedback("§7[fal] Signal break… retuning the broadcast.");
                    case "off" -> {}
                    default -> {}
                }
            }
            case "notice" -> feedback("§e[fal] " + m.get("text").getAsString());
            case "steer_sent" -> {}
            case "steer_applied" -> {}
            default -> {}
        }
    }

    private void handleFrame(byte[] jpeg) {
        String id = activeId;
        if (id == null) return;
        try {
            NativeImage img = NativeImage.read(new ByteArrayInputStream(jpeg));
            ImageCanvasManager.pushLiveFrame(id, img);
        } catch (Exception e) {
            // A torn frame is not worth logging every time.
        }
    }

    private void feedback(String msg) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal(msg), false);
        });
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            LOGGER.info("Bridge connected");
            Runnable ready = onReady.getAndSet(null);
            if (ready != null) ready.run();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            handleControl(data.toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            frameBuf.write(chunk, 0, chunk.length);
            if (last) {
                byte[] jpeg = frameBuf.toByteArray();
                frameBuf.reset();
                handleFrame(jpeg);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOGGER.warn("Bridge socket error: {}", error.getMessage());
            socket = null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOGGER.info("Bridge socket closed: {} {}", statusCode, reason);
            socket = null;
            return null;
        }
    }
}
