package com.falcraft.commands;

import com.falcraft.util.FalAPI;
import com.falcraft.util.PlacementPreview;
import com.falcraft.util.SplatParser;
import com.falcraft.util.Voxelizer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.suggestion.SuggestionProvider;

/**
 * Command to generate 3D models as Gaussian splats using Nano Banana Pro + TripoSplat,
 * then voxelize the splat cloud.
 * Usage: /fal splat <size> <prompt>
 *
 * Experimental alternative to /fal craft (Hunyuan mesh). Splats are noisier/blobbier but
 * can capture organic, fuzzy subjects (creatures, foliage) that meshes struggle with.
 */
public class SplatCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("SplatCommand");

    // Default TripoSplat Gaussian count (rounded to nearest multiple of 32 by the model).
    private static final int NUM_GAUSSIANS = 262144;

    private static final SuggestionProvider<FabricClientCommandSource> SIZE_SUGGESTIONS = (context, builder) -> {
        builder.suggest(16, Component.literal("Tiny"));
        builder.suggest(32, Component.literal("Small"));
        builder.suggest(48, Component.literal("Medium"));
        builder.suggest(64, Component.literal("Large"));
        builder.suggest(80, Component.literal("Extra Large"));
        builder.suggest(96, Component.literal("Huge"));
        builder.suggest(112, Component.literal("Massive"));
        builder.suggest(128, Component.literal("Maximum"));
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("fal")
                .then(literal("splat")
                        .then(argument("size", IntegerArgumentType.integer(16, 128))
                                .suggests(SIZE_SUGGESTIONS)
                                .then(argument("prompt", StringArgumentType.greedyString())
                                        .executes(SplatCommand::execute)))));
    }

    private static int execute(CommandContext<FabricClientCommandSource> context) {
        int size = IntegerArgumentType.getInteger(context, "size");
        String prompt = StringArgumentType.getString(context, "prompt");
        FabricClientCommandSource source = context.getSource();

        source.sendFeedback(Component.literal("§d[fal] Starting §bSPLAT§d generation (" + size + "x" + size + "x" + size + ")"));
        source.sendFeedback(Component.literal("§d[fal] Prompt: \"" + prompt + "\""));
        source.sendFeedback(Component.literal("§d[fal] Using Nano Banana Pro + TripoSplat"));

        new Thread(() -> {
            try {
                LOGGER.info("Starting Splat pipeline...");

                // Step 1: Generate image with Nano Banana Pro
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§d[fal] [1/4] Generating image with Nano Banana Pro...")));

                FalAPI falApi = new FalAPI();
                String imageUrl = falApi.generateImageWithNanoBanana(prompt);

                // Step 2: Convert to a Gaussian splat with TripoSplat
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§d[fal] [2/4] Generating Gaussian splat with TripoSplat...")));

                byte[] splatBytes = falApi.generateSplatWithTripoSplat(imageUrl, NUM_GAUSSIANS);

                // Step 3: Parse the .splat cloud
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§d[fal] [3/4] Parsing splat cloud...")));

                SplatParser.SplatData splat = SplatParser.parse(splatBytes);

                // Step 4: Voxelize the splat
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§d[fal] [4/4] Converting to voxels (" +
                            size + "x" + size + "x" + size + ")...")));

                Voxelizer.VoxelGrid voxelGrid = Voxelizer.voxelizeSplat(splat, size);

                if (voxelGrid.voxels().isEmpty()) {
                    Minecraft.getInstance().execute(() ->
                        source.sendError(Component.literal("§c[fal] Error: Generated splat has no voxels!")));
                    return;
                }

                // Step 5: Enter placement preview
                Minecraft.getInstance().execute(() -> {
                    try {
                        PlacementPreview.startPlacement(voxelGrid);

                        source.sendFeedback(Component.literal(
                                "§a[fal] ✓ §bSPLAT§a generation complete! " + voxelGrid.voxels().size() + " blocks ready."));
                        source.sendFeedback(Component.literal(
                                "§e[fal] Right-click to place, G to rotate, V to tilt!"));
                    } catch (Exception e) {
                        String errorMsg = e.getMessage();
                        source.sendError(Component.literal("§c[fal] Error preparing placement: " + errorMsg));
                        LOGGER.error("Error preparing placement", e);
                    }
                });

            } catch (IllegalStateException e) {
                Minecraft.getInstance().execute(() ->
                    source.sendError(Component.literal("§c[fal] Error: API key not configured. Use /fal setkey <key>")));
                LOGGER.error("API key not configured", e);
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                Minecraft.getInstance().execute(() ->
                    source.sendError(Component.literal("§c[fal] Error during splat generation: " + errorMsg)));
                LOGGER.error("Error during Splat generation process", e);
            }
        }, "fal-Splat-Thread").start();

        return 1;
    }
}
