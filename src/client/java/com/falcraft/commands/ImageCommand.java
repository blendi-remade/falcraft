package com.falcraft.commands;

import com.falcraft.util.FalAPI;
import com.falcraft.util.ImageCanvasManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Generates an AI image and hangs it in the world as a full-fidelity textured canvas.
 * Usage:
 *   /fal image <prompt>        - high fidelity (Nano Banana Pro)
 *   /fal image fast <prompt>   - quick/cheap (Z-Image Turbo)
 *   /fal image cancel          - cancel the current placement preview
 *
 * After generation a live preview follows your crosshair: look at a wall, then
 * right-click to place. G = rotate facing, H/N = bigger/smaller.
 */
public class ImageCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("ImageCommand");

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("fal")
                .then(literal("image")
                        // /fal image <prompt> -> Nano Banana Pro
                        .then(argument("prompt", StringArgumentType.greedyString())
                                .executes(ctx -> execute(ctx, false)))
                        // /fal image fast <prompt> -> Z-Image Turbo
                        .then(literal("fast")
                                .then(argument("prompt", StringArgumentType.greedyString())
                                        .executes(ctx -> execute(ctx, true))))
                        // /fal image cancel
                        .then(literal("cancel")
                                .executes(ImageCommand::executeCancel))
                        // /fal image clear
                        .then(literal("clear")
                                .executes(ImageCommand::executeClear))));
    }

    private static int executeClear(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft.getInstance().execute(() -> {
            int n = ImageCanvasManager.clearCurrentDimension();
            source.sendFeedback(Component.literal("§e[fal] Removed " + n + " image canvas(es)."));
        });
        return 1;
    }

    private static int executeCancel(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (ImageCanvasManager.isPreviewActive()) {
            Minecraft.getInstance().execute(ImageCanvasManager::cancelPreview);
            source.sendFeedback(Component.literal("§e[fal] Image placement cancelled."));
        } else {
            source.sendFeedback(Component.literal("§7[fal] No image preview to cancel."));
        }
        return 1;
    }

    private static int execute(CommandContext<FabricClientCommandSource> context, boolean fast) {
        String prompt = StringArgumentType.getString(context, "prompt");
        FabricClientCommandSource source = context.getSource();
        String model = fast ? "Z-Image Turbo" : "Nano Banana Pro";

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

                Minecraft.getInstance().execute(() -> {
                    ImageCanvasManager.startPreview(png);
                    source.sendFeedback(Component.literal(
                            "§a[fal] ✓ Image ready! Look at a wall and right-click to place."));
                    source.sendFeedback(Component.literal(
                            "§e[fal] G = rotate, H/N = bigger/smaller, /fal image cancel to abort."));
                });

            } catch (IllegalStateException e) {
                Minecraft.getInstance().execute(() ->
                        source.sendError(Component.literal("§c[fal] Error: API key not configured. Use /fal setkey <key>")));
                LOGGER.error("API key not configured", e);
            } catch (Exception e) {
                String msg = e.getMessage();
                Minecraft.getInstance().execute(() ->
                        source.sendError(Component.literal("§c[fal] Error during image generation: " + msg)));
                LOGGER.error("Error during image generation", e);
            }
        }, "fal-Image-Thread").start();

        return 1;
    }
}
