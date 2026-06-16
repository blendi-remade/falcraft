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

import java.util.Map;

/**
 * Places voxel grids as blocks in the Minecraft world
 */
public class BlockPlacer {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlockPlacer");
    
    /**
     * Places a voxel grid in the Minecraft world
     * The grid is placed based on the player's look direction
     * @param grid The voxel grid to place
     * @return The number of blocks placed
     */
    public static int placeVoxelGrid(Voxelizer.VoxelGrid grid) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        
        if (player == null) {
            LOGGER.error("Player is null!");
            return 0;
        }
        
        // Get the appropriate level based on whether we're in single-player or multiplayer
        Level level;
        IntegratedServer server = minecraft.getSingleplayerServer();
        
        if (server != null) {
            // Single-player: use the integrated server's level for proper persistence
            ClientLevel clientLevel = minecraft.level;
            if (clientLevel == null) {
                LOGGER.error("Client level is null!");
                return 0;
            }
            
            // Get the server-side level with the same dimension as the client
            ServerLevel serverLevel = server.getLevel(clientLevel.dimension());
            if (serverLevel != null) {
                level = serverLevel;
                LOGGER.info("Using server-side level for block placement (single-player)");
            } else {
                LOGGER.warn("Could not get server level, falling back to client level");
                level = clientLevel;
            }
        } else {
            // Multiplayer: we can only place on client side (won't persist)
            level = minecraft.level;
            LOGGER.warn("Multiplayer detected - blocks may not persist! Consider using a server-side mod.");
        }
        
        if (level == null) {
            LOGGER.error("Level is null!");
            return 0;
        }
        
        // Calculate placement origin based on player look direction
        BlockPos origin = calculatePlacementOrigin(player, grid.size());
        
        LOGGER.info("Placing voxel grid at origin: {} (level side: {})", origin, level.isClientSide() ? "CLIENT" : "SERVER");
        
        int blocksPlaced = 0;
        
        // Place each voxel as a block
        for (Map.Entry<BlockPos, Integer> entry : grid.voxels().entrySet()) {
            BlockPos voxelPos = entry.getKey();
            int color = entry.getValue();
            
            // Calculate world position
            BlockPos worldPos = origin.offset(voxelPos);
            
            // Get the closest matching block
            BlockState blockState = BlockMapper.getClosestBlock(color);
            
            // Place the block with proper flags for server-side placement
            // Flags: 3 = UPDATE_NEIGHBORS | BLOCK_UPDATE (notify neighbors and update)
            level.setBlock(worldPos, blockState, 3);
            blocksPlaced++;
        }
        
        LOGGER.info("Placed {} blocks on {} side", blocksPlaced, level.isClientSide() ? "CLIENT" : "SERVER");
        
        return blocksPlaced;
    }
    
    /**
     * Calculates where to place the voxel grid based on what block the player is looking at
     * Uses raycasting to find the target block, then centers the structure on top of it
     */
    private static BlockPos calculatePlacementOrigin(LocalPlayer player, int gridSize) {
        // Raycast to find what block the player is looking at
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
    private static int findGroundFromAbove(Level level, BlockPos startPos, int startY) {
        // Search down from start position (up to 100 blocks)
        for (int y = startY; y >= startY - 100; y--) {
            BlockPos checkPos = new BlockPos(startPos.getX(), y, startPos.getZ());
            BlockState blockState = level.getBlockState(checkPos);
            BlockState aboveState = level.getBlockState(checkPos.above());
            
            // Found solid ground with air above
            if (!blockState.isAir() && aboveState.isAir()) {
                return y + 1; // Place on top
            }
        }
        
        // If no ground found within 100 blocks, place at world bottom + some height
        return Math.max(level.getMinY() + 5, startY - 100);
    }
    
    /**
     * Finds the ground level below a position by scanning downward
     * @param level The level to search in
     * @param startPos Starting position for the search
     * @param minY Minimum Y level to search (player's feet level)
     * @return Y coordinate where the model should be placed
     */
    public static int findGroundLevel(Level level, BlockPos startPos, int minY) {
        // Scan downward from start position to find solid ground
        for (int y = startPos.getY(); y >= minY - 10; y--) {
            BlockPos checkPos = new BlockPos(startPos.getX(), y, startPos.getZ());
            BlockState blockState = level.getBlockState(checkPos);
            BlockState aboveState = level.getBlockState(checkPos.above());
            
            // Found ground: solid block with air above
            if (!blockState.isAir() && aboveState.isAir()) {
                LOGGER.info("Found ground at Y={}", y + 1);
                return y + 1; // Place on top of the solid block
            }
        }
        
        // If no ground found, place at player's feet level
        LOGGER.warn("No ground found, placing at player level Y={}", minY);
        return minY;
    }
}

