package com.falcraft;

import com.falcraft.commands.ConfigCommand;
import com.falcraft.commands.GenerateCommand;
import com.falcraft.commands.RemixCommand;
import com.falcraft.commands.CraftCommand;
import com.falcraft.commands.SplatCommand;
import com.falcraft.commands.ImageCommand;
import com.falcraft.commands.VideoCommand;
import com.falcraft.commands.StreamCommand;
import com.falcraft.render.GhostBlockRenderer;
import com.falcraft.render.HotbarCanvasOverlay;
import com.falcraft.render.ImageCanvasRenderer;
import com.falcraft.util.ImageCanvasManager;
import com.falcraft.util.MapImageFactory;
import com.falcraft.util.PlacementPreview;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class FalcraftClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("FalcraftClient");
    
    private static boolean wasRightClickPressed = false;
    private static boolean wasRotateKeyPressed = false;
    private static boolean wasPitchKeyPressed = false;
    private static boolean wasRollKeyPressed = false;
    private static boolean wasMoveUpKeyPressed = false;
    private static boolean wasMoveDownKeyPressed = false;

    // Image-canvas placement key edges
    private static boolean imgWasRightClickPressed = false;
    private static boolean imgWasRotatePressed = false;
    private static boolean imgWasGrowPressed = false;
    private static boolean imgWasShrinkPressed = false;
    private static boolean imgWasSnapPressed = false;

    @Override
    public void onInitializeClient() {
        // Register the client-side commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            ConfigCommand.register(dispatcher);
            // RemixCommand.register(dispatcher);  // Coming in v1.1.0
            GenerateCommand.register(dispatcher);
            StreamCommand.register(dispatcher);  // Streaming 3D generation
            CraftCommand.register(dispatcher);   // Nano Banana Pro + Hunyuan 3D
            SplatCommand.register(dispatcher);   // Nano Banana Pro + TripoSplat (experimental)
            ImageCommand.register(dispatcher);   // AI image -> in-world canvas
            VideoCommand.register(dispatcher);   // image -> looping video canvas
        });

        // Register ghost block renderer for placement preview
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            GhostBlockRenderer.render(
                context.matrixStack(),
                context.consumers(),
                context.tickCounter().getGameTimeDeltaPartialTick(true)
            );
            // Render placed image canvases + live placement preview
            ImageCanvasRenderer.render(
                context.matrixStack(),
                context.camera().getPosition()
            );
        });

        // Draw the AI image as a thumbnail over hotbar slots holding canvas items
        HudRenderCallback.EVENT.register((guiGraphics, tickDelta) -> HotbarCanvasOverlay.render(guiGraphics));

        // Left-click an image canvas to remove it (cancels the block-break behind it)
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide && !ImageCanvasManager.isPreviewActive()) {
                String removedId = ImageCanvasManager.removeLookedAt(player);
                if (removedId != null) {
                    giveBackCanvasItem(removedId); // hand the map back so it can be re-placed
                    return InteractionResult.FAIL; // consume the click, don't break the wall
                }
            }
            return InteractionResult.PASS;
        });
        
        // Register client tick handler for placement confirmation and animated placement
        // This detects right-clicks anywhere, not just when targeting blocks
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Load/unload persisted image canvases as the world changes (runs even with no player)
            ImageCanvasManager.tickLoad(client);
            // Advance any playing video canvases
            ImageCanvasManager.tickVideos();

            if (client.player == null) return;

            // Holding a tagged map item drives the image placement preview
            ItemStack held = client.player.getMainHandItem();
            String heldCanvasId = MapImageFactory.getFalcraftId(held);
            if (heldCanvasId != null) {
                ImageCanvasManager.startPreviewForId(heldCanvasId);
                if (ImageCanvasManager.isPreviewActive()) {
                    // Don't apply placement hotkeys while a screen (chat/console/menu) is open
                    if (client.screen == null) {
                        handleImagePlacementKeys(client);
                    } else {
                        imgWasRightClickPressed = false;
                        imgWasRotatePressed = false;
                        imgWasGrowPressed = false;
                        imgWasShrinkPressed = false;
                        imgWasSnapPressed = false;
                    }
                    return;
                }
            } else if (ImageCanvasManager.isPreviewActive()) {
                ImageCanvasManager.cancelPreview();
            }

            // Tick animated placement if active
            if (PlacementPreview.isAnimatingPlacement()) {
                PlacementPreview.tickAnimatedPlacement();
            }
            
            // Tick noise animation during streaming - makes chaos ALIVE from the start!
            if (PlacementPreview.isStreaming()) {
                PlacementPreview.tickNoiseAnimation();
            }
            
            // Check if placement mode is active
            if (PlacementPreview.isPlacementActive()) {
                // Don't apply placement hotkeys while a screen (chat/console/menu) is open
                if (client.screen != null) {
                    wasRightClickPressed = false;
                    wasRotateKeyPressed = false;
                    wasPitchKeyPressed = false;
                    wasRollKeyPressed = false;
                    wasMoveUpKeyPressed = false;
                    wasMoveDownKeyPressed = false;
                    return;
                }
                // During streaming mode, only allow rotation and ESC to cancel
                // Don't allow placement until streaming is complete
                if (PlacementPreview.isStreaming()) {
                    // Detect G key for rotation during streaming
                    boolean isRotateKeyPressed = org.lwjgl.glfw.GLFW.glfwGetKey(
                        Minecraft.getInstance().getWindow().getWindow(),
                        org.lwjgl.glfw.GLFW.GLFW_KEY_G
                    ) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                    
                    if (isRotateKeyPressed && !wasRotateKeyPressed) {
                        PlacementPreview.rotate();
                        int degrees = PlacementPreview.getRotationIndex() * 90;
                        client.player.displayClientMessage(
                            Component.literal("§b[fal] Rotated to " + degrees + "°"),
                            true
                        );
                    }
                    wasRotateKeyPressed = isRotateKeyPressed;
                    wasRightClickPressed = false;
                    return;
                }
                
                // Detect right-click (use attack button press)
                boolean isRightClickPressed = client.options.keyUse.isDown();
                
                // Trigger on rising edge (button just pressed, not held)
                if (isRightClickPressed && !wasRightClickPressed) {
                    PlacementPreview.confirmPlacement();
                    client.player.displayClientMessage(
                        Component.literal("§e[fal] ⚡ Building structure..."),
                        false
                    );
                }
                wasRightClickPressed = isRightClickPressed;
                
                // Detect G key for rotation (not R, as R conflicts with shader reload)
                boolean isRotateKeyPressed = org.lwjgl.glfw.GLFW.glfwGetKey(
                    Minecraft.getInstance().getWindow().getWindow(),
                    org.lwjgl.glfw.GLFW.GLFW_KEY_G
                ) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                
                if (isRotateKeyPressed && !wasRotateKeyPressed) {
                    PlacementPreview.rotate();
                    int degrees = PlacementPreview.getRotationIndex() * 90;
                    client.player.displayClientMessage(
                        Component.literal("§e[fal] Rotated to " + degrees + "°"),
                        true
                    );
                }
                wasRotateKeyPressed = isRotateKeyPressed;

                // V key: pitch (rotate around X axis)
                boolean isPitchKeyPressed = org.lwjgl.glfw.GLFW.glfwGetKey(
                    Minecraft.getInstance().getWindow().getWindow(),
                    org.lwjgl.glfw.GLFW.GLFW_KEY_V
                ) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                if (isPitchKeyPressed && !wasPitchKeyPressed) {
                    PlacementPreview.cyclePitch();
                    client.player.displayClientMessage(
                        Component.literal("§e[fal] Pitch (V) | G=yaw B=roll H/N=up/down"),
                        true);
                }
                wasPitchKeyPressed = isPitchKeyPressed;

                // B key: roll (rotate around Z axis)
                boolean isRollKeyPressed = org.lwjgl.glfw.GLFW.glfwGetKey(
                    Minecraft.getInstance().getWindow().getWindow(),
                    org.lwjgl.glfw.GLFW.GLFW_KEY_B
                ) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                if (isRollKeyPressed && !wasRollKeyPressed) {
                    PlacementPreview.cycleRoll();
                    client.player.displayClientMessage(
                        Component.literal("§e[fal] Roll (B) | G=yaw V=pitch H/N=up/down"),
                        true);
                }
                wasRollKeyPressed = isRollKeyPressed;

                // H key: move up
                boolean isMoveUpPressed = org.lwjgl.glfw.GLFW.glfwGetKey(
                    Minecraft.getInstance().getWindow().getWindow(),
                    org.lwjgl.glfw.GLFW.GLFW_KEY_H
                ) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                if (isMoveUpPressed && !wasMoveUpKeyPressed) {
                    PlacementPreview.moveUp();
                    client.player.displayClientMessage(
                        Component.literal("§e[fal] Y offset: " + PlacementPreview.getYOffset()),
                        true);
                }
                wasMoveUpKeyPressed = isMoveUpPressed;

                // N key: move down
                boolean isMoveDownPressed = org.lwjgl.glfw.GLFW.glfwGetKey(
                    Minecraft.getInstance().getWindow().getWindow(),
                    org.lwjgl.glfw.GLFW.GLFW_KEY_N
                ) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                if (isMoveDownPressed && !wasMoveDownKeyPressed) {
                    PlacementPreview.moveDown();
                    client.player.displayClientMessage(
                        Component.literal("§e[fal] Y offset: " + PlacementPreview.getYOffset()),
                        true);
                }
                wasMoveDownKeyPressed = isMoveDownPressed;
            } else {
                wasRightClickPressed = false;
                wasRotateKeyPressed = false;
                wasPitchKeyPressed = false;
                wasRollKeyPressed = false;
                wasMoveUpKeyPressed = false;
                wasMoveDownKeyPressed = false;
            }
        });
        
        LOGGER.info("Falcraft client initialized");
    }

    /**
     * Handles input while an image-canvas placement preview is active:
     * right-click to place, G to rotate facing, H/N to grow/shrink.
     */
    private static void handleImagePlacementKeys(Minecraft client) {
        long window = client.getWindow().getWindow();

        // Right-click (use key) places the canvas at the current target
        boolean rightClick = client.options.keyUse.isDown();
        if (rightClick && !imgWasRightClickPressed) {
            if (ImageCanvasManager.confirmPlacement()) {
                consumeHeldCanvasItem(client); // one map -> one placed canvas
                if (client.player != null) {
                    client.player.displayClientMessage(Component.literal("§a[fal] 🖼 Image placed!"), true);
                }
            }
        }
        imgWasRightClickPressed = rightClick;

        // G: rotate facing
        boolean rotate = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_G) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (rotate && !imgWasRotatePressed) {
            ImageCanvasManager.rotateFacing();
            if (client.player != null) {
                client.player.displayClientMessage(Component.literal("§e[fal] Rotated facing"), true);
            }
        }
        imgWasRotatePressed = rotate;

        // H: grow
        boolean grow = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_H) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (grow && !imgWasGrowPressed) {
            ImageCanvasManager.grow();
            if (client.player != null) {
                client.player.displayClientMessage(Component.literal("§e[fal] Size: " + ImageCanvasManager.getWidthBlocks() + " wide"), true);
            }
        }
        imgWasGrowPressed = grow;

        // N: shrink
        boolean shrink = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_N) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (shrink && !imgWasShrinkPressed) {
            ImageCanvasManager.shrink();
            if (client.player != null) {
                client.player.displayClientMessage(Component.literal("§e[fal] Size: " + ImageCanvasManager.getWidthBlocks() + " wide"), true);
            }
        }
        imgWasShrinkPressed = shrink;

        // F: toggle snap-to-block vs free-hand
        boolean snap = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_F) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (snap && !imgWasSnapPressed) {
            ImageCanvasManager.toggleSnap();
            if (client.player != null) {
                client.player.displayClientMessage(Component.literal(
                        "§e[fal] Snap: " + (ImageCanvasManager.isSnapEnabled() ? "ON (block-aligned)" : "OFF (free-hand)")), true);
            }
        }
        imgWasSnapPressed = snap;
    }

    /** Removes one tagged canvas map from the player's selected slot (server-side). */
    private static void consumeHeldCanvasItem(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        UUID uuid = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) return;
            ItemStack selected = sp.getInventory().getSelected();
            if (MapImageFactory.getFalcraftId(selected) != null) {
                selected.shrink(1);
            }
        });
    }

    /** Re-mints the tagged map for a removed canvas and gives it back to the player (server-side). */
    private static void giveBackCanvasItem(String id) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        byte[] png = ImageCanvasManager.readImageBytes(id);
        if (png == null) return;
        UUID uuid = mc.player.getUUID();
        server.execute(() -> {
            try {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) return;
                ItemStack map = MapImageFactory.createTaggedMap(sp.serverLevel(), png, id);
                if (!sp.getInventory().add(map)) {
                    sp.drop(map, false);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to give back canvas item {}", id, e);
            }
        });
    }
}

