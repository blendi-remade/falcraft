package com.falcraft.render;

import com.falcraft.util.ImageCanvasManager;
import com.falcraft.util.ImageCanvasManager.CanvasTransform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Renders AI-generated image canvases in the world as full-fidelity textured quads,
 * plus a live placement preview that follows the player's crosshair.
 */
public class ImageCanvasRenderer {

    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final double BORDER_MARGIN = 0.12; // world units of frame around the image

    public static void render(PoseStack poseStack, Vec3 cameraPos) {
        var placed = ImageCanvasManager.getPlacedCanvases();
        boolean preview = ImageCanvasManager.isPreviewActive();
        if (placed.isEmpty() && !preview) {
            return;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();

        // Placed canvases: full opacity, dark frame.
        for (ImageCanvasManager.ImageCanvas c : placed) {
            drawCanvas(poseStack, cameraPos, c.toTransform(), c.texture, 1.0f,
                    0.08f, 0.08f, 0.08f, 1.0f);
        }

        // Live preview: slightly transparent, bright yellow frame.
        if (preview) {
            LocalPlayer player = Minecraft.getInstance().player;
            ResourceLocation tex = ImageCanvasManager.getPreviewTexture();
            if (player != null && tex != null) {
                CanvasTransform t = ImageCanvasManager.computeTargetTransform(player);
                drawCanvas(poseStack, cameraPos, t, tex, 0.85f,
                        1.0f, 0.85f, 0.2f, 0.9f);
            }
        }

        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
    }

    private static void drawCanvas(PoseStack poseStack, Vec3 cameraPos, CanvasTransform t,
            ResourceLocation texture, float alpha,
            float br, float bg, float bb, float ba) {
        Direction facing = t.facing();
        Vec3 normal = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 right = UP.cross(normal).normalize();
        double hw = t.width() / 2.0;
        double hh = t.height() / 2.0;

        // Center relative to camera.
        Vec3 c = t.center().subtract(cameraPos);

        Vec3 rW = right.scale(hw);
        Vec3 uH = UP.scale(hh);

        Matrix4f matrix = poseStack.last().pose();

        // ---- Frame (drawn first, slightly behind and larger) ----
        Vec3 cBack = c.subtract(normal.scale(0.005));
        Vec3 rWb = right.scale(hw + BORDER_MARGIN);
        Vec3 uHb = UP.scale(hh + BORDER_MARGIN);
        Vec3 fbl = cBack.subtract(rWb).subtract(uHb);
        Vec3 fbr = cBack.add(rWb).subtract(uHb);
        Vec3 ftr = cBack.add(rWb).add(uHb);
        Vec3 ftl = cBack.subtract(rWb).add(uHb);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder frame = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        frame.addVertex(matrix, (float) fbl.x, (float) fbl.y, (float) fbl.z).setColor(br, bg, bb, ba);
        frame.addVertex(matrix, (float) fbr.x, (float) fbr.y, (float) fbr.z).setColor(br, bg, bb, ba);
        frame.addVertex(matrix, (float) ftr.x, (float) ftr.y, (float) ftr.z).setColor(br, bg, bb, ba);
        frame.addVertex(matrix, (float) ftl.x, (float) ftl.y, (float) ftl.z).setColor(br, bg, bb, ba);
        BufferUploader.drawWithShader(frame.buildOrThrow());

        // ---- Image quad ----
        Vec3 bl = c.subtract(rW).subtract(uH);
        Vec3 brc = c.add(rW).subtract(uH);
        Vec3 tr = c.add(rW).add(uH);
        Vec3 tl = c.subtract(rW).add(uH);

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShaderColor(1, 1, 1, alpha);
        BufferBuilder img = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        img.addVertex(matrix, (float) bl.x, (float) bl.y, (float) bl.z).setUv(0f, 1f);
        img.addVertex(matrix, (float) brc.x, (float) brc.y, (float) brc.z).setUv(1f, 1f);
        img.addVertex(matrix, (float) tr.x, (float) tr.y, (float) tr.z).setUv(1f, 0f);
        img.addVertex(matrix, (float) tl.x, (float) tl.y, (float) tl.z).setUv(0f, 0f);
        BufferUploader.drawWithShader(img.buildOrThrow());
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }
}
