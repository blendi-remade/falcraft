package com.falcraft;

import com.falcraft.commands.ConfigCommand;
import com.falcraft.commands.GenerateCommand;
import com.falcraft.commands.RemixCommand;
import com.falcraft.commands.CraftCommand;
import com.falcraft.commands.SplatCommand;
import com.falcraft.commands.StreamCommand;
import com.falcraft.render.GhostBlockRenderer;
import com.falcraft.util.PlacementPreview;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FalcraftClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("FalcraftClient");
    
    private static boolean wasRightClickPressed = false;
    private static boolean wasRotateKeyPressed = false;
    private static boolean wasPitchKeyPressed = false;
    private static boolean wasRollKeyPressed = false;
    private static boolean wasMoveUpKeyPressed = false;
    private static boolean wasMoveDownKeyPressed = false;

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
        });
        
        // Register ghost block renderer for placement preview
        WorldRenderEvents.END_MAIN.register(context -> {
            GhostBlockRenderer.render(
                    context.matrices(),
                    context.consumers()
            );
        });
        
        // Register client tick handler for placement confirmation and animated placement
        // This detects right-clicks anywhere, not just when targeting blocks
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            
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
                // During streaming mode, only allow rotation and ESC to cancel
                // Don't allow placement until streaming is complete
                if (PlacementPreview.isStreaming()) {
                    // Detect G key for rotation during streaming
                    boolean isRotateKeyPressed = org.lwjgl.glfw.GLFW.glfwGetKey(
                        Minecraft.getInstance().getWindow().handle(),
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
                    Minecraft.getInstance().getWindow().handle(),
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
}

