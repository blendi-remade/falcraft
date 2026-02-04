package com.falcraft.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages the ghost block placement preview system
 * Allows players to see where a 3D structure will be placed before confirming
 * 
 * Supports both:
 * - Static mode: Show complete voxel grid after generation
 * - Streaming mode: Show live voxel updates during diffusion
 */
public class PlacementPreview {
    private static final Logger LOGGER = LoggerFactory.getLogger("PlacementPreview");
    
    private static Voxelizer.VoxelGrid pendingGrid = null;
    private static Map<BlockPos, Integer> surfaceVoxels = null; // Pre-computed surface for preview
    private static boolean isActive = false;
    private static int rotationIndex = 0; // 0=0°, 1=90°, 2=180°, 3=270° (clockwise around Y axis)
    
    // Streaming mode state
    private static boolean isStreaming = false;
    private static Map<BlockPos, Integer> streamingVoxels = null;
    private static int streamingGridSize = 0;
    private static String streamingStage = "";
    private static int streamingStep = 0;
    private static int streamingTotalSteps = 0;
    private static float streamingProgress = 0f;
    private static BlockPos streamingLockedOrigin = null; // Fixed position during streaming
    private static Map<BlockPos, Integer> noiseVoxels = null; // Random noise for "emerging from chaos" effect
    private static final Random NOISE_RANDOM = new Random();
    
    // Noise animation state - makes the noise "alive" and swirling from the start
    private static int noiseAnimationTick = 0;
    private static Map<BlockPos, float[]> noisePositions = null; // Actual float positions for smooth movement
    private static Map<BlockPos, float[]> noiseVelocities = null; // [vx, vy, vz] per noise voxel
    
    // Animated placement state
    private static boolean isAnimatingPlacement = false;
    private static List<List<Map.Entry<BlockPos, Integer>>> layersByY = null;
    private static int currentLayerIndex = 0;
    private static BlockPos placementOrigin = null;
    private static int placementRotation = 0; // Rotation to apply during placement
    private static int placementGridSize = 0; // Grid size for rotation calculation
    private static final int LAYERS_PER_TICK = 3; // Place 3 Y-levels per tick for smooth animation
    
    /**
     * Starts placement preview mode with the given voxel grid
     * @param grid The voxel grid to preview
     */
    public static void startPlacement(Voxelizer.VoxelGrid grid) {
        pendingGrid = grid;
        isActive = true;
        isStreaming = false;
        rotationIndex = 0; // Reset rotation
        
        // Pre-compute surface voxels for preview rendering
        surfaceVoxels = extractSurfaceVoxels(grid);
        LOGGER.info("Started placement preview mode with {} voxels, {} surface voxels", 
            grid.voxels().size(), surfaceVoxels.size());
    }
    
    // ==================== STREAMING MODE ====================
    
    /**
     * Starts streaming preview mode.
     * In this mode, voxels are updated incrementally as they stream in.
     * The position is LOCKED at start so you can watch the structure form in place.
     * 
     * Also generates random "noise" voxels that create the "emerging from chaos" effect.
     * @param gridSize The target grid size for coordinate scaling
     */
    public static void startStreaming(int gridSize) {
        isStreaming = true;
        isActive = true;
        streamingGridSize = gridSize;
        streamingVoxels = new HashMap<>();
        surfaceVoxels = new HashMap<>();
        pendingGrid = null;
        rotationIndex = 0;
        streamingStage = "starting";
        streamingStep = 0;
        streamingTotalSteps = 0;
        streamingProgress = 0f;
        
        // Lock the position at where player is looking RIGHT NOW
        // This lets them watch the structure form in a fixed spot
        streamingLockedOrigin = calculateStreamingOrigin(gridSize);
        
        // Generate initial noise voxels - random scattered voxels throughout the bounding box
        // These create the "chaos" that resolves into order as diffusion progresses
        noiseVoxels = generateNoiseVoxels(gridSize);
        surfaceVoxels = new HashMap<>(noiseVoxels); // Start with noise visible
        
        // Initialize noise animation - make it ALIVE from the start!
        noiseAnimationTick = 0;
        initializeNoiseAnimation();
        
        LOGGER.info("Started streaming preview mode with grid size {}, {} noise voxels, locked origin at {}", 
            gridSize, noiseVoxels.size(), streamingLockedOrigin);
    }
    
    /**
     * Initializes noise animation state.
     * With the new "stable emergence" approach, we just need to track the noise - no movement.
     */
    private static void initializeNoiseAnimation() {
        // No complex initialization needed anymore
        // The noise is static, creating a stable cloud for the structure to emerge through
        noisePositions = null;
        noiseVelocities = null;
    }
    
