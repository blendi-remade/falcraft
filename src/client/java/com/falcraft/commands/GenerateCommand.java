package com.falcraft.commands;

import com.falcraft.util.BlockMapper;
import com.falcraft.util.FalAPI;
import com.falcraft.util.GLBParser;
import com.falcraft.util.PlacementPreview;
import com.falcraft.util.TextureSampler;
import com.falcraft.util.Voxelizer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Usage:
 *   /fal generate <size> <prompt>
 *   /fal generate <size> materials <block,list> <prompt>
 *   /fal generate legacy <size> <prompt>
 *   /fal generate legacy <size> materials <block,list> <prompt>
 *
 * "materials" is an optional subcommand.  Its argument is a single greedy
 * string of the form:  minecraft:stone,minecraft:oak_log a prompt here
 * Everything up to the first space that follows the comma-separated block
 * list is parsed as block IDs; the rest is the prompt.
 *
 * This approach is necessary because Brigadier cannot parse two consecutive
 * greedy/space-delimited arguments in the same branch.
 */
public class GenerateCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("GenerateCommand");

    /**
     * Matches a single block ID token: optional-namespace:name
     * e.g. "minecraft:stone" or "stone" (bare names are also valid in-game).
     */
    private static final Pattern BLOCK_ID_TOKEN = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    // -------------------------------------------------------------------------
    // Suggestion providers
    // -------------------------------------------------------------------------

    private static final SuggestionProvider<FabricClientCommandSource> SIZE_SUGGESTIONS = (ctx, builder) -> {
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

    /**
     * Tab-completes the combined "block_list prompt" greedy argument.
     *
     * While the cursor is still inside the block list (no space present, or
     * the text so far looks entirely like comma-separated IDs) we suggest
     * block names.  Once the user has typed a space after the last ID we
     * stop suggesting so they can type the prompt freely.
     */
    private static final SuggestionProvider<FabricClientCommandSource> MATERIALS_GREEDY_SUGGESTIONS =
            (ctx, builder) -> {
        String remaining = builder.getRemaining();

        // If there is a space, the user has moved on to the prompt — stop suggesting.
        if (remaining.contains(" ")) {
            return builder.buildFuture();
        }

        // Complete the last comma-separated token.
        int commaIdx = remaining.lastIndexOf(',');
        String prefix  = commaIdx >= 0 ? remaining.substring(0, commaIdx + 1) : "";
        String current = commaIdx >= 0 ? remaining.substring(commaIdx + 1)    : remaining;

        for (String id : BlockMapper.getAllPaletteBlockIds()) {
            if (id.toLowerCase().startsWith(current.toLowerCase())) {
                builder.suggest(prefix + id);
            }
        }
        return builder.buildFuture();
    };

    // -------------------------------------------------------------------------
    // Command registration
    // -------------------------------------------------------------------------

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("fal")
            .then(literal("generate")
                .then(argument("size", IntegerArgumentType.integer(16, 128))
                    .suggests(SIZE_SUGGESTIONS)
                    // /fal generate <size> materials <block,list and prompt as greedy>
                    .then(literal("materials")
                        .then(argument("materials_and_prompt", StringArgumentType.greedyString())
                            .suggests(MATERIALS_GREEDY_SUGGESTIONS)
                            .executes(ctx -> {
                                MaterialsAndPrompt mp = splitMaterialsAndPrompt(
                                    StringArgumentType.getString(ctx, "materials_and_prompt"),
                                    ctx.getSource());
                                return mp == null ? 0 : execute(ctx, mp.prompt, mp.materials);
                            })))
                    // /fal generate <size> <prompt>  (no material filter)
                    .then(argument("prompt", StringArgumentType.greedyString())
                        .executes(ctx -> execute(ctx,
                            StringArgumentType.getString(ctx, "prompt"), null))))

                .then(literal("legacy")
                    .then(argument("size", IntegerArgumentType.integer(16, 128))
                        .suggests(SIZE_SUGGESTIONS)
                        .then(literal("materials")
                            .then(argument("materials_and_prompt", StringArgumentType.greedyString())
                                .suggests(MATERIALS_GREEDY_SUGGESTIONS)
                                .executes(ctx -> {
                                    MaterialsAndPrompt mp = splitMaterialsAndPrompt(
                                        StringArgumentType.getString(ctx, "materials_and_prompt"),
                                        ctx.getSource());
                                    return mp == null ? 0 : executeLegacy(ctx, mp.prompt, mp.materials);
                                })))
                        .then(argument("prompt", StringArgumentType.greedyString())
                            .executes(ctx -> executeLegacy(ctx,
                                StringArgumentType.getString(ctx, "prompt"), null)))))));
    }

    // -------------------------------------------------------------------------
    // Parsing helpers
    // -------------------------------------------------------------------------

    private record MaterialsAndPrompt(List<String> materials, String prompt) {}

    /**
     * Splits a string like "minecraft:stone,minecraft:oak_log a house on a hill"
     * into a material list and a prompt.
     *
     * Strategy: the block-list is the leading run of comma-separated tokens that
     * each match namespace:name.  The first space after that run ends the list.
     *
     * Returns null (and sends an error) if no prompt text is found after the list.
     */
    private static MaterialsAndPrompt splitMaterialsAndPrompt(String raw, FabricClientCommandSource source) {
        // Find the first space — everything before it is the block list, after is the prompt.
        int spaceIdx = raw.indexOf(' ');
        if (spaceIdx < 0) {
            source.sendError(Component.literal(
                "§c[fal] Usage: materials <block,list> <prompt>   " +
                "Example: materials minecraft:stone,minecraft:cobblestone a castle"));
            return null;
        }

        String blockPart  = raw.substring(0, spaceIdx).trim();
        String promptPart = raw.substring(spaceIdx + 1).trim();

        if (promptPart.isEmpty()) {
            source.sendError(Component.literal("§c[fal] Prompt cannot be empty after the block list."));
            return null;
        }

        List<String> materials = Arrays.asList(blockPart.split(","));
        return new MaterialsAndPrompt(materials, promptPart);
    }

    /**
     * Apply (or clear) the material filter and warn about unrecognised IDs.
     */
    private static void applyMaterialFilter(List<String> materials, FabricClientCommandSource source) {
        if (materials == null || materials.isEmpty()) {
            BlockMapper.clearMaterialFilter();
            return;
        }
        List<String> unknown = BlockMapper.setMaterialFilter(materials);
        source.sendFeedback(Component.literal(
            "§e[fal] Material filter: " + String.join(", ", materials)));
        if (!unknown.isEmpty()) {
            source.sendFeedback(Component.literal(
                "§6[fal] Warning: unrecognised block IDs ignored: " + String.join(", ", unknown)));
        }
    }

    // -------------------------------------------------------------------------
    // Default generation (Z-Image + SAM-3D)
    // -------------------------------------------------------------------------

    private static int execute(CommandContext<FabricClientCommandSource> ctx,
                               String prompt,
                               List<String> materials) {
        int size = IntegerArgumentType.getInteger(ctx, "size");
        FabricClientCommandSource source = ctx.getSource();

        source.sendFeedback(Component.literal(
            "§e[fal] Starting 3D generation (" + size + "x" + size + "x" + size + ")"));
        source.sendFeedback(Component.literal("§e[fal] Prompt: \"" + prompt + "\""));
        source.sendFeedback(Component.literal("§e[fal] Using Z-Image + SAM-3D (about 30 seconds)"));
        if (materials != null && !materials.isEmpty()) {
            source.sendFeedback(Component.literal(
                "§e[fal] Material filter: " + String.join(", ", materials)));
        }

        new Thread(() -> {
            try {
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§e[fal] [1/4] Generating 2D image...")));

                FalAPI falApi = new FalAPI();
                FalAPI.ModelResult modelResult = falApi.generateModelFast(prompt);

                LOGGER.info("Received GLB model ({} bytes)", modelResult.glbData().length);
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§e[fal] [2/4] 3D model generated! Processing...")));

                TextureSampler textureSampler = null;
                try {
                    byte[] embeddedTexture = GLBParser.extractEmbeddedTexture(modelResult.glbData());
                    if (embeddedTexture != null) {
                        textureSampler = new TextureSampler(embeddedTexture);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to extract embedded texture: {}", e.getMessage(), e);
                }

                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§e[fal] [3/4] Parsing 3D model...")));

                GLBParser.MeshData meshData = GLBParser.parse(modelResult.glbData(), textureSampler);

                final TextureSampler finalSampler = textureSampler;
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§e[fal] [4/4] Converting to voxels...")));

                Voxelizer.VoxelGrid voxelGrid = Voxelizer.voxelize(meshData, size, finalSampler);

                if (voxelGrid.voxels().isEmpty()) {
                    Minecraft.getInstance().execute(() ->
                        source.sendError(Component.literal("§c[fal] Error: Generated model has no voxels!")));
                    return;
                }

                Minecraft.getInstance().execute(() -> {
                    try {
                        PlacementPreview.startPlacementWithMaterials(voxelGrid, materials);
                        source.sendFeedback(Component.literal(
                            "§a[fal] ✓ Generation complete! " + voxelGrid.voxels().size() + " blocks ready."));
                        source.sendFeedback(Component.literal("§e[fal] Right-click to place, G to rotate!"));
                    } catch (Exception e) {
                        source.sendError(Component.literal("§c[fal] Error preparing placement: " + e.getMessage()));
                        LOGGER.error("Error preparing placement", e);
                    }
                });

            } catch (IllegalStateException e) {
                Minecraft.getInstance().execute(() ->
                    source.sendError(Component.literal(
                        "§c[fal] Error: API key not configured. Use /fal setkey <key>")));
            } catch (Exception e) {
                Minecraft.getInstance().execute(() ->
                    source.sendError(Component.literal("§c[fal] Error during generation: " + e.getMessage())));
                LOGGER.error("Error during 3D generation", e);
            }
        }, "fal-Generate-Thread").start();

        return 1;
    }

    // -------------------------------------------------------------------------
    // Legacy generation (Meshy-6)
    // -------------------------------------------------------------------------

    private static int executeLegacy(CommandContext<FabricClientCommandSource> ctx,
                                     String prompt,
                                     List<String> materials) {
        int size = IntegerArgumentType.getInteger(ctx, "size");
        FabricClientCommandSource source = ctx.getSource();

        source.sendFeedback(Component.literal(
            "§e[fal] Starting §7LEGACY§e 3D generation (" + size + "x" + size + "x" + size + ")"));
        source.sendFeedback(Component.literal("§e[fal] Prompt: \"" + prompt + "\""));
        source.sendFeedback(Component.literal("§e[fal] Using Meshy-6 pipeline (about 7 minutes)"));
        if (materials != null && !materials.isEmpty()) {
            source.sendFeedback(Component.literal(
                "§e[fal] Material filter: " + String.join(", ", materials)));
        }

        new Thread(() -> {
            try {
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§e[fal] Generating 3D model with AI...")));

                FalAPI falApi = new FalAPI();
                FalAPI.ModelResult modelResult = falApi.generateModel(prompt);

                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§e[fal] Model generated! Processing...")));

                TextureSampler textureSampler = null;
                try {
                    byte[] embeddedTexture = GLBParser.extractEmbeddedTexture(modelResult.glbData());
                    if (embeddedTexture != null) {
                        textureSampler = new TextureSampler(embeddedTexture);
                        Minecraft.getInstance().execute(() ->
                            source.sendFeedback(Component.literal("§e[fal] Embedded texture extracted!")));
                    } else {
                        Minecraft.getInstance().execute(() ->
                            source.sendFeedback(Component.literal("§6[fal] No embedded texture, using vertex colors")));
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to extract embedded texture: {}", e.getMessage(), e);
                }

                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§e[fal] Parsing 3D model...")));

                GLBParser.MeshData meshData = GLBParser.parse(modelResult.glbData(), textureSampler);

                final TextureSampler finalSampler = textureSampler;
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§e[fal] Converting to voxels...")));

                Voxelizer.VoxelGrid voxelGrid = Voxelizer.voxelize(meshData, size, finalSampler);
                LOGGER.info("Voxelized mesh: {} voxels", voxelGrid.voxels().size());

                if (voxelGrid.voxels().isEmpty()) {
                    Minecraft.getInstance().execute(() ->
                        source.sendError(Component.literal("§c[fal] Error: Generated model has no voxels!")));
                    return;
                }

                Minecraft.getInstance().execute(() -> {
                    try {
                        PlacementPreview.startPlacementWithMaterials(voxelGrid, materials);
                        source.sendFeedback(Component.literal(
                            "§a[fal] ✓ §7LEGACY§a generation complete! " +
                            voxelGrid.voxels().size() + " blocks ready."));
                        source.sendFeedback(Component.literal("§e[fal] Right-click to place, G to rotate!"));
                    } catch (Exception e) {
                        source.sendError(Component.literal("§c[fal] Error preparing placement: " + e.getMessage()));
                        LOGGER.error("Error preparing placement", e);
                    }
                });

            } catch (IllegalStateException e) {
                Minecraft.getInstance().execute(() ->
                    source.sendError(Component.literal(
                        "§c[fal] Error: API key not configured. Use /fal setkey <key>")));
            } catch (Exception e) {
                Minecraft.getInstance().execute(() ->
                    source.sendError(Component.literal("§c[fal] Error during legacy generation: " + e.getMessage())));
                LOGGER.error("Error during LEGACY 3D generation", e);
            }
        }, "fal-LegacyGenerate-Thread").start();

        return 1;
    }
}
