package com.falcraft.render;

import com.falcraft.util.BlockMapper;
import com.falcraft.util.PlacementPreview;
import com.falcraft.util.Voxelizer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Renders ghost blocks for the placement preview system.
 * Shows the actual structure shape using colored transparent blocks.
 */
public class GhostBlockRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("GhostBlockRenderer");
    
    // Ghost block rendering settings
    private static final float GHOST_ALPHA = 0.6f; // Transparency for ghost blocks
    
    /**
     * Renders ghost blocks showing the actual structure shape with colors.
     * Uses pre-computed surface voxels for performance.
     * 
     * Supports both:
     * - Normal mode: pendingGrid is set after generation
     * - Streaming mode: surfaceVoxels are updated live during diffusion
     * 
     * During streaming, renders ACTUAL Minecraft block textures for a more immersive effect!
     */
    public static void render(PoseStack poseStack, MultiBufferSource bufferSource, float partialTick) {
        if (!PlacementPreview.isPlacementActive()) {
            return;
        }
        
        // Determine grid size based on mode
        int size;
        Map<BlockPos, Integer> surfaceVoxels = PlacementPreview.getSurfaceVoxels();
        
        if (PlacementPreview.isStreaming()) {
            // Streaming mode: use streaming grid size
            size = PlacementPreview.getStreamingGridSize();
            if (surfaceVoxels == null || surfaceVoxels.isEmpty()) {
                return; // No voxels yet - wait for first update
            }
        } else {
            // Normal mode: use pending grid
            Voxelizer.VoxelGrid grid = PlacementPreview.getPendingGrid();
            if (grid == null) {
                return;
            }
            size = grid.size();
        }
        
        Minecraft minecraft = Minecraft.getInstance();
        
        // Calculate preview origin (locked during streaming, dynamic after)
        BlockPos origin = PlacementPreview.calculatePreviewOrigin().offset(0, PlacementPreview.getYOffset(), 0);
        
        // Get camera position for proper rendering offset
        Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().getPosition();
        
        // Create bounding box for the entire structure
        AABB box = new AABB(
            origin.getX() - cameraPos.x,
            origin.getY() - cameraPos.y,
            origin.getZ() - cameraPos.z,
            origin.getX() + size - cameraPos.x,
            origin.getY() + size - cameraPos.y,
            origin.getZ() + size - cameraPos.z
        );
        
        // Setup rendering
        poseStack.pushPose();
        
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        
        // Render voxels - ALWAYS use textured blocks for best visual experience
        // This shows actual Minecraft block textures in the preview!
        if (surfaceVoxels != null && !surfaceVoxels.isEmpty()) {
            renderTexturedBlocks(poseStack, bufferSource, surfaceVoxels, origin, cameraPos, size);
        }
        
        // Draw bounding box and footprint outlines
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f matrix = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        
        // Draw bounding box outline (subtle, since we have ghost blocks)
        BufferBuilder lineBuffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        drawBoxEdges(lineBuffer, matrix, box, 0.2f, 0.8f, 0.2f, 0.4f); // Dim green
        BufferUploader.drawWithShader(lineBuffer.buildOrThrow());
        
        // Draw ground footprint outline
        BufferBuilder groundBuffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        drawGroundFootprint(groundBuffer, matrix, box, 1.0f, 1.0f, 0.3f, 0.8f); // Yellow
        BufferUploader.drawWithShader(groundBuffer.buildOrThrow());
        
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        
        poseStack.popPose();
    }
    
    /**
     * Renders voxels as actual textured Minecraft blocks.
     * Used during streaming for an immersive "blocks materializing" effect.
     */
    private static void renderTexturedBlocks(PoseStack poseStack, MultiBufferSource bufferSource,
            Map<BlockPos, Integer> voxels, BlockPos origin, Vec3 cameraPos, int gridSize) {
        
        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = minecraft.getBlockRenderer();
        
        // Full brightness for preview blocks
        int light = LightTexture.FULL_BRIGHT;
        
        for (Map.Entry<BlockPos, Integer> entry : voxels.entrySet()) {
            BlockPos localPos = entry.getKey();
            int rgb = entry.getValue();
            
            // Apply rotation
            BlockPos rotatedPos = PlacementPreview.rotatePosition(localPos, gridSize);
            
            // Get actual block state from color
            BlockState blockState = BlockMapper.getClosestBlock(rgb);
            
            // Calculate world position relative to camera
            double x = origin.getX() + rotatedPos.getX() - cameraPos.x;
            double y = origin.getY() + rotatedPos.getY() - cameraPos.y;
            double z = origin.getZ() + rotatedPos.getZ() - cameraPos.z;
            
            poseStack.pushPose();
            poseStack.translate(x, y, z);
            
            // Render the actual block model with textures (full size, no scaling)
            blockRenderer.renderSingleBlock(blockState, poseStack, bufferSource, light, OverlayTexture.NO_OVERLAY);
            
            poseStack.popPose();
        }
    }
    
    /**
     * Renders surface voxels as transparent colored cubes
     */
    private static void renderGhostBlocks(BufferBuilder buffer, Matrix4f matrix,
                                           Map<BlockPos, Integer> surfaceVoxels,
                                           BlockPos origin, Vec3 cameraPos, int gridSize) {
        int rotation = PlacementPreview.getRotationIndex();
        
        for (Map.Entry<BlockPos, Integer> entry : surfaceVoxels.entrySet()) {
            BlockPos voxelPos = entry.getKey();
            int color = entry.getValue();
            
            // Apply rotation to the voxel position
            BlockPos rotatedPos = PlacementPreview.rotatePosition(voxelPos, gridSize);
            
            // Calculate world position relative to camera
            float x = (float) (origin.getX() + rotatedPos.getX() - cameraPos.x);
            float y = (float) (origin.getY() + rotatedPos.getY() - cameraPos.y);
            float z = (float) (origin.getZ() + rotatedPos.getZ() - cameraPos.z);
            
            // Extract color components
            float r = ((color >> 16) & 0xFF) / 255.0f;
            float g = ((color >> 8) & 0xFF) / 255.0f;
            float b = (color & 0xFF) / 255.0f;
            
            // Draw a 1x1x1 ghost cube at this position
            drawGhostCube(buffer, matrix, x, y, z, r, g, b, GHOST_ALPHA);
        }
    }
    
    /**
     * Draws a single transparent cube (6 faces)
     */
    private static void drawGhostCube(BufferBuilder buffer, Matrix4f matrix,
                                       float x, float y, float z,
                                       float r, float g, float b, float a) {
        float x1 = x, y1 = y, z1 = z;
        float x2 = x + 1, y2 = y + 1, z2 = z + 1;
        
        // Bottom face (Y-)
        buffer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buffer.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        
        // Top face (Y+)
        buffer.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buffer.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        
        // North face (Z-)
        buffer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        
        // South face (Z+)
        buffer.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        buffer.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        
        // West face (X-)
        buffer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buffer.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buffer.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        
        // East face (X+)
        buffer.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        buffer.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
    }
    
    /**
     * Draws a filled semi-transparent plane on the ground showing exact contact area
     * This "paints" the ground blocks where the structure will sit
     */
    private static void drawFilledGroundPlane(BufferBuilder buffer, Matrix4f matrix, AABB box) {
        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxZ = (float) box.maxZ;
        
        // Ground level - offset slightly upward to render on top of terrain
        float groundY = minY + 0.02f;
        
        // Draw a bright white/yellow semi-transparent plane
        // 50% opacity so you can still see terrain underneath
        float r = 1.0f; // White with slight yellow tint
        float g = 1.0f;
        float b = 0.8f;
        float a = 0.5f; // 50% opacity
        
        // Draw the filled rectangle
        buffer.addVertex(matrix, minX, groundY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, groundY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, groundY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, groundY, maxZ).setColor(r, g, b, a);
    }
    
    /**
     * Draws vertical pillars from the bottom 4 corners down to the ground
     * Uses bright white and draws multiple lines per corner for thickness
     */
    private static void drawVerticalPillars(BufferBuilder buffer, Matrix4f matrix, AABB box, BlockPos origin, int size) {
        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxZ = (float) box.maxZ;
        
        float groundY = (float) box.minY;
        float offset = 0.1f; // Offset for thicker lines
        
        // Draw BRIGHT WHITE vertical lines from bottom corners
        // Draw multiple lines per corner to make them thicker and more visible
        
        // Corner 1: minX, minZ (draw 3 lines for thickness)
        for (int i = 0; i < 3; i++) {
            float xOff = (i - 1) * offset;
            buffer.addVertex(matrix, minX + xOff, minY, minZ).setColor(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.addVertex(matrix, minX + xOff, groundY, minZ).setColor(1.0f, 1.0f, 1.0f, 0.7f);
        }
        
        // Corner 2: maxX, minZ
        for (int i = 0; i < 3; i++) {
            float xOff = (i - 1) * offset;
            buffer.addVertex(matrix, maxX + xOff, minY, minZ).setColor(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.addVertex(matrix, maxX + xOff, groundY, minZ).setColor(1.0f, 1.0f, 1.0f, 0.7f);
        }
        
        // Corner 3: maxX, maxZ
        for (int i = 0; i < 3; i++) {
            float xOff = (i - 1) * offset;
            buffer.addVertex(matrix, maxX + xOff, minY, maxZ).setColor(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.addVertex(matrix, maxX + xOff, groundY, maxZ).setColor(1.0f, 1.0f, 1.0f, 0.7f);
        }
        
        // Corner 4: minX, maxZ
        for (int i = 0; i < 3; i++) {
            float xOff = (i - 1) * offset;
            buffer.addVertex(matrix, minX + xOff, minY, maxZ).setColor(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.addVertex(matrix, minX + xOff, groundY, maxZ).setColor(1.0f, 1.0f, 1.0f, 0.7f);
        }
    }
    
    /**
     * Draws box faces with distinct bottom face (yellow) vs sides (green)
     */
    private static void drawBoxFacesWithDistinctBottom(BufferBuilder buffer, Matrix4f matrix, AABB box) {
        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;
        
        // Bottom face - BRIGHT YELLOW with 60% opacity (more opaque)
        buffer.addVertex(matrix, minX, minY, minZ).setColor(1.0f, 1.0f, 0.0f, 0.6f);
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(1.0f, 1.0f, 0.0f, 0.6f);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(1.0f, 1.0f, 0.0f, 0.6f);
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(1.0f, 1.0f, 0.0f, 0.6f);
        
        // Top face - Green with 20% opacity
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        
        // North face (Z min) - Green with 20% opacity
        buffer.addVertex(matrix, minX, minY, minZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        
        // South face (Z max) - Green with 20% opacity
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        
        // West face (X min) - Green with 20% opacity
        buffer.addVertex(matrix, minX, minY, minZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        
        // East face (X max) - Green with 20% opacity
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(0.0f, 1.0f, 0.3f, 0.2f);
    }
    
    /**
     * Draws a bright white outline on the ground showing the exact footprint perimeter
     * Combined with filled plane for maximum visibility
     */
    private static void drawGroundFootprint(BufferBuilder buffer, Matrix4f matrix, AABB box, float r, float g, float b, float a) {
        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxZ = (float) box.maxZ;
        
        // Draw a thick rectangle outline on the ground level (minY)
        // Slightly offset upward (+0.03) so it renders on top of filled plane
        float groundY = minY + 0.03f;
        
        // Draw the 4 edges of the footprint rectangle
        // Edge 1: minX, minZ -> maxX, minZ
        buffer.addVertex(matrix, minX, groundY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, groundY, minZ).setColor(r, g, b, a);
        
        // Edge 2: maxX, minZ -> maxX, maxZ
        buffer.addVertex(matrix, maxX, groundY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, groundY, maxZ).setColor(r, g, b, a);
        
        // Edge 3: maxX, maxZ -> minX, maxZ
        buffer.addVertex(matrix, maxX, groundY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, groundY, maxZ).setColor(r, g, b, a);
        
        // Edge 4: minX, maxZ -> minX, minZ
        buffer.addVertex(matrix, minX, groundY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, groundY, minZ).setColor(r, g, b, a);
        
        // Draw an X across the footprint for even better visual reference
        // Diagonal 1: minX, minZ -> maxX, maxZ
        buffer.addVertex(matrix, minX, groundY, minZ).setColor(r, g, b, a * 0.5f);
        buffer.addVertex(matrix, maxX, groundY, maxZ).setColor(r, g, b, a * 0.5f);
        
        // Diagonal 2: maxX, minZ -> minX, maxZ
        buffer.addVertex(matrix, maxX, groundY, minZ).setColor(r, g, b, a * 0.5f);
        buffer.addVertex(matrix, minX, groundY, maxZ).setColor(r, g, b, a * 0.5f);
    }
    
    /**
     * Draws the 6 faces of a bounding box with transparency
     */
    private static void drawBoxFaces(BufferBuilder buffer, Matrix4f matrix, AABB box, float r, float g, float b, float a) {
        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;
        
        // Bottom face (Y min)
        buffer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(r, g, b, a);
        
        // Top face (Y max)
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(r, g, b, a);
        
        // North face (Z min)
        buffer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(r, g, b, a);
        
        // South face (Z max)
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(r, g, b, a);
        
        // West face (X min)
        buffer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(r, g, b, a);
        
        // East face (X max)
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(r, g, b, a);
    }
    
    /**
     * Draws the 12 edges of a bounding box
     */
    private static void drawBoxEdges(BufferBuilder buffer, Matrix4f matrix, AABB box, float r, float g, float b, float a) {
        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;
        
        // Bottom face edges
        buffer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(r, g, b, a);
        
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(r, g, b, a);
        
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(r, g, b, a);
        
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a);
        
        // Top face edges
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(r, g, b, a);
        
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(r, g, b, a);
        
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(r, g, b, a);
        
        // Vertical edges
        buffer.addVertex(matrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(r, g, b, a);
        
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(r, g, b, a);
        
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(r, g, b, a);
    }
}