    /**
     * Updates noise display each tick.
     * 
     * NEW APPROACH - "Stable Emergence":
     * - Noise is STATIC (no chaotic movement)
     * - Gentle floating/pulsing effect only
     * - Structure emerges THROUGH the noise cloud
     * - Clean, deterministic fade-out
     */
    public static void tickNoiseAnimation() {
        if (!isStreaming || noiseVoxels == null || noiseVoxels.isEmpty()) return;
        
        noiseAnimationTick++;
        
        // Update surface voxels - this is where the magic happens
        if (streamingVoxels == null || streamingVoxels.isEmpty()) {
            // No real data yet - show static noise cloud
            // Add subtle "breathing" effect by occasionally toggling a few voxels
            if (noiseAnimationTick % 10 == 0) {
                // Very subtle shimmer - just change colors slightly
                for (Map.Entry<BlockPos, Integer> entry : noiseVoxels.entrySet()) {
                    // 5% chance to slightly shift color for shimmer effect
                    if (NOISE_RANDOM.nextFloat() < 0.05f) {
                        int newColor = MIXED_BLOCK_PALETTE[NOISE_RANDOM.nextInt(MIXED_BLOCK_PALETTE.length)];
                        noiseVoxels.put(entry.getKey(), newColor);
                    }
                }
            }
            surfaceVoxels = new HashMap<>(noiseVoxels);
        } else {
            // Real data arrived - blend noise with structure (handled by blendNoiseWithVoxels)
            surfaceVoxels = blendNoiseWithVoxels(streamingVoxels, streamingStage, streamingProgress);
        }
    }
    
    // Comprehensive block palette - ~65% natural/neutral, ~35% colorful accents
    // Creates a realistic "pile of Minecraft blocks" effect
    private static final int[] MIXED_BLOCK_PALETTE = {
        // ===== STONE VARIANTS (most common - the backbone) =====
        0x808080, // Stone
        0x808080, // Stone (duplicate for higher frequency)
        0x7F7F7F, // Cobblestone
        0x7F7F7F, // Cobblestone (duplicate)
        0x7A7A7A, // Stone bricks
        0x4F4F51, // Deepslate
        0x555555, // Cobbled deepslate
        0x434343, // Deepslate bricks
        0x353538, // Deepslate tiles
        0x6C6C66, // Tuff
        0x878787, // Andesite
        0x848484, // Polished andesite
        0xE4E4E4, // Diorite
        0x9A6E53, // Granite
        0xA0A0A0, // Smooth stone
        0x2A2333, // Blackstone
        0x36313D, // Polished blackstone
        
        // ===== WOOD PLANKS (lots of brown tones) =====
        0xB18962, // Oak
        0xB18962, // Oak (duplicate)
        0x73563A, // Spruce
        0x73563A, // Spruce (duplicate)
        0xD2BC7C, // Birch
        0x44331C, // Dark oak
        0xB88856, // Jungle
        0xB05E3C, // Acacia
        0x773636, // Mangrove
        0xE4B4A8, // Cherry
        0x6C3A4A, // Crimson
        0x2B6D64, // Warped
        
        // ===== NATURAL/ORGANIC =====
        0x8B5D3B, // Dirt-ish
        0xE3DBB0, // Sandstone
        0xBF6330, // Red sandstone
        0x4F6633, // Moss
        0x8B6B4D, // Mud bricks
        0x8E7259, // Packed mud
        0x8F5D4B, // Bricks
        0xDBDCA6, // End stone
        0x6D3636, // Netherrack
        0x2C151A, // Nether bricks
        
        // ===== TERRACOTTA (muted, earthy colors) =====
        0xD1B1A1, // White terracotta
        0x876B62, // Light gray terracotta
        0x392A23, // Gray terracotta
        0xA05325, // Orange terracotta
        0x4D3224, // Brown terracotta
        0x8F3D2E, // Red terracotta
        0xBA8523, // Yellow terracotta
        0x575B5B, // Cyan terracotta
        0x4A3B5B, // Blue terracotta
        0x4C532A, // Green terracotta
        0x764556, // Purple terracotta
        
        // ===== CONCRETE (some vibrant accents) =====
        0xF9FFFE, // White concrete
        0x7D7D73, // Light gray concrete
        0x36393D, // Gray concrete
        0x080A0F, // Black concrete
        0x5C3A24, // Brown concrete
        0x8E2121, // Red concrete
        0xE06101, // Orange concrete
        0xF1AF15, // Yellow concrete
        0x5EA818, // Lime concrete
        0x157788, // Cyan concrete
        0x2389C6, // Light blue concrete
        0x2C2E8F, // Blue concrete
        0x64209C, // Purple concrete
        
        // ===== MINERAL BLOCKS (iconic, sparkly) =====
        0xAA0F01, // Redstone block!
        0x1E4B8C, // Lapis block
        0xF9D71C, // Gold block
        0xD8D8D8, // Iron block
        0x62E2DD, // Diamond block
        0x52D869, // Emerald block
        0xFFBC5E, // Glowstone
        0x1A1919, // Coal block
        
        // ===== SPECIAL BLOCKS (unique colors) =====
        0x63A293, // Prismarine
        0x395A4E, // Dark prismarine
        0x8B6AA6, // Amethyst
        0xA87AA4, // Purpur
        0xE8E5DD, // Quartz
        0xE3DCC6, // Bone block
        0xC06A4D, // Copper
        0x53A384, // Oxidized copper
        
        // ===== WOOL (soft accents - fewer than before) =====
        0xE9ECEC, // White wool
        0x3E4447, // Gray wool
        0x8E8E86, // Light gray wool
        0x724728, // Brown wool
        0xA12722, // Red wool
        0x546D1B, // Green wool
    };
    
