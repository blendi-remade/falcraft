package com.falcraft.commands;

import com.falcraft.util.FalAPI;
import com.falcraft.util.GLBParser;
import com.falcraft.util.PlacementPreview;
import com.falcraft.util.TextureSampler;
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
 * Command to generate 3D models using Nano Banana Pro + Hunyuan 3D v3.1 Pro pipeline
 * Usage: /fal craft <size> <prompt>
 * Higher quality UV-textured models via Hunyuan 3D
 */
public class CraftCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("CraftCommand");

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
                .then(literal("craft")
                        .then(argument("size", IntegerArgumentType.integer(16, 128))
                                .suggests(SIZE_SUGGESTIONS)
                                .then(argument("prompt", StringArgumentType.greedyString())
                                        .executes(CraftCommand::execute)))));
    }

    private static int execute(CommandContext<FabricClientCommandSource> context) {
        int size = IntegerArgumentType.getInteger(context, "size");
        String prompt = StringArgumentType.getString(context, "prompt");
        FabricClientCommandSource source = context.getSource();

        source.sendFeedback(Component.literal("§d[fal] Starting §bCRAFT§d generation (" + size + "x" + size + "x" + size + ")"));
        source.sendFeedback(Component.literal("§d[fal] Prompt: \"" + prompt + "\""));
        source.sendFeedback(Component.literal("§d[fal] Using Nano Banana Pro + Hunyuan 3D v3.1 Pro"));

        new Thread(() -> {
            try {
                LOGGER.info("Starting Craft pipeline...");

                // Step 1: Generate image with Nano Banana Pro
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§d[fal] [1/4] Generating image with Nano Banana Pro...")));

                FalAPI falApi = new FalAPI();
                String imageUrl = falApi.generateImageWithNanoBanana(prompt);

                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§d[fal] [2/4] Converting to 3D with Hunyuan 3D...")));

                // Step 2: Convert to 3D with Hunyuan
                FalAPI.ModelResult modelResult = falApi.generate3DWithHunyuan(imageUrl);

                LOGGER.info("Received GLB model ({} bytes)", modelResult.glbData().length);
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§d[fal] [3/4] Parsing 3D model...")));

                // Step 3: Extract texture and parse GLB
                // Hunyuan 3D follows standard glTF UV convention (V=0 is top), so flipV=false
                TextureSampler textureSampler = null;
                try {
                    byte[] embeddedTexture = GLBParser.extractEmbeddedTexture(modelResult.glbData());
                    if (embeddedTexture != null) {
                        textureSampler = new TextureSampler(embeddedTexture, false);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to extract embedded texture: {}", e.getMessage(), e);
                }

                GLBParser.MeshData meshData = GLBParser.parse(modelResult.glbData(), textureSampler);

                // Log bounding box to diagnose axis orientation
                float[] verts = meshData.vertices();
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
                for (int i = 0; i < verts.length; i += 3) {
                    minX = Math.min(minX, verts[i]);     maxX = Math.max(maxX, verts[i]);
                    minY = Math.min(minY, verts[i+1]);   maxY = Math.max(maxY, verts[i+1]);
                    minZ = Math.min(minZ, verts[i+2]);   maxZ = Math.max(maxZ, verts[i+2]);
                }
                float rangeX = maxX - minX, rangeY = maxY - minY, rangeZ = maxZ - minZ;
                LOGGER.info("Mesh bounding box: X=[{},{}] range={}, Y=[{},{}] range={}, Z=[{},{}] range={}",
                    String.format("%.3f", minX), String.format("%.3f", maxX), String.format("%.3f", rangeX),
                    String.format("%.3f", minY), String.format("%.3f", maxY), String.format("%.3f", rangeY),
                    String.format("%.3f", minZ), String.format("%.3f", maxZ), String.format("%.3f", rangeZ));
                LOGGER.info("Tallest axis: {} (if not Y, model needs Y/Z swap)",
                    rangeY >= rangeX && rangeY >= rangeZ ? "Y (correct)" :
                    rangeZ >= rangeX && rangeZ >= rangeY ? "Z (needs swap!)" : "X (unusual)");

                // Step 4: Voxelize
                final TextureSampler finalTextureSampler = textureSampler;
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§d[fal] [4/4] Converting to voxels (" +
                            size + "x" + size + "x" + size + ")...")));

                Voxelizer.VoxelGrid voxelGrid = Voxelizer.voxelize(meshData, size, finalTextureSampler);

                if (voxelGrid.voxels().isEmpty()) {
                    Minecraft.getInstance().execute(() ->
                        source.sendError(Component.literal("§c[fal] Error: Generated model has no voxels!")));
                    return;
                }

                // Step 5: Enter placement preview
                Minecraft.getInstance().execute(() -> {
                    try {
                        PlacementPreview.startPlacement(voxelGrid);

                        source.sendFeedback(Component.literal(
                                "§a[fal] \u2713 §bCRAFT§a generation complete! " + voxelGrid.voxels().size() + " blocks ready."));
                        source.sendFeedback(Component.literal(
                                "§e[fal] Right-click to place, G to rotate, T to tilt!"));
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
                    source.sendError(Component.literal("§c[fal] Error during craft generation: " + errorMsg)));
                LOGGER.error("Error during Craft 3D generation process", e);
            }
        }, "fal-Craft-Thread").start();

        return 1;
    }
}
