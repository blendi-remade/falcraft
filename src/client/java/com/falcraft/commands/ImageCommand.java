package com.falcraft.commands;

import com.falcraft.util.FalAPI;
import com.falcraft.util.ImageCanvasManager;
import com.falcraft.util.MapImageFactory;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
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
 * Generates an AI image and gives it as a tagged map item. Hold the item to place it
 * in the world as a full-fidelity canvas; break a placed canvas to get the item back.
 * Usage:
 *   /fal image <prompt>        - high fidelity (Nano Banana Pro)
 *   /fal image fast <prompt>   - quick/cheap (Z-Image Turbo)
 *   /fal image cancel          - cancel the current placement preview
 *   /fal image clear           - remove all placed canvases in this dimension
 */
public class ImageCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("ImageCommand");

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("fal")
                .then(literal("image")
                        .then(argument("prompt", StringArgumentType.greedyString())
                                .executes(ctx -> execute(ctx, false)))
                        .then(literal("fast")
                                .then(argument("prompt", StringArgumentType.greedyString())
                                        .executes(ctx -> execute(ctx, true))))
                        .then(literal("cancel")
                                .executes(ImageCommand::executeCancel))
                        .then(literal("clear")
                                .executes(ImageCommand::executeClear))));
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

    private static int execute(CommandContext<FabricClientCommandSource> context, boolean fast) {
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

        source.sendFeedback(Component.literal("§d[fal] Generating image with §b" + model + "§d..."));
        source.sendFeedback(Component.literal("§d[fal] Prompt: \"" + prompt + "\""));

        new Thread(() -> {
            try {
                FalAPI falApi = new FalAPI();
                String imageUrl = fast
                        ? falApi.generatePictureZImage(prompt)
                        : falApi.generatePictureNanoBanana(prompt);

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