    // "Magical" block palette for geometry phase - prismarine, ice, crystal-like
    private static final int[] MAGICAL_BLOCK_PALETTE = {
        0x63A293, // Prismarine
        0x5BA496, // Prismarine bricks
        0x395A4E, // Dark prismarine
        0xACDBC5, // Sea lantern
        0x167879, // Warped wart block
        0x2B6D64, // Warped planks
        0x8B6AA6, // Amethyst
        0xA87AA4, // Purpur
        0x7FCCF5, // Light blue ice-like
        0x9CDDF5, // Sky blue crystalline
        0x4ACFCF, // Bright cyan
        0x2DD4BF, // Teal
    };
    
    /**
     * Generates dense noise voxels to create a "cloud" effect.
     * Uses gaussian distribution so noise is denser in the center.
     * Uses ACTUAL Minecraft block colors for a more "real" initial chaos.
     */
    private static Map<BlockPos, Integer> generateNoiseVoxels(int gridSize) {
        Map<BlockPos, Integer> noise = new HashMap<>();
        
        // Target ~35% fill with subtle organic variation (not too cubic, not too circular)
        int totalVoxels = gridSize * gridSize * gridSize;
        int targetCount = (int) (totalVoxels * 0.35);
        targetCount = Math.min(targetCount, 18000); // Cap for performance
        targetCount = Math.max(targetCount, 2500);  // Minimum for visual effect
        
        float center = gridSize / 2.0f;
        float stdDev = gridSize / 2.8f; // Moderate spread
        
        // Small margin to soften hard cube edges
        int margin = Math.max(1, gridSize / 16);
        int minBound = margin;
        int maxBound = gridSize - margin - 1;
        
        int attempts = 0;
        int maxAttempts = targetCount * 3;
        
        while (noise.size() < targetCount && attempts < maxAttempts) {
            attempts++;
            
            // Gaussian distribution - denser in center
            float gx = (float) (center + NOISE_RANDOM.nextGaussian() * stdDev);
            float gy = (float) (center + NOISE_RANDOM.nextGaussian() * stdDev);
            float gz = (float) (center + NOISE_RANDOM.nextGaussian() * stdDev);
            
            int x = Math.round(gx);
            int y = Math.round(gy);
            int z = Math.round(gz);
            
            // Reject positions outside bounds (softer than clamping)
            if (x < minBound || x > maxBound || 
                y < minBound || y > maxBound || 
                z < minBound || z > maxBound) {
                continue;
            }
            
            // Add irregular "holes" using position-based hash - breaks uniformity
            int hash = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
            float holeProbability = 0.12f; // 12% holes for organic feel
            if ((hash & 0xFF) < (int)(holeProbability * 256)) {
                continue;
            }
            
            // Use actual Minecraft block colors
            int color = MIXED_BLOCK_PALETTE[NOISE_RANDOM.nextInt(MIXED_BLOCK_PALETTE.length)];
            noise.put(new BlockPos(x, y, z), color);
        }
        
        return noise;
    }
    
    /**
     * Calculates the origin position for streaming mode (called once at start).
     * Similar to calculatePreviewOrigin but doesn't require pendingGrid.
     */
    private static BlockPos calculateStreamingOrigin(int gridSize) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        
        if (player == null) {
            return BlockPos.ZERO;
        }
        
