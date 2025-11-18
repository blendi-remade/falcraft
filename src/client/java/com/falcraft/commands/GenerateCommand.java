package com.falcraft.commands;

import com.falcraft.util.BlockPlacer;
import com.falcraft.util.HunyuanAPI;
import com.falcraft.util.GLBParser;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Command to generate 3D models from text prompts using Tencent Hunyuan 3D
 * Usage: /fal generate <size> <prompt>
 * Size: 16-128 (recommended: 32=fast, 48=balanced, 64=detailed)
 */
public class GenerateCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("GenerateCommand");
    
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("fal")
                .then(literal("generate")
                        .then(argument("size", IntegerArgumentType.integer(16, 128))
                                .then(argument("prompt", StringArgumentType.greedyString())
                                        .executes(GenerateCommand::execute)))));
    }
    
    private static int execute(CommandContext<FabricClientCommandSource> context) {
        int size = IntegerArgumentType.getInteger(context, "size");
        String prompt = StringArgumentType.getString(context, "prompt");
        FabricClientCommandSource source = context.getSource();
        
        // Send initial feedback
        source.sendFeedback(Component.literal("§e[fal] Starting 3D model generation (" + size + "x" + size + "x" + size + ")"));
        source.sendFeedback(Component.literal("§e[fal] Prompt: \"" + prompt + "\""));
        source.sendFeedback(Component.literal("§e[fal] This may take several minutes..."));
        
        // Run the generation process asynchronously to avoid blocking the game thread
        new Thread(() -> {
            try {
                LOGGER.info("Starting 3D model generation process...");
                
                // Step 1: Call Tencent Hunyuan API to generate 3D model
                Minecraft.getInstance().execute(() -> 
                    source.sendFeedback(Component.literal("§e[fal] Generating 3D model with Tencent Hunyuan...")));
                
                HunyuanAPI hunyuanApi = new HunyuanAPI();
                HunyuanAPI.ModelResult modelResult = hunyuanApi.generateModel(prompt);
                
                LOGGER.info("Received GLB model from Tencent Hunyuan ({} bytes)", modelResult.glbData().length);
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§e[fal] Model generated! Processing...")));
                
                // Step 2: Extract embedded texture from GLB (more reliable than external texture_urls)
                TextureSampler textureSampler = null;
                try {
                    Minecraft.getInstance().execute(() ->
                        source.sendFeedback(Component.literal("§e[fal] Extracting texture from GLB...")));
                    
                    byte[] embeddedTexture = GLBParser.extractEmbeddedTexture(modelResult.glbData());
                    if (embeddedTexture != null) {
                        textureSampler = new TextureSampler(embeddedTexture);
                        LOGGER.info("Loaded embedded texture from GLB: {} bytes", embeddedTexture.length);
                        
                        // DEBUG: Save embedded texture to disk for inspection
                        try {
                            Path debugPath = Paths.get("debug_texture_embedded.jpg");
                            Files.write(debugPath, embeddedTexture);
                            LOGGER.info("DEBUG: Saved embedded texture to: {}", debugPath.toAbsolutePath());
                        } catch (Exception ex) {
                            LOGGER.warn("Could not save debug texture: {}", ex.getMessage());
                        }
                        
                        Minecraft.getInstance().execute(() ->
                            source.sendFeedback(Component.literal("§e[fal] Embedded texture extracted!")));
                    } else {
                        LOGGER.warn("No embedded texture found in GLB");
                        Minecraft.getInstance().execute(() ->
                            source.sendFeedback(Component.literal("§6[fal] No embedded texture, will use vertex colors")));
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to extract embedded texture: {}", e.getMessage(), e);
                    Minecraft.getInstance().execute(() ->
                        source.sendFeedback(Component.literal("§6[fal] Could not extract texture")));
                }
                
                // Note: Tencent Hunyuan embeds textures in GLB, no external texture URLs
                
                // Step 3: Parse GLB file with texture sampling
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§e[fal] Parsing 3D model...")));
                
                GLBParser.MeshData meshData = GLBParser.parse(modelResult.glbData(), textureSampler);
                LOGGER.info("Parsed GLB: {} vertices, {} indices", 
                        meshData.vertices().length / 3, meshData.indices().length);
                
                // Step 4: Voxelize the mesh
                Minecraft.getInstance().execute(() ->
                    source.sendFeedback(Component.literal("§e[fal] Converting to voxels (" + 
                            size + "x" + size + "x" + size + ")...")));
                
                Voxelizer.VoxelGrid voxelGrid = Voxelizer.voxelize(meshData, size);
                LOGGER.info("Voxelized mesh: {} voxels", voxelGrid.voxels().size());
                
                if (voxelGrid.voxels().isEmpty()) {
                    Minecraft.getInstance().execute(() ->
                        source.sendError(Component.literal("§c[fal] Error: Generated model has no voxels!")));
                    return;
                }
                
                // Step 5: Place blocks in the world (MUST run on main thread)
                Minecraft.getInstance().execute(() -> {
                    try {
                        source.sendFeedback(Component.literal("§e[fal] Placing blocks in world..."));
                        
                        int blocksPlaced = BlockPlacer.placeVoxelGrid(voxelGrid);
                        
                        source.sendFeedback(Component.literal(
                                "§a[fal] ✓ 3D model generated successfully! Placed " + blocksPlaced + " blocks."));
                        LOGGER.info("3D generation process completed successfully");
                        
                    } catch (Exception e) {
                        String errorMsg = e.getMessage();
                        source.sendError(Component.literal("§c[fal] Error placing blocks: " + errorMsg));
                        LOGGER.error("Error placing blocks", e);
                    }
                });
                
            } catch (IllegalStateException e) {
                // Handle missing API credentials
                Minecraft.getInstance().execute(() ->
                    source.sendError(Component.literal("§c[fal] Error: Tencent Cloud credentials not found in .env file!")));
                LOGGER.error("Tencent Cloud credentials not set", e);
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                Minecraft.getInstance().execute(() ->
                    source.sendError(Component.literal("§c[fal] Error during generation: " + errorMsg)));
                LOGGER.error("Error during 3D generation process", e);
            }
        }, "fal-Generate-Thread").start();
        
        return 1;
    }
}

