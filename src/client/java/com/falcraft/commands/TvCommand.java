package com.falcraft.commands;

import com.falcraft.util.DirectorBridge;
import com.falcraft.util.ImageCanvasManager;
import com.falcraft.util.MapImageFactory;
import com.falcraft.util.TvChannels;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * FalTV: an endless, live-generated television broadcast on an in-world canvas,
 * rendered continuously by MiniMax H3 Max Director (via the local bridge).
 *
 *   /fal tv <channel|prompt>   tune a channel (preset key or freeform words)
 *   /fal tv off                stop the broadcast (freezes on the last frame)
 *
 * Once on air, any chat message starting with "!" steers the show live
 * (handled in FalcraftClient).
 */
public final class TvCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("TvCommand");
    private static final int W = 640;
    private static final int H = 360;

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> tv = literal("tv");

        // /fal tv off
        tv.then(literal("off").executes(ctx -> {
            DirectorBridge.get().off();
            ctx.getSource().sendFeedback(Component.literal("§7[fal] Broadcast stopped."));
            return 1;
        }));

        // /fal tv <channel>  with preset suggestions
        tv.then(argument("channel", StringArgumentType.greedyString())
                .suggests((c, b) -> {
                    for (String key : TvChannels.keys()) b.suggest(key);
                    return b.buildFuture();
                })
                .executes(TvCommand::execute));

        // /fal tv  (no args) lists channels
        tv.executes(ctx -> {
            ctx.getSource().sendFeedback(Component.literal(
                    "§b[fal] FalTV channels: §f" + String.join("§7, §f", TvChannels.keys())));
            ctx.getSource().sendFeedback(Component.literal(
                    "§7Usage: /fal tv <channel|your own idea>   ·   steer with §f!something§7 in chat"));
            return 1;
        });

        dispatcher.register(literal("fal").then(tv));
    }

    private static int execute(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        String arg = StringArgumentType.getString(ctx, "channel").trim();

        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) {
            source.sendError(Component.literal("§c[fal] FalTV is single-player only."));
            return 0;
        }
        if (mc.player == null) return 0;

        TvChannels.Channel channel =
                TvChannels.has(arg) ? TvChannels.get(arg) : TvChannels.freeform(arg);

        String id = ImageCanvasManager.newId();
        byte[] card = tuningCard();
        ImageCanvasManager.saveImageBytes(id, card);

        source.sendFeedback(Component.literal("§d[fal] 📺 Tuning §b" + channel.label() + "§d…"));
        source.sendFeedback(Component.literal(
                "§7[fal] The bridge must be running (npm start in falcraft/bridge). Hold the TV map and right-click a wall to hang it."));

        UUID uuid = mc.player.getUUID();
        // Register the live texture (render thread), then mint the item (server thread).
        ImageCanvasManager.registerLive(id, W, H);
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                try {
                    ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                    if (sp == null) return;
                    ItemStack map = MapImageFactory.createTaggedMap(sp.serverLevel(), card, id);
                    map.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                            Component.literal("§b[fal] 📺 " + channel.label()));
                    if (!sp.getInventory().add(map)) sp.drop(map, false);
                } catch (Exception e) {
                    LOGGER.error("Failed to mint TV item", e);
                }
            });
        }

        // Open/steer the bridge to this channel; frames start flowing into the id.
        DirectorBridge.get().tune(id, channel.prompt(), "480p");
        return 1;
    }

    /** A dark 16:9 "tuning" card, used as the item thumbnail and pre-signal frame. */
    private static byte[] tuningCard() {
        NativeImage img = new NativeImage(W, H, false);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean border = x < 6 || x >= W - 6 || y < 6 || y >= H - 6;
                // ABGR packed. A dim panel with a magenta frame.
                int color = border ? 0xFFCC33AA : 0xFF160E14;
                img.setPixelRGBA(x, y, color);
            }
        }
        try {
            return img.asByteArray();
        } catch (Exception e) {
            LOGGER.error("Failed to build tuning card", e);
            return new byte[0];
        } finally {
            img.close();
        }
    }
}
