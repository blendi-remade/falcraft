package com.falcraft;

import com.falcraft.commands.*;
import com.falcraft.render.GhostBlockRenderer;
import com.falcraft.util.PlacementPreview;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FalcraftClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("FalcraftClient");
    
    private static boolean wasRightClickPressed = false;
    private static KeyMapping rotateKey;

    private void handleRotationInput(Minecraft client) {
        // consumeClick() returns true only once per press
        while (FalcraftClient.rotateKey.consumeClick()) {
            PlacementPreview.rotate();
            int degrees = PlacementPreview.getRotationIndex() * 90;

            String colorCode = PlacementPreview.isStreaming() ? "§b" : "§e";

            client.player.displayClientMessage(
                    Component.literal(colorCode + "[fal] Rotated horizontally to " + degrees + "°"),
                    true
            );
        }
    }

    @Override
    public void onInitializeClient() {
        // Register the client-side commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            ConfigCommand.register(dispatcher);
            // RemixCommand.register(dispatcher);  // Coming in v1.1.0
            GenerateCommand.register(dispatcher);
            StreamCommand.register(dispatcher);  // Streaming 3D generation
        });
        
        // Register ghost block renderer for placement preview
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            GhostBlockRenderer.render(
                context.matrixStack(),
                context.consumers(),
                context.tickCounter().getGameTimeDeltaPartialTick(true)
            );
        });

        // Register the rotation key (Defaults to G)
        rotateKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.fal.rotate",                 // Translation key (for lang file)
                InputConstants.Type.KEYSYM,       // Key type
                GLFW.GLFW_KEY_G,                  // Default key
                "category.fal.general"            // Category in Controls menu
        ));

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

            handleRotationInput(client);

            if (PlacementPreview.isPlacementActive()) {

                handleRotationInput(client);

                if (PlacementPreview.isStreaming()) {
                    wasRightClickPressed = false; // Reset click safety
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
            } else {
                // Reset flags when inactive
                wasRightClickPressed = false;
            }
        });
        
        LOGGER.info("Falcraft client initialized");
    }
}

