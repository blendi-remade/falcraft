package com.falcraft.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Maps RGB colors to the closest matching Minecraft blocks
 */
public class BlockMapper {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlockMapper");
    
    // Color palette mapping block types to their approximate RGB colors
    private static final Map<Block, Integer> BLOCK_PALETTE = new HashMap<>();
    
    // Cache for color lookups
    private static final Map<Integer, BlockState> COLOR_CACHE = new HashMap<>();

    /**
     * When non-null, getClosestBlock() only selects from this subset of palette blocks.
     * Null means "use the full palette" (default behaviour).
     */
    private static Map<Block, Integer> activePalette = null;
    
    static {
        // Curated palette: Clean concrete for primary colors + stone/wood for natural tones
        // Using concrete as primary palette (clean, consistent colors)
        
        // Core 16 colors (Concrete - cleanest and most vibrant)
        BLOCK_PALETTE.put(Blocks.WHITE_CONCRETE, 0xF9FFFE);
        BLOCK_PALETTE.put(Blocks.LIGHT_GRAY_CONCRETE, 0x7D7D73);
        BLOCK_PALETTE.put(Blocks.GRAY_CONCRETE, 0x36393D);
        BLOCK_PALETTE.put(Blocks.BLACK_CONCRETE, 0x080A0F);
        BLOCK_PALETTE.put(Blocks.BROWN_CONCRETE, 0x5C3A24);
        BLOCK_PALETTE.put(Blocks.RED_CONCRETE, 0x8E2121);
        BLOCK_PALETTE.put(Blocks.ORANGE_CONCRETE, 0xE06101);
        BLOCK_PALETTE.put(Blocks.YELLOW_CONCRETE, 0xF1AF15);
        BLOCK_PALETTE.put(Blocks.LIME_CONCRETE, 0x5EA818);
        BLOCK_PALETTE.put(Blocks.GREEN_CONCRETE, 0x495B24);
        BLOCK_PALETTE.put(Blocks.CYAN_CONCRETE, 0x157788);
        BLOCK_PALETTE.put(Blocks.LIGHT_BLUE_CONCRETE, 0x2389C6);
        BLOCK_PALETTE.put(Blocks.BLUE_CONCRETE, 0x2C2E8F);
        BLOCK_PALETTE.put(Blocks.PURPLE_CONCRETE, 0x64209C);
        BLOCK_PALETTE.put(Blocks.MAGENTA_CONCRETE, 0xA9309F);
        BLOCK_PALETTE.put(Blocks.PINK_CONCRETE, 0xD5658F);
        
        // Essential stone blocks (for gray/tan range)
        BLOCK_PALETTE.put(Blocks.STONE, 0x808080);
        BLOCK_PALETTE.put(Blocks.COBBLESTONE, 0x7F7F7F);
        BLOCK_PALETTE.put(Blocks.STONE_BRICKS, 0x7A7A7A);
        BLOCK_PALETTE.put(Blocks.ANDESITE, 0x878787);
        BLOCK_PALETTE.put(Blocks.DIORITE, 0xE4E4E4);
        BLOCK_PALETTE.put(Blocks.SMOOTH_STONE, 0xA0A0A0);
        BLOCK_PALETTE.put(Blocks.POLISHED_ANDESITE, 0x848484);
        
        // Essential wood blocks (for brown/tan range)
        BLOCK_PALETTE.put(Blocks.OAK_PLANKS, 0xB18962);
        BLOCK_PALETTE.put(Blocks.BIRCH_PLANKS, 0xD2BC7C);
        BLOCK_PALETTE.put(Blocks.DARK_OAK_PLANKS, 0x44331C);
        BLOCK_PALETTE.put(Blocks.GRANITE, 0x9A6E53);
        BLOCK_PALETTE.put(Blocks.BRICKS, 0x8F5D4B);
        
        // Terracotta for muted/earthy tones (fills gaps between concrete and stone)
        BLOCK_PALETTE.put(Blocks.WHITE_TERRACOTTA, 0xD1B1A1);
        BLOCK_PALETTE.put(Blocks.LIGHT_GRAY_TERRACOTTA, 0x876B62);
        BLOCK_PALETTE.put(Blocks.GRAY_TERRACOTTA, 0x392A23);
        
        // Dark/black blocks with texture (avoiding flat concrete)
        BLOCK_PALETTE.put(Blocks.COAL_BLOCK, 0x1A1919);  // Very dark
        BLOCK_PALETTE.put(Blocks.BLACKSTONE, 0x2A2333);  // Dark with natural texture
        BLOCK_PALETTE.put(Blocks.POLISHED_BLACKSTONE, 0x36313D); // Smooth dark
        BLOCK_PALETTE.put(Blocks.DEEPSLATE, 0x4F4F51);   // Layered dark gray
        BLOCK_PALETTE.put(Blocks.DEEPSLATE_TILES, 0x353538); // Tiled very dark
        BLOCK_PALETTE.put(Blocks.DEEPSLATE_BRICKS, 0x434343); // Dark gray with brick pattern
        BLOCK_PALETTE.put(Blocks.COBBLED_DEEPSLATE, 0x555555); // Rough dark cobbled stone
        BLOCK_PALETTE.put(Blocks.DARK_PRISMARINE, 0x395A4E); // Dark teal-gray stone
        
        // White/light blocks with texture variety
        BLOCK_PALETTE.put(Blocks.QUARTZ_BLOCK, 0xE8E5DD); // Bright white
        BLOCK_PALETTE.put(Blocks.CALCITE, 0xE3E4DC);      // Crystalline white
        BLOCK_PALETTE.put(Blocks.BONE_BLOCK, 0xE3DCC6);   // Cream with lines
        BLOCK_PALETTE.put(Blocks.WHITE_WOOL, 0xE9ECEC);   // Soft fabric white
        BLOCK_PALETTE.put(Blocks.MUSHROOM_STEM, 0xC9AE9D); // Pale tan with spots
        
        // Special blocks for unique colors
        BLOCK_PALETTE.put(Blocks.SANDSTONE, 0xE3DBB0);    // Warm tan
        BLOCK_PALETTE.put(Blocks.RED_SANDSTONE, 0xBF6330); // Orange-brown
        
        // ===== EXPANDED PALETTE =====
        
        // All remaining terracotta (unique earthy muted tones)
        BLOCK_PALETTE.put(Blocks.ORANGE_TERRACOTTA, 0xA05325);
        BLOCK_PALETTE.put(Blocks.BROWN_TERRACOTTA, 0x4D3224);
        BLOCK_PALETTE.put(Blocks.RED_TERRACOTTA, 0x8F3D2E);
        BLOCK_PALETTE.put(Blocks.YELLOW_TERRACOTTA, 0xBA8523);
        BLOCK_PALETTE.put(Blocks.CYAN_TERRACOTTA, 0x575B5B);
        BLOCK_PALETTE.put(Blocks.BLUE_TERRACOTTA, 0x4A3B5B);
        BLOCK_PALETTE.put(Blocks.PINK_TERRACOTTA, 0xA14E4E);
        BLOCK_PALETTE.put(Blocks.MAGENTA_TERRACOTTA, 0x95576C);
        BLOCK_PALETTE.put(Blocks.PURPLE_TERRACOTTA, 0x764556);
        BLOCK_PALETTE.put(Blocks.GREEN_TERRACOTTA, 0x4C532A);
        BLOCK_PALETTE.put(Blocks.LIME_TERRACOTTA, 0x677534);
        BLOCK_PALETTE.put(Blocks.LIGHT_BLUE_TERRACOTTA, 0x706C89);
        BLOCK_PALETTE.put(Blocks.BLACK_TERRACOTTA, 0x251610);
        
        // Copper oxidation stages (unique teal/green progression)
        BLOCK_PALETTE.put(Blocks.COPPER_BLOCK, 0xC06A4D);
        BLOCK_PALETTE.put(Blocks.EXPOSED_COPPER, 0xA07D5D);
        BLOCK_PALETTE.put(Blocks.WEATHERED_COPPER, 0x6D9466);
        BLOCK_PALETTE.put(Blocks.OXIDIZED_COPPER, 0x53A384);
        
        // More wood types (fills brown/tan spectrum)
        BLOCK_PALETTE.put(Blocks.SPRUCE_PLANKS, 0x73563A);
        BLOCK_PALETTE.put(Blocks.JUNGLE_PLANKS, 0xB88856);
        BLOCK_PALETTE.put(Blocks.ACACIA_PLANKS, 0xB05E3C);
        BLOCK_PALETTE.put(Blocks.MANGROVE_PLANKS, 0x773636);
        BLOCK_PALETTE.put(Blocks.CHERRY_PLANKS, 0xE4B4A8);
        BLOCK_PALETTE.put(Blocks.CRIMSON_PLANKS, 0x6C3A4A);
        BLOCK_PALETTE.put(Blocks.WARPED_PLANKS, 0x2B6D64);
        
        // Unique color blocks
        BLOCK_PALETTE.put(Blocks.AMETHYST_BLOCK, 0x8B6AA6);
        BLOCK_PALETTE.put(Blocks.PRISMARINE, 0x63A293);
        BLOCK_PALETTE.put(Blocks.PRISMARINE_BRICKS, 0x5BA496);
        BLOCK_PALETTE.put(Blocks.SEA_LANTERN, 0xACDBC5);
        BLOCK_PALETTE.put(Blocks.MUD_BRICKS, 0x8B6B4D);
        BLOCK_PALETTE.put(Blocks.PACKED_MUD, 0x8E7259);
        BLOCK_PALETTE.put(Blocks.TUFF, 0x6C6C66);
        BLOCK_PALETTE.put(Blocks.MOSS_BLOCK, 0x4F6633);
        
        // Nether blocks (unique dark/warm tones)
        BLOCK_PALETTE.put(Blocks.NETHER_BRICKS, 0x2C151A);
        BLOCK_PALETTE.put(Blocks.RED_NETHER_BRICKS, 0x45080A);
        BLOCK_PALETTE.put(Blocks.SHROOMLIGHT, 0xF09B4E);
        BLOCK_PALETTE.put(Blocks.NETHERRACK, 0x6D3636);
        BLOCK_PALETTE.put(Blocks.WARPED_WART_BLOCK, 0x167879);
        BLOCK_PALETTE.put(Blocks.CRIMSON_NYLIUM, 0x8B1F1F);
        
        // End blocks
        BLOCK_PALETTE.put(Blocks.END_STONE, 0xDBDCA6);
        BLOCK_PALETTE.put(Blocks.END_STONE_BRICKS, 0xDBDEA7);
        BLOCK_PALETTE.put(Blocks.PURPUR_BLOCK, 0xA87AA4);
        BLOCK_PALETTE.put(Blocks.PURPUR_PILLAR, 0xAB7FA7);
        
        // Wool for soft tones (more muted than concrete)
        BLOCK_PALETTE.put(Blocks.BROWN_WOOL, 0x724728);
        BLOCK_PALETTE.put(Blocks.GRAY_WOOL, 0x3E4447);
        BLOCK_PALETTE.put(Blocks.LIGHT_GRAY_WOOL, 0x8E8E86);
        BLOCK_PALETTE.put(Blocks.CYAN_WOOL, 0x158991);
        BLOCK_PALETTE.put(Blocks.PURPLE_WOOL, 0x7B2BAD);
        BLOCK_PALETTE.put(Blocks.BLUE_WOOL, 0x353A9E);
        BLOCK_PALETTE.put(Blocks.GREEN_WOOL, 0x546D1B);
        BLOCK_PALETTE.put(Blocks.RED_WOOL, 0xA12722);
        BLOCK_PALETTE.put(Blocks.ORANGE_WOOL, 0xF07613);
        BLOCK_PALETTE.put(Blocks.YELLOW_WOOL, 0xF8C627);
        BLOCK_PALETTE.put(Blocks.LIME_WOOL, 0x70B919);
        BLOCK_PALETTE.put(Blocks.PINK_WOOL, 0xED8DAC);
        BLOCK_PALETTE.put(Blocks.MAGENTA_WOOL, 0xBD44B3);
        BLOCK_PALETTE.put(Blocks.LIGHT_BLUE_WOOL, 0x3AAFD9);
        BLOCK_PALETTE.put(Blocks.BLACK_WOOL, 0x141519);
        
    }
    
