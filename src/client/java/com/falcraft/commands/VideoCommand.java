package com.falcraft.commands;

import com.falcraft.util.FalAPI;
import com.falcraft.util.ImageCanvasManager;
import com.falcraft.util.MapImageFactory;
import com.falcraft.util.VideoDecoder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.UUID;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Animates the currently-held image canvas into a looping video canvas (image-to-video).
 * Usage (hold an image made with /fal image, then):
 *   /fal video <prompt>        - Seedance 2.0 (higher quality)
 *   /fal video fast <prompt>   - LTX-2.3 fast
 *
 * The result is itemized + placed exactly like an image, but its texture loops through
 * the decoded frames.
 */
public class VideoCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("VideoCommand");

    /** Source-image orientation -> the aspect_ratio each video model should use. */
    private enum Orient {
        SQUARE("auto", "1:1"),      // LTX has no square -> auto (best effort); Seedance -> 1:1
        LANDSCAPE("16:9", "16:9"),
        PORTRAIT("9:16", "9:16");

        final String ltx;
        final String seedance;

        Orient(String ltx, String seedance) {
            this.ltx = ltx;
            this.seedance = seedance;
        }
    }

    /** Determines orientation from a PNG's dimensions (near-square counts as square). */
    private static Orient orientationOf(byte[] png) {
        try (NativeImage ni = NativeImage.read(new ByteArrayInputStream(png))) {
            int w = ni.getWidth();
            int h = ni.getHeight();
            if (w > h * 1.1) return Orient.LANDSCAPE;
            if (h > w * 1.1) return Orient.PORTRAIT;
            return Orient.SQUARE;
        } catch (Exception e) {
            LOGGER.warn("Could not read image dimensions, defaulting to square", e);
            return Orient.SQUARE;
        }
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("fal")
                .then(literal("video")
                        .then(argument("prompt", StringArgumentType.greedyString())
                                .executes(ctx -> execute(ctx, false)))
                        .then(literal("fast")
                                .then(argument("prompt", StringArgumentType.greedyString())
                                        .executes(ctx -> execute(ctx, true))))));
    }

    private static int execute(CommandContext<FabricClientCommandSource> context, boolean fast) {
        String prompt = StringArgumentType.getString(context, "prompt");
        FabricClientCommandSource source = context.getSource();
        String model = fast ? "LTX-2.3 fast" : "Seedance 2.0";

        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) {
            source.sendError(Component.literal("§c[fal] Video is single-player only."));
            return 0;
        }
        if (mc.player == null) {
            return 0;
        }

        // The source image is the canvas the player is currently holding.
        String sourceId = MapImageFactory.getFalcraftId(mc.player.getMainHandItem());
        if (sourceId == null) {
            source.sendError(Component.literal("§c[fal] Hold an image canvas first (make one with /fal image), then run /fal video."));
            return 0;
        }
        byte[] sourcePng = ImageCanvasManager.readImageBytes(sourceId);
        if (sourcePng == null) {
            source.sendError(Component.literal("§c[fal] Couldn't read the held image."));
            return 0;
        }
        UUID playerUuid = mc.player.getUUID();

        source.sendFeedback(Component.literal("§d[fal] Animating your image with §b" + model + "§d..."));
        source.sendFeedback(Component.literal("§d[fal] Prompt: \"" + prompt + "\""));
        source.sendFeedback(Component.literal("§7[fal] This can take a minute or two."));

        new Thread(() -> {
            try {
                String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(sourcePng);

                // Match the video's aspect ratio to the source image's orientation.
                // LTX (fast) has no square option -> "auto"; Seedance supports "1:1".
                Orient orient = orientationOf(sourcePng);
                String aspect = fast ? orient.ltx : orient.seedance;

                FalAPI falApi = new FalAPI();
                String videoUrl = fast
                        ? falApi.generateVideoFast(dataUri, prompt, aspect)
                        : falApi.generateVideoNormal(dataUri, prompt, aspect);

                byte[] mp4 = falApi.downloadFile(videoUrl);
                LOGGER.info("Downloaded video ({} bytes)", mp4.length);

                String id = ImageCanvasManager.newId();
                ImageCanvasManager.saveVideoBytes(id, mp4);

                mc.execute(() -> source.sendFeedback(Component.literal("§d[fal] Decoding frames...")));
                VideoDecoder.DecodedVideo dv = ImageCanvasManager.decodeSavedVideo(id);
                if (dv.frames().isEmpty()) {
                    mc.execute(() -> source.sendError(Component.literal("§c[fal] Could not decode the generated video.")));
                    return;
                }

                // Frame 0 doubles as the item/thumbnail image.
                byte[] thumbPng = dv.frames().get(0).asByteArray();
                ImageCanvasManager.saveImageBytes(id, thumbPng);

                mc.execute(() -> {
                    // Register the animated texture first (render thread), then give the item (server thread).
                    ImageCanvasManager.registerVideo(id, dv.frames(), dv.fps());

                    MinecraftServer server = mc.getSingleplayerServer();
                    if (server == null) return;
                    server.execute(() -> {
                        try {
                            ServerPlayer sp = server.getPlayerList().getPlayer(playerUuid);
                            if (sp == null) return;
                            ItemStack map = MapImageFactory.createTaggedMap(sp.serverLevel(), thumbPng, id);
                            if (!sp.getInventory().add(map)) {
                                sp.drop(map, false);
                            }
                            mc.execute(() -> {
                                source.sendFeedback(Component.literal("§a[fal] ✓ Video ready in your inventory!"));
                                source.sendFeedback(Component.literal(
                                        "§e[fal] Hold it to place the looping canvas — right-click to hang, H/N size, G rotate, F snap."));
                            });
                        } catch (Exception e) {
                            LOGGER.error("Failed to create video item", e);
                            mc.execute(() -> source.sendError(Component.literal("§c[fal] Failed to create video item: " + e.getMessage())));
                        }
                    });
                });

            } catch (IllegalStateException e) {
                mc.execute(() ->
                        source.sendError(Component.literal("§c[fal] Error: API key not configured. Use /fal setkey <key>")));
                LOGGER.error("API key not configured", e);
            } catch (Exception e) {
                String msg = e.getMessage();
                mc.execute(() ->
                        source.sendError(Component.literal("§c[fal] Error during video generation: " + msg)));
                LOGGER.error("Error during video generation", e);
            }
        }, "fal-Video-Thread").start();

        return 1;
    }
}
