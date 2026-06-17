package com.falcraft.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Builds a real vanilla {@code filled_map} item that carries an AI-generated image:
 * - the map's 128x128 pixels are a dithered low-res copy of the image, so the item
 *   shows the picture when held in hand (vanilla map rendering),
 * - the stack is tagged with a falcraft id (CUSTOM_DATA) linking it to the full-res
 *   PNG on disk, which the in-world canvas renderer uses for placement.
 *
 * The map is the inventory token; our textured quad is still the actual display.
 * Server-side (single-player) only, since it mints real map SavedData.
 */
public class MapImageFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger("MapImageFactory");
    public static final String ID_KEY = "falcraft_id";
    private static final int MAP_SIZE = 128;

    /**
     * Creates a tagged filled_map item from the given PNG bytes on the server level.
     * Must be called on the server thread.
     */
    public static ItemStack createTaggedMap(ServerLevel level, byte[] pngBytes, String falcraftId) throws IOException {
        byte[] colors = new byte[MAP_SIZE * MAP_SIZE];

        try (NativeImage img = NativeImage.read(new ByteArrayInputStream(pngBytes))) {
            int w = img.getWidth();
            int h = img.getHeight();
            for (int my = 0; my < MAP_SIZE; my++) {
                for (int mx = 0; mx < MAP_SIZE; mx++) {
                    int sx = Math.min(w - 1, mx * w / MAP_SIZE);
                    int sy = Math.min(h - 1, my * h / MAP_SIZE);
                    // NativeImage stores ABGR (R is the low byte).
                    int p = img.getPixelRGBA(sx, sy);
                    int r = p & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = (p >> 16) & 0xFF;
                    int a = (p >> 24) & 0xFF;
                    colors[my * MAP_SIZE + mx] = a < 128 ? 0 : nearestMapColor(r, g, b);
                }
            }
        }

        MapId mapId = level.getFreeMapId();
        MapItemSavedData data = MapItemSavedData.createFresh(0, 0, (byte) 0, false, false, level.dimension());
        System.arraycopy(colors, 0, data.colors, 0, colors.length);
        level.setMapData(mapId, data);

        ItemStack stack = new ItemStack(Items.FILLED_MAP);
        stack.set(DataComponents.MAP_ID, mapId);

        CompoundTag tag = new CompoundTag();
        tag.putString(ID_KEY, falcraftId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b[fal] Canvas"));

        LOGGER.info("Created tagged map (mapId {}) for canvas {}", mapId, falcraftId);
        return stack;
    }

    /** Returns the falcraft id tagged on a stack, or null if it isn't one of ours. */
    public static String getFalcraftId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        return tag.contains(ID_KEY) ? tag.getString(ID_KEY) : null;
    }

    /** Finds the nearest map-color packed byte (colorId*4 + brightnessId) for an RGB value. */
    private static byte nearestMapColor(int r, int g, int b) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;

        for (int id = 1; id < 64; id++) {
            MapColor mc = MapColor.byId(id);
            if (mc == null || mc == MapColor.NONE) continue;
            int base = mc.col;
            int br = (base >> 16) & 0xFF;
            int bg = (base >> 8) & 0xFF;
            int bb = base & 0xFF;

            for (MapColor.Brightness brightness : MapColor.Brightness.values()) {
                int m = brightness.modifier;
                int cr = br * m / 255;
                int cg = bg * m / 255;
                int cb = bb * m / 255;

                double dr = cr - r, dg = cg - g, db = cb - b;
                double dist = dr * dr + dg * dg + db * db;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = mc.id * 4 + brightness.id;
                }
            }
        }
        return (byte) best;
    }
}
