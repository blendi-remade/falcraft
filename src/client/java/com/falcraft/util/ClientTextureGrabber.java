package com.falcraft.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClientTextureGrabber {
    private static final Logger LOGGER = LoggerFactory.getLogger("ClientTextureGrabber");
    
    /**
     * Result of a single texture grab operation (legacy, kept for compatibility)
     */
    public static class GrabResult {
        public final File textureFile;
        public final String blockId;
        
        public GrabResult(File textureFile, String blockId) {
            this.textureFile = textureFile;
            this.blockId = blockId;
        }
    }
    
    /**
     * Represents a single texture entry with its file and name
     */
    public static class TextureEntry {
        public final File textureFile;
        public final String textureName;
        
        public TextureEntry(File textureFile, String textureName) {
            this.textureFile = textureFile;
            this.textureName = textureName;
        }
    }
    
    /**
     * Result of grabbing ALL textures from a multi-face block
     */
    public static class MultiGrabResult {
        public final List<TextureEntry> textures;
        public final String blockId;
        public final Path tempDir;
        
        public MultiGrabResult(List<TextureEntry> textures, String blockId, Path tempDir) {
            this.textures = textures;
            this.blockId = blockId;
            this.tempDir = tempDir;
        }
        
        /**
         * Cleans up all temporary texture files
         */
        public void cleanup() {
            for (TextureEntry entry : textures) {
                if (entry.textureFile.exists()) {
                    entry.textureFile.delete();
                }
            }
            if (tempDir != null) {
                tempDir.toFile().delete();
            }
        }
    }

    /**
     * Grabs ALL unique textures from the block the player is looking at.
     * For simple blocks like stone, returns 1 texture.
     * For multi-face blocks like grass, returns all unique textures (top, side, bottom).
     * 
     * @return A MultiGrabResult containing all unique textures, or null if no block is targeted
     */
    public static MultiGrabResult grabAllBlockTextures() throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        
        // Check if player is looking at a block
        HitResult hitResult = minecraft.hitResult;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            LOGGER.warn("Player is not looking at a block");
            return null;
        }
        
        BlockHitResult blockHit = (BlockHitResult) hitResult;
        BlockPos pos = blockHit.getBlockPos();
        
        if (minecraft.level == null) {
            LOGGER.warn("World is null");
            return null;
        }
        
        BlockState blockState = minecraft.level.getBlockState(pos);
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(blockState.getBlock());
        String blockName = blockId.getPath();
        
        LOGGER.info("Player is looking at block: {}", blockId);
        
        // Get the block's baked model (contains all face quads)
        BlockModelShaper modelShaper = minecraft.getBlockRenderer().getBlockModelShaper();
        BlockStateModel model = modelShaper.getBlockModel(blockState);
        
        if (model == null) {
            LOGGER.error("Could not get model for block: {}", blockId);
            return null;
        }
        
        // Collect all unique textures from all faces
        Map<String, TextureAtlasSprite> uniqueSprites = new LinkedHashMap<>();
        RandomSource random = RandomSource.create();

        // Check all 6 directional faces
        List<BlockModelPart> parts = model.collectParts(random);
        for (Direction dir : Direction.values()) {
            for (BlockModelPart part : parts) {
                List<BakedQuad> quads = part.getQuads(dir);
                for (BakedQuad quad : quads) {
                    TextureAtlasSprite sprite = quad.sprite();
                    String textureName = extractTextureName(sprite.contents().name());
                    uniqueSprites.putIfAbsent(textureName, sprite);
                }
            }
        }

        // Check null direction (for overlays, general quads)
        for (BlockModelPart part : parts) {
            List<BakedQuad> quads = part.getQuads(null);
            for (BakedQuad quad : quads) {
                TextureAtlasSprite sprite = quad.sprite();
                String textureName = extractTextureName(sprite.contents().name());
                uniqueSprites.putIfAbsent(textureName, sprite);
            }
        }
        
        LOGGER.info("Found {} unique textures for block {}", uniqueSprites.size(), blockId);
        
        if (uniqueSprites.isEmpty()) {
            LOGGER.error("No textures found for block: {}", blockId);
            return null;
        }
        
        // Create temp directory and extract all textures
        Path tempDir = Files.createTempDirectory("falcraft_textures");
        List<TextureEntry> textureEntries = new ArrayList<>();
        
        for (Map.Entry<String, TextureAtlasSprite> entry : uniqueSprites.entrySet()) {
            String textureName = entry.getKey();
            TextureAtlasSprite sprite = entry.getValue();
            
            NativeImage texture = extractSpriteTexture(sprite);
            if (texture == null) {
                LOGGER.warn("Failed to extract texture: {}", textureName);
                continue;
            }
            
            File outputFile = tempDir.resolve(textureName + ".png").toFile();
            texture.writeToFile(outputFile);
            texture.close();
            
            textureEntries.add(new TextureEntry(outputFile, textureName));
            LOGGER.info("Extracted texture: {} -> {}", textureName, outputFile.getAbsolutePath());
        }
        
        if (textureEntries.isEmpty()) {
            LOGGER.error("Failed to extract any textures for block: {}", blockId);
            tempDir.toFile().delete();
            return null;
        }
        
        return new MultiGrabResult(textureEntries, blockName, tempDir);
    }
    
    /**
     * Extracts the simple texture name from a Identifier path
     * e.g., "minecraft:block/grass_block_top" -> "grass_block_top"
     */
    private static String extractTextureName(Identifier location) {
        String path = location.getPath();
        if (path.contains("/")) {
            return path.substring(path.lastIndexOf("/") + 1);
        }
        return path;
    }

    /**
     * Grabs the texture of the block the player is currently looking at (legacy single-texture method)
     * @return A GrabResult containing the texture file and block ID, or null if no block is targeted
     * @deprecated Use grabAllBlockTextures() instead for proper multi-texture support
     */
    @Deprecated
    public static GrabResult grabTargetedBlockTexture() throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        
        // Check if player is looking at a block
        HitResult hitResult = minecraft.hitResult;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            LOGGER.warn("Player is not looking at a block");
            return null;
        }
        
        BlockHitResult blockHit = (BlockHitResult) hitResult;
        BlockPos pos = blockHit.getBlockPos();
        
        if (minecraft.level == null) {
            LOGGER.warn("World is null");
            return null;
        }
        
        BlockState blockState = minecraft.level.getBlockState(pos);
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(blockState.getBlock());
        
        LOGGER.info("Player is looking at block: {}", blockId);
        
        // Get the block's model and particle texture
        BlockModelShaper modelShaper = minecraft.getBlockRenderer().getBlockModelShaper();
        TextureAtlasSprite sprite = modelShaper.getParticleIcon(blockState);
        
        if (sprite == null) {
            LOGGER.error("Could not get texture sprite for block: {}", blockId);
            return null;
        }
        
        Identifier spriteName = sprite.contents().name();
        LOGGER.info("Found texture sprite: {}", spriteName);
        
        // Extract the texture as a NativeImage
        NativeImage texture = extractSpriteTexture(sprite);
        
        if (texture == null) {
            LOGGER.error("Failed to extract texture for block: {}", blockId);
            return null;
        }
        
        // Save to a temporary file - use the actual sprite name, not the block name
        Path tempDir = Files.createTempDirectory("falcraft_textures");
        
        // Extract just the texture name from the sprite path (e.g., "minecraft:block/dirt" -> "dirt")
        String textureName = spriteName.getPath();
        if (textureName.contains("/")) {
            textureName = textureName.substring(textureName.lastIndexOf("/") + 1);
        }
        
        File outputFile = tempDir.resolve(textureName + ".png").toFile();
        
        texture.writeToFile(outputFile);
        texture.close();
        
        LOGGER.info("Saved texture to: {}", outputFile.getAbsolutePath());
        LOGGER.info("Block: {}, Actual texture: {}", blockId, textureName);
        
        // Return the actual texture name, not the block ID
        return new GrabResult(outputFile, textureName);
    }

    /**
     * Extracts a texture sprite as a NativeImage
     * @param sprite The texture atlas sprite
     * @return A NativeImage containing the sprite's texture
     */
    private static NativeImage extractSpriteTexture(TextureAtlasSprite sprite) {
        try {
            int width = sprite.contents().width();
            int height = sprite.contents().height();
            
            LOGGER.info("Extracting texture: {}x{}", width, height);
            
            // Create a new NativeImage with the sprite's dimensions
            NativeImage image = new NativeImage(width, height, false);
            
            // Access the sprite's animated texture contents to get the first frame
            // The sprite contents stores the full texture which we need to extract
            try {
                // Use reflection to access the mipmap data since it's private
                var field = sprite.contents().getClass().getDeclaredField("byMipLevel");
                field.setAccessible(true);
                NativeImage[] mipmaps = (NativeImage[]) field.get(sprite.contents());
                NativeImage sourceImage = mipmaps[0];
                
                // Copy pixel data from the sprite
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int color = sourceImage.getPixel(x, y);
                        image.setPixel(x, y, color);
                    }
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                LOGGER.error("Failed to access sprite mipmap data", e);
                return null;
            }
            
            return image;
        } catch (Exception e) {
            LOGGER.error("Failed to extract sprite texture", e);
            return null;
        }
    }
}