    /**
     * Gets the closest matching Minecraft block for an RGB color.
     * If a material filter has been set via {@link #setMaterialFilter}, only those
     * blocks are considered; otherwise the full built-in palette is used.
     *
     * @param rgb The color as an RGB integer (0xRRGGBB)
     * @return The closest matching block state
     */
    public static BlockState getClosestBlock(int rgb) {
        // Check cache first
        if (COLOR_CACHE.containsKey(rgb)) {
            return COLOR_CACHE.get(rgb);
        }

        Block closestBlock = Blocks.WHITE_WOOL;
        double minDistance = Double.MAX_VALUE;

        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        double[] labTarget = rgbToLab(r, g, b);

        // Use the filtered palette if one has been set, otherwise the full palette
        Map<Block, Integer> searchPalette = (activePalette != null) ? activePalette : BLOCK_PALETTE;

        for (Map.Entry<Block, Integer> entry : searchPalette.entrySet()) {
            int blockColor = entry.getValue();
            int br = (blockColor >> 16) & 0xFF;
            int bg = (blockColor >> 8) & 0xFF;
            int bb = blockColor & 0xFF;

            double[] labBlock = rgbToLab(br, bg, bb);

            // CIE76 Delta-E
            double distance = Math.sqrt(
                Math.pow(labTarget[0] - labBlock[0], 2) +
                Math.pow(labTarget[1] - labBlock[1], 2) +
                Math.pow(labTarget[2] - labBlock[2], 2)
            );

            if (distance < minDistance) {
                minDistance = distance;
                closestBlock = entry.getKey();
            }
        }

        BlockState blockState = closestBlock.defaultBlockState();
        COLOR_CACHE.put(rgb, blockState);
        return blockState;
    }
    