        // Raycast to find what block the player is looking at
        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 lookVec = player.getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.scale(200.0));
        
        net.minecraft.world.level.ClipContext context = new net.minecraft.world.level.ClipContext(
            eyePos,
            endPos,
            net.minecraft.world.level.ClipContext.Block.OUTLINE,
            net.minecraft.world.level.ClipContext.Fluid.NONE,
            player
        );
        
        net.minecraft.world.phys.BlockHitResult hitResult = player.level().clip(context);
        
        BlockPos targetBlock;
        double minDistance = Math.max(gridSize * 1.2, 15.0);
        
        if (hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            Vec3 hitPos = hitResult.getLocation();
            double distanceToHit = eyePos.distanceTo(hitPos);
            
            if (distanceToHit < minDistance) {
                Vec3 targetPos = eyePos.add(lookVec.scale(minDistance));
                targetBlock = BlockPos.containing(targetPos);
                int groundY = findGroundFromAbove(player.level(), targetBlock, (int)eyePos.y);
                targetBlock = new BlockPos(targetBlock.getX(), groundY, targetBlock.getZ());
            } else {
                targetBlock = hitResult.getBlockPos().above();
            }
        } else {
            Vec3 targetPos = eyePos.add(lookVec.scale(minDistance));
            targetBlock = BlockPos.containing(targetPos);
            int groundY = findGroundFromAbove(player.level(), targetBlock, (int)eyePos.y);
            targetBlock = new BlockPos(targetBlock.getX(), groundY, targetBlock.getZ());
        }
        
        // Center the structure horizontally around the target block
        return new BlockPos(
            targetBlock.getX() - gridSize / 2,
            targetBlock.getY(),
            targetBlock.getZ() - gridSize / 2
        );
    }
    
    /**
     * Updates the streaming voxels with new data.
     * Called when new voxel data arrives from the SSE stream.
     * @param voxels The new voxel data
     * @param stage "geometry" or "appearance"
     * @param step Current diffusion step
     * @param totalSteps Total diffusion steps
     * @param progress Overall progress (0.0 - 1.0)
     */
    public static void updateStreamingVoxels(Map<BlockPos, Integer> voxels, String stage, 
            int step, int totalSteps, float progress) {
        if (!isStreaming) return;
        
        streamingVoxels = voxels;
        streamingStage = stage;
        streamingStep = step;
        streamingTotalSteps = totalSteps;
        streamingProgress = progress;
        
        // Blend noise voxels with real voxels for the "chaos → order" effect
        // As progress increases, more noise fades out and real structure emerges
        surfaceVoxels = blendNoiseWithVoxels(voxels, stage, progress);
        
        LOGGER.debug("Updated streaming voxels: {} voxels, stage={}, step={}/{}, blended={}", 
            voxels.size(), stage, step, totalSteps, surfaceVoxels.size());
    }
    
    /**
     * Blends noise voxels with real voxels for clean "emergence from cloud" effect.
     * 
     * NEW APPROACH - Smooth, deterministic blending:
     * 1. Real voxels always show (they're the structure!)
     * 2. Noise voxels fade based on DISTANCE to structure + PROGRESS
     * 3. No random flickering - deterministic based on position
     * 4. Noise stays visible longer for cleaner emergence
     */
    private static Map<BlockPos, Integer> blendNoiseWithVoxels(Map<BlockPos, Integer> realVoxels, 
            String stage, float progress) {
        Map<BlockPos, Integer> blended = new HashMap<>();
        
        boolean isGeometry = "geometry".equals(stage);
        
        // Progress within each phase
        float geometryProgress = isGeometry ? Math.min(1.0f, progress * 2.0f) : 1.0f;
        float appearanceProgress = isGeometry ? 0.0f : Math.min(1.0f, (progress - 0.5f) * 2.0f);
        
        // Calculate structure bounds for distance calculations
        BlockPos centroid = realVoxels.isEmpty() ? 
            new BlockPos(streamingGridSize/2, streamingGridSize/2, streamingGridSize/2) : 
            calculateCentroid(realVoxels.keySet());
        
        // FIRST: Add noise voxels - they CONVERGE towards the structure
        // FAR noise disappears first, CLOSE noise stays longer (shrinking cloud effect)
        // All noise gone by appearance phase (progress ~0.5)
        if (noiseVoxels != null && !noiseVoxels.isEmpty()) {
            
            // Noise fully gone by start of appearance phase (50%)
            float fadeEnd = 0.50f;
            float fadeFactor = Math.min(1f, progress / fadeEnd);
            
            for (Map.Entry<BlockPos, Integer> entry : noiseVoxels.entrySet()) {
                BlockPos noisePos = entry.getKey();
                
                // Calculate distance to nearest real voxel
                float distToStructure;
                if (realVoxels.size() < 50) {
                    distToStructure = (float) Math.sqrt(noisePos.distSqr(centroid));
                } else {
                    // Sample nearby real voxels for speed
                    distToStructure = Float.MAX_VALUE;
                    int checked = 0;
                    for (BlockPos realPos : realVoxels.keySet()) {
                        float d = (float) Math.sqrt(noisePos.distSqr(realPos));
                        if (d < distToStructure) distToStructure = d;
                        if (d < 2 || ++checked > 20) break;
                    }
                }
                
                // CONVERGING logic: FAR noise fades FIRST, CLOSE noise stays LONGER
                // This creates the "shrinking cloud" effect towards the structure
                float maxDist = streamingGridSize * 0.5f;
                float normalizedDist = Math.min(1f, distToStructure / maxDist);
                
                // Far (dist ~1) = LOW threshold = fades early
                // Close (dist ~0) = HIGH threshold = survives longer
                float survivalThreshold = (1f - normalizedDist) * 0.7f + 0.2f; // Range: 0.2 (far) to 0.9 (close)
                
                if (fadeFactor < survivalThreshold) {
                    int noiseColor = entry.getValue();
                    
                    // Noise near structure transforms to magical colors (being absorbed)
                    if (distToStructure < 6 && progress > 0.05f) {
                        float transformAmount = Math.min(1f, progress * 2f);
                        transformAmount *= (1f - distToStructure / 6f); // Closer = more transformed
                        int magicalColor = MAGICAL_BLOCK_PALETTE[Math.abs(noiseColor) % MAGICAL_BLOCK_PALETTE.length];
                        noiseColor = blendColors(noiseColor, magicalColor, transformAmount);
                    }
                    
                    blended.put(noisePos, noiseColor);
                }
            }
        }
        
        // SECOND: Add real voxels ON TOP (they override noise at same position)
        // Real voxels are the actual structure emerging through the noise
        for (Map.Entry<BlockPos, Integer> entry : realVoxels.entrySet()) {
            int originalColor = entry.getValue();
            int color;
            
            if (isGeometry) {
                // Geometry phase: Show as magical/crystalline blocks
                color = MAGICAL_BLOCK_PALETTE[Math.abs(originalColor) % MAGICAL_BLOCK_PALETTE.length];
            } else {
                // Appearance phase: Blend from magical to final block color
                int magicalColor = MAGICAL_BLOCK_PALETTE[Math.abs(originalColor) % MAGICAL_BLOCK_PALETTE.length];
                int mappedColor = getBlockMappedColor(originalColor);
                color = blendColors(magicalColor, mappedColor, appearanceProgress);
            }
            
            blended.put(entry.getKey(), color);
        }
        
        return blended;
    }
    
    /**
     * Gets the actual Minecraft block color that this RGB would map to.
     * Uses BlockMapper's palette to find the closest matching block color.
     */
    private static int getBlockMappedColor(int rgb) {
        // Get the BlockState that this color maps to
        BlockState blockState = BlockMapper.getClosestBlock(rgb);
        
        // Get the color of that block from the mapper's palette
        // We need to look up what color that block actually is
        return BlockMapper.getBlockColor(blockState.getBlock());
    }
    
    /**
     * Calculates the centroid (center point) of a set of positions.
     */
    private static BlockPos calculateCentroid(Set<BlockPos> positions) {
        if (positions.isEmpty()) {
            return new BlockPos(streamingGridSize / 2, streamingGridSize / 2, streamingGridSize / 2);
        }
        
        long sumX = 0, sumY = 0, sumZ = 0;
        for (BlockPos pos : positions) {
            sumX += pos.getX();
            sumY += pos.getY();
            sumZ += pos.getZ();
        }
        
        int count = positions.size();
        return new BlockPos(
            (int) (sumX / count),
            (int) (sumY / count),
            (int) (sumZ / count)
        );
    }
    
    /**
     * Blends two colors together.
     * @param c1 First color
     * @param c2 Second color
     * @param t Blend factor (0.0 = c1, 1.0 = c2)
     * @return Blended color
     */
    private static int blendColors(int c1, int c2, float t) {
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        
        int r = (int) (r1 * (1 - t) + r2 * t);
        int g = (int) (g1 * (1 - t) + g2 * t);
        int b = (int) (b1 * (1 - t) + b2 * t);
        
        return (r << 16) | (g << 8) | b;
    }
    
    /**
     * Completes streaming mode and converts to normal placement mode.
     * @param finalVoxels The final voxel data
     */
    public static void completeStreaming(Map<BlockPos, Integer> finalVoxels) {
        if (!isStreaming) return;
        
        // Convert to VoxelGrid for placement
        pendingGrid = new Voxelizer.VoxelGrid(finalVoxels, streamingGridSize);
        
        // Re-extract surface voxels for final preview (with real colors, no noise)
        surfaceVoxels = extractSurfaceVoxels(pendingGrid);
        
        isStreaming = false;
        streamingStage = "complete";
        
        // Clear noise, animation state, and unlock position
        noiseVoxels = null;
        streamingLockedOrigin = null;
        noiseAnimationTick = 0;
        noisePositions = null;
        noiseVelocities = null;
        
        LOGGER.info("Streaming complete! {} voxels ready for placement", finalVoxels.size());
    }
    
    /**
     * Cancels streaming mode
     */
    public static void cancelStreaming() {
        if (isStreaming) {
            LOGGER.info("Cancelled streaming preview");
            isStreaming = false;
            isActive = false;
            streamingVoxels = null;
            surfaceVoxels = null;
            pendingGrid = null;
            streamingLockedOrigin = null;
            noiseVoxels = null;
            // Clear noise animation state
            noiseAnimationTick = 0;
            noisePositions = null;
            noiseVelocities = null;
        }
    }
    
    /**
     * @return true if currently in streaming mode
     */
    public static boolean isStreaming() {
        return isStreaming;
    }
    
    /**
     * @return The current streaming stage ("geometry", "appearance", etc.)
     */
    public static String getStreamingStage() {
        return streamingStage;
    }
    
    /**
     * @return The current streaming step
     */
    public static int getStreamingStep() {
        return streamingStep;
    }
    
    /**
     * @return The total streaming steps
     */
    public static int getStreamingTotalSteps() {
        return streamingTotalSteps;
    }
    
    /**
     * @return The streaming progress (0.0 - 1.0)
     */
    public static float getStreamingProgress() {
        return streamingProgress;
    }
    
    /**
     * @return The streaming grid size
     */
    public static int getStreamingGridSize() {
        return streamingGridSize;
    }
    
    /**
     * Rotates the structure 90 degrees clockwise (when viewed from above)
     */
    public static void rotate() {
        if (!isActive) return;
        rotationIndex = (rotationIndex + 1) % 4;
        LOGGER.info("Rotated structure to {} degrees", rotationIndex * 90);
    }
    
    /**
     * Gets the current rotation index (0=0°, 1=90°, 2=180°, 3=270°)
     */
    public static int getRotationIndex() {
        return rotationIndex;
    }
    
    /**
     * Transforms a voxel position based on current rotation.
     * Rotates around the Y axis (up), keeping the structure centered.
     * @param pos Original voxel position (in grid coordinates)
     * @param gridSize The size of the grid
     * @return Rotated position
     */
    public static BlockPos rotatePosition(BlockPos pos, int gridSize) {
        if (rotationIndex == 0) {
            return pos; // No rotation
        }
        
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int s = gridSize - 1; // Max coordinate
        
        return switch (rotationIndex) {
            case 1 -> new BlockPos(s - z, y, x);      // 90° CW
            case 2 -> new BlockPos(s - x, y, s - z);  // 180°
            case 3 -> new BlockPos(z, y, s - x);      // 270° CW (90° CCW)
            default -> pos;
        };
    }
    
    /**
     * Extracts only the surface voxels (voxels with at least one exposed face)
     * This is much smaller than the full voxel set for preview rendering
     */
    private static Map<BlockPos, Integer> extractSurfaceVoxels(Voxelizer.VoxelGrid grid) {
        Map<BlockPos, Integer> surface = new HashMap<>();
        Set<BlockPos> allVoxels = grid.voxels().keySet();
        
        for (Map.Entry<BlockPos, Integer> entry : grid.voxels().entrySet()) {
            BlockPos pos = entry.getKey();
            
            // Check if any face is exposed (neighbor is not a voxel)
            boolean isExposed = 
                !allVoxels.contains(pos.above()) ||
                !allVoxels.contains(pos.below()) ||
                !allVoxels.contains(pos.north()) ||
                !allVoxels.contains(pos.south()) ||
                !allVoxels.contains(pos.east()) ||
                !allVoxels.contains(pos.west());
            
            if (isExposed) {
                surface.put(pos, entry.getValue());
            }
        }
        
        return surface;
    }
    
    /**
     * @return The surface voxels for preview rendering, or null if not available
     */
    public static Map<BlockPos, Integer> getSurfaceVoxels() {
        return surfaceVoxels;
    }
    
    /**
     * Confirms placement and starts animated bottom-up block placement
     */
    public static void confirmPlacement() {
        if (isActive && pendingGrid != null) {
            LOGGER.info("Starting animated placement of {} blocks (rotation: {}°)", 
                pendingGrid.voxels().size(), rotationIndex * 90);
            
            // Calculate placement origin and save rotation state
            placementOrigin = calculatePreviewOrigin();
            placementRotation = rotationIndex;
            placementGridSize = pendingGrid.size();
            
            // Sort voxels by Y coordinate (bottom to top)
            // Note: Y doesn't change with Y-axis rotation, so original Y is fine
            Map<Integer, List<Map.Entry<BlockPos, Integer>>> voxelsByY = new TreeMap<>();
            
            for (Map.Entry<BlockPos, Integer> entry : pendingGrid.voxels().entrySet()) {
                BlockPos voxelPos = entry.getKey();
                int y = voxelPos.getY();
                voxelsByY.computeIfAbsent(y, k -> new ArrayList<>()).add(entry);
            }
            
            // Convert to list of layers (sorted by Y)
            layersByY = new ArrayList<>(voxelsByY.values());
            currentLayerIndex = 0;
            isAnimatingPlacement = true;
            
            // Exit preview mode (but keep animating)
            isActive = false;
            pendingGrid = null;
            surfaceVoxels = null;
            
            LOGGER.info("Prepared {} layers for animated placement", layersByY.size());
        }
    }
    
    /**
     * Cancels placement preview without placing blocks
     */
    public static void cancelPlacement() {
        if (isActive) {
            LOGGER.info("Cancelled placement preview");
            isActive = false;
            pendingGrid = null;
            surfaceVoxels = null;
            
            // Also cancel streaming if active
            if (isStreaming) {
                isStreaming = false;
                streamingVoxels = null;
            }
        }
    }
    
    /**
     * @return true if placement preview is currently active
     */
    public static boolean isPlacementActive() {
        return isActive;
    }
    
    /**
     * @return The pending voxel grid, or null if not in placement mode
     */
    public static Voxelizer.VoxelGrid getPendingGrid() {
        return pendingGrid;
    }
    
    /**
     * Calculates the current preview origin based on what block the player is looking at
     * Uses raycasting to find the target block, then centers the structure on top of it
     * 
     * During streaming mode, returns the LOCKED origin (fixed at stream start).
     * @return The origin position for the preview
     */
    public static BlockPos calculatePreviewOrigin() {
        if (!isActive) {
            return BlockPos.ZERO;
        }
        
        // During streaming, return the locked origin (fixed position)
        if (isStreaming && streamingLockedOrigin != null) {
            return streamingLockedOrigin;
        }
        
        // Support both streaming mode and normal mode
        if (pendingGrid == null && !isStreaming) {
            return BlockPos.ZERO;
        }
        
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        
        if (player == null) {
            return BlockPos.ZERO;
        }
        
        int gridSize = isStreaming ? streamingGridSize : pendingGrid.size();
        
        // Raycast to find what block the player is looking at
        // Use a longer distance so it works from far away (up to 200 blocks)
        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 lookVec = player.getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.scale(200.0)); // 200 block reach
        
        net.minecraft.world.level.ClipContext context = new net.minecraft.world.level.ClipContext(
            eyePos,
            endPos,
            net.minecraft.world.level.ClipContext.Block.OUTLINE,
            net.minecraft.world.level.ClipContext.Fluid.NONE,
            player
        );
        
        net.minecraft.world.phys.BlockHitResult hitResult = player.level().clip(context);
        
        BlockPos targetBlock;
        double minDistance = Math.max(gridSize * 1.2, 15.0); // Minimum comfortable distance
        
        if (hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            Vec3 hitPos = hitResult.getLocation();
            double distanceToHit = eyePos.distanceTo(hitPos);
            
            // If the hit block is too close, place further away instead
            if (distanceToHit < minDistance) {
                Vec3 targetPos = eyePos.add(lookVec.scale(minDistance));
                targetBlock = BlockPos.containing(targetPos);
                
                // Search down from eye level to find ground (up to 100 blocks down)
                int groundY = findGroundFromAbove(player.level(), targetBlock, (int)eyePos.y);
                targetBlock = new BlockPos(targetBlock.getX(), groundY, targetBlock.getZ());
            } else {
                // Hit block is at good distance - place on top of it
                targetBlock = hitResult.getBlockPos().above();
            }
        } else {
            // Not looking at any block - place in front of player
            Vec3 targetPos = eyePos.add(lookVec.scale(minDistance));
            targetBlock = BlockPos.containing(targetPos);
            
            // Search down from eye level to find ground (up to 100 blocks down)
            int groundY = findGroundFromAbove(player.level(), targetBlock, (int)eyePos.y);
            targetBlock = new BlockPos(targetBlock.getX(), groundY, targetBlock.getZ());
        }
        
        // Center the structure horizontally around the target block
        return new BlockPos(
            targetBlock.getX() - gridSize / 2,
            targetBlock.getY(),
            targetBlock.getZ() - gridSize / 2
        );
    }
    
    /**
     * Finds ground by searching downward from a high position
     * Used when placing structures in the air or looking at sky
     */
    private static int findGroundFromAbove(net.minecraft.world.level.Level level, BlockPos startPos, int startY) {
        // Search down from start position (up to 100 blocks)
        for (int y = startY; y >= startY - 100; y--) {
            BlockPos checkPos = new BlockPos(startPos.getX(), y, startPos.getZ());
            net.minecraft.world.level.block.state.BlockState blockState = level.getBlockState(checkPos);
            net.minecraft.world.level.block.state.BlockState aboveState = level.getBlockState(checkPos.above());
            
            // Found solid ground with air above
            if (!blockState.isAir() && aboveState.isAir()) {
                return y + 1; // Place on top
            }
        }
        
        // If no ground found within 100 blocks, place at world bottom + some height
        return Math.max(level.getMinY() + 5, startY - 100);
    }
    
    /**
     * @return true if animated placement is currently in progress
     */
    public static boolean isAnimatingPlacement() {
        return isAnimatingPlacement;
    }
    
    /**
     * Advances the animated placement by placing the next batch of layers
     * Call this from a client tick event
     */
    public static void tickAnimatedPlacement() {
        if (!isAnimatingPlacement || layersByY == null) {
            return;
        }
        
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        
        if (player == null) {
            LOGGER.error("Player is null during animated placement!");
            cancelAnimatedPlacement();
            return;
        }
        
        // Get the appropriate level for block placement
        Level level = getPlacementLevel(minecraft);
        if (level == null) {
            LOGGER.error("Level is null during animated placement!");
            cancelAnimatedPlacement();
            return;
        }
        
        // Place the next batch of layers
        int layersPlaced = 0;
        int blocksPlaced = 0;
        
        while (currentLayerIndex < layersByY.size() && layersPlaced < LAYERS_PER_TICK) {
            List<Map.Entry<BlockPos, Integer>> layer = layersByY.get(currentLayerIndex);
            
            // Place all blocks in this layer
            for (Map.Entry<BlockPos, Integer> entry : layer) {
                BlockPos voxelPos = entry.getKey();
                int color = entry.getValue();
                
                // Apply rotation to the voxel position
                BlockPos rotatedPos = applyPlacementRotation(voxelPos);
                
                // Calculate world position
                BlockPos worldPos = placementOrigin.offset(rotatedPos);
                
                // Get the closest matching block
                BlockState blockState = BlockMapper.getClosestBlock(color);
                
                // Place the block
                level.setBlock(worldPos, blockState, 3);
                blocksPlaced++;
            }
            
            currentLayerIndex++;
            layersPlaced++;
        }
        
        // Check if we're done
        if (currentLayerIndex >= layersByY.size()) {
            LOGGER.info("Animated placement complete! Placed {} blocks", getTotalBlockCount());
            isAnimatingPlacement = false;
            layersByY = null;
            placementOrigin = null;
            currentLayerIndex = 0;
        }
    }
    
    /**
     * Cancels the animated placement
     */
    private static void cancelAnimatedPlacement() {
        isAnimatingPlacement = false;
        layersByY = null;
        placementOrigin = null;
        placementRotation = 0;
        placementGridSize = 0;
        currentLayerIndex = 0;
    }
    
    /**
     * Applies the saved placement rotation to a voxel position
     */
    private static BlockPos applyPlacementRotation(BlockPos pos) {
        if (placementRotation == 0) {
            return pos;
        }
        
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int s = placementGridSize - 1;
        
        return switch (placementRotation) {
            case 1 -> new BlockPos(s - z, y, x);      // 90° CW
            case 2 -> new BlockPos(s - x, y, s - z);  // 180°
            case 3 -> new BlockPos(z, y, s - x);      // 270° CW
            default -> pos;
        };
    }
    
    /**
     * Gets the total number of blocks to place
     */
    private static int getTotalBlockCount() {
        if (layersByY == null) return 0;
        return layersByY.stream().mapToInt(List::size).sum();
    }
    
    /**
     * Gets the appropriate level for block placement
     */
    private static Level getPlacementLevel(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        
        if (server != null) {
            // Single-player: use the integrated server's level for proper persistence
            ClientLevel clientLevel = minecraft.level;
            if (clientLevel == null) {
                return null;
            }
            
            // Get the server-side level with the same dimension as the client
            ServerLevel serverLevel = server.getLevel(clientLevel.dimension());
            if (serverLevel != null) {
                return serverLevel;
            } else {
                LOGGER.warn("Could not get server level, falling back to client level");
                return clientLevel;
            }
        } else {
            // Multiplayer: we can only place on client side (won't persist)
            return minecraft.level;
        }
    }
}

