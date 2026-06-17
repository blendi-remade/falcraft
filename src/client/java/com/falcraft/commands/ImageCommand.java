package com.falcraft.commands;

import com.falcraft.util.FalAPI;
import com.falcraft.util.ImageCanvasManager;
import com.falcraft.util.MapImageFactory;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Generates an AI image and gives it as a tagged map item (see {@link ImageCanvasManager}).
 * Usage:
 *   /fal image [square|landscape|portrait] <prompt>        - high fidelity (Nano Banana Pro)
 *   /fal image fast [square|landscape|portrait] <prompt>   - quick/cheap (Z-Image Turbo)
 *   /fal image cancel | clear
 * Aspect defaults to square. The chosen orientation also drives the video aspect ratio
 * (see VideoCommand), since image-to-video derives orientation from the source image.
 */
public class ImageCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("ImageCommand");

    /** Aspect presets mapped to each image model's parameter. */
    public enum Aspect {
        SQUARE("square_hd", "1:1"),
        LANDSCAPE("landscape_16_9", "16:9"),
        PORTRAIT("portrait_16_9", "9:16");

        public final String zImageSize;  // Z-Image Turbo image_size
        public final String nanoBanana;  // Nano Banana Pro aspect_ratio

        Aspect(String zImageSize, String nanoBanana) {
            this.zImageSize = zImageSize;
            this.nanoBanana = nanoBanana;
        }
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> image = literal("image");
        withAspectBranches(image, false);                       // /fal image [aspect] <prompt>  (Nano Banana Pro)
        image.then(withAspectBranches(literal("fast"), true));  // /fal image fast [aspect] <prompt>  (Z-Image)
        image.then(literal("cancel").executes(ImageCommand::executeCancel));
        image.then(literal("clear").executes(ImageCommand::executeClear));
        dispatcher.register(literal("fal").then(image));
    }

    /** Adds a default (square) prompt plus a literal per aspect, all under the given node. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> withAspectBranches(
            LiteralArgumentBuilder<FabricClientCommandSource> node, boolean fast) {
        node.then(argument("prompt", StringArgumentType.greedyString())
                .executes(ctx -> execute(ctx, fast, Aspect.SQUARE)));
        for (Aspect aspect : Aspect.values()) {
            node.then(literal(aspect.name().toLowerCase())
                    .then(argument("prompt", StringArgumentType.greedyString())
                            .executes(ctx -> execute(ctx, fast, aspect))));
        }
        return node;
    }

    private static int executeCancel(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft.getInstance().execute(ImageCanvasManager::cancelPreview);
        source.sendFeedback(Component.literal("§e[fal] Image placement cancelled."));
        return 1;
    }

    private static int executeClear(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft.getInstance().execute(() -> {
            int n = ImageCanvasManager.clearCurrentDimension();
            source.sendFeedback(Component.literal("§e[fal] Removed " + n + " image canvas(es)."));
        });
        return 1;
    }

    private static int execute(CommandContext<FabricClientCommandSource> context, boolean fast, Aspect aspect) {
        String prompt = StringArgumentType.getString(context, "prompt");
        FabricClientCommandSource source = context.getSource();
        String model = fast ? "Z-Image Turbo" : "Nano Banana Pro";

        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) {
            source.sendError(Component.literal("§c[fal] Image items are single-player only."));
            return 0;
        }
        if (mc.player == null) {
            return 0;
        }
        UUID playerUuid = mc.player.getUUID();

        source.sendFeedback(Component.literal("§d[fal] Generating §b" + aspect.name().toLowerCase()
                + "§d image with §b" + model + "§d..."));
        source.sendFeedback(Component.literal("§d[fal] Prompt: \"" + prompt + "\""));

        new Thread(() -> {
            try {
                FalAPI falApi = new FalAPI();
                String imageUrl = fast
                        ? falApi.generatePictureZImage(prompt, aspect.zImageSize)
                        : falApi.generatePictureNanoBanana(prompt, aspect.nanoBanana);

                byte[] png = falApi.downloadFile(imageUrl);
                LOGGER.info("Downloaded picture ({} bytes)", png.length);

                String id = ImageCanvasManager.newId();
                ImageCanvasManager.saveImageBytes(id, png);

                MinecraftServer server = mc.getSingleplayerServer();
                if (server == null) {
                    mc.execute(() -> source.sendError(Component.literal("§c[fal] Image items are single-player only.")));
                    return;
                }

                server.execute(() -> {
                    try {
                        ServerPlayer sp = server.getPlayerList().getPlayer(playerUuid);
                        if (sp == null) return;
                        ServerLevel sl = sp.serverLevel();
                        ItemStack map = MapImageFactory.createTaggedMap(sl, png, id);
                        if (!sp.getInventory().add(map)) {
                            sp.drop(map, false);
                        }
                        mc.execute(() -> {
                            source.sendFeedback(Component.literal("§a[fal] ✓ Image added to your inventory!"));
                            source.sendFeedback(Component.literal(
                                    "§e[fal] Hold it to place — right-click to hang, H/N size, G rotate, F snap."));
                        });
                    } catch (Exception e) {
                        LOGGER.error("Failed to create image item", e);
                        mc.execute(() -> source.sendError(Component.literal("§c[fal] Failed to create image item: " + e.getMessage())));
                    }
                });

            } catch (IllegalStateException e) {
                mc.execute(() ->
                        source.sendError(Component.literal("§c[fal] Error: API key not configured. Use /fal setkey <key>")));
                LOGGER.error("API key not configured", e);
            } catch (Exception e) {
                String msg = e.getMessage();
                mc.execute(() ->
                        source.sendError(Component.literal("§c[fal] Error during image generation: " + msg)));
                LOGGER.error("Error during image generation", e);
            }
        }, "fal-Image-Thread").start();

        return 1;
    }
}