    /**
     * Converts RGB to LAB color space for perceptually uniform color matching
     * @param r Red component (0-255)
     * @param g Green component (0-255)
     * @param b Blue component (0-255)
     * @return LAB values [L, a, b]
     */
    private static double[] rgbToLab(int r, int g, int b) {
        // Convert RGB to XYZ
        double var_R = r / 255.0;
        double var_G = g / 255.0;
        double var_B = b / 255.0;
        
        // Apply gamma correction
        if (var_R > 0.04045) var_R = Math.pow((var_R + 0.055) / 1.055, 2.4);
        else var_R = var_R / 12.92;
        if (var_G > 0.04045) var_G = Math.pow((var_G + 0.055) / 1.055, 2.4);
        else var_G = var_G / 12.92;
        if (var_B > 0.04045) var_B = Math.pow((var_B + 0.055) / 1.055, 2.4);
        else var_B = var_B / 12.92;
        
        var_R = var_R * 100;
        var_G = var_G * 100;
        var_B = var_B * 100;
        
        // Observer = 2°, Illuminant = D65
        double X = var_R * 0.4124 + var_G * 0.3576 + var_B * 0.1805;
        double Y = var_R * 0.2126 + var_G * 0.7152 + var_B * 0.0722;
        double Z = var_R * 0.0193 + var_G * 0.1192 + var_B * 0.9505;
        
        // Convert XYZ to LAB
        double var_X = X / 95.047;
        double var_Y = Y / 100.000;
        double var_Z = Z / 108.883;
        
        if (var_X > 0.008856) var_X = Math.pow(var_X, 1.0 / 3.0);
        else var_X = (7.787 * var_X) + (16.0 / 116.0);
        if (var_Y > 0.008856) var_Y = Math.pow(var_Y, 1.0 / 3.0);
        else var_Y = (7.787 * var_Y) + (16.0 / 116.0);
        if (var_Z > 0.008856) var_Z = Math.pow(var_Z, 1.0 / 3.0);
        else var_Z = (7.787 * var_Z) + (16.0 / 116.0);
        
        double L = (116 * var_Y) - 16;
        double a = 500 * (var_X - var_Y);
        double b_lab = 200 * (var_Y - var_Z);
        
        return new double[]{L, a, b_lab};
    }
    
