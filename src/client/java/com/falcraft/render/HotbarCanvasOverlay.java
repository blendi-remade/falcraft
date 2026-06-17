package com.falcraft.render;

import com.falcraft.util.ImageCanvasManager;
import com.falcraft.util.MapImageFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Draws the actual AI image as a thumbnail over each hotbar slot holding a canvas item,
 * so you can tell which is which at a glance (a filled_map otherwise shows a generic icon
 * in inventory slots). Reuses the textures cached by {@link ImageCanvasManager}.
 */
public class HotbarCanvasOverlay {

    private static final int ICON = 16;

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        int baseX = g.guiWidth() / 2 - 90;
        int y = g.guiHeight() - 16 - 3;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            String id = MapImageFactory.getFalcraftId(stack);
            if (id == null) continue;

            ResourceLocation tex = ImageCanvasManager.getOrLoadTexture(id);
            if (tex == null) continue;

            int x = baseX + i * 20 + 2;
            // Draw the whole texture scaled into the 16x16 slot icon (region == texture size
            // means full-texture sampling regardless of the source resolution).
            g.blit(tex, x, y, ICON, ICON, 0.0F, 0.0F, ICON, ICON, ICON, ICON);
        }
    }
}