    /**
     * Gets the RGB color associated with a block.
     * @param block The block to get the color for
     * @return The RGB color (0xRRGGBB), or 0x808080 (gray) if not in palette
     */
    public static int getBlockColor(Block block) {
        Integer color = BLOCK_PALETTE.get(block);
        return color != null ? color : 0x808080;
    }
    
    /**
     * Returns all block registry IDs present in the built-in palette, e.g. "minecraft:stone".
     * Used to populate autocomplete suggestions for the -m / --materials flag.
     */
    public static List<String> getAllPaletteBlockIds() {
        return BLOCK_PALETTE.keySet().stream()
            .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * Restricts {@link #getClosestBlock} to the given block IDs.
     * Blocks not in the built-in palette are warned about and skipped.
     * Pass an empty or null list to reset to the full palette.
     *
     * @param blockIds Minecraft resource-location strings, e.g. "minecraft:stone"
     * @return List of block IDs that were not recognised / not in the palette
     */
    public static List<String> setMaterialFilter(List<String> blockIds) {
        COLOR_CACHE.clear();

        if (blockIds == null || blockIds.isEmpty()) {
            activePalette = null;
            return Collections.emptyList();
        }

        Map<Block, Integer> filtered = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();

        for (String id : blockIds) {
            String trimmed = id.trim();
            if (trimmed.isEmpty()) continue;

            ResourceLocation loc = ResourceLocation.tryParse(trimmed);
            if (loc == null) {
                unknown.add(trimmed);
                continue;
            }

            // Look the block up in the game registry
            Block block = BuiltInRegistries.BLOCK.get(loc);
            if (block == null || block == Blocks.AIR) {
                // AIR is the registry's "not found" sentinel
                unknown.add(trimmed);
                continue;
            }

            Integer paletteColor = BLOCK_PALETTE.get(block);
            if (paletteColor == null) {
                // Block is valid but has no colour entry — assign its average texture
                // colour as 0x808080 (neutral grey) so it still participates.
                LOGGER.warn("Block {} is not in the built-in palette; using grey fallback", trimmed);
                filtered.put(block, 0x808080);
            } else {
                filtered.put(block, paletteColor);
            }
        }

        if (filtered.isEmpty()) {
            LOGGER.warn("Material filter produced no usable blocks; reverting to full palette");
            activePalette = null;
        } else {
            activePalette = filtered;
        }

        return unknown;
    }

    /**
     * Resets the material filter so the full palette is used again.
     */
    public static void clearMaterialFilter() {
        activePalette = null;
        COLOR_CACHE.clear();
    }

    /**
     * Clears the color cache
     */
    public static void clearCache() {
        COLOR_CACHE.clear();
    }
}

