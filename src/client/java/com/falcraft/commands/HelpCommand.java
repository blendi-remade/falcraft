package com.falcraft.commands;

import com.falcraft.util.GuideBook;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Onboarding help. {@code /fal} and {@code /fal help} print a concise command rundown
 * in chat (works everywhere); {@code /fal guide} hands over the written guide book
 * (single-player only, like other item-giving features).
 */
public class HelpCommand {

    private static final String[] HELP = {
            "§6§l✦ falcraft §r§7— AI generation in Minecraft",
            "§7Set your key once: §f/fal setkey \"YOUR_KEY\"",
            "§b3D §7» §f/fal generate <size> <prompt> §7(Z-Image + SAM-3D)",
            "§7    §f/fal craft <size> <prompt> §7(Hunyuan, textured)",
            "§7    §f/fal stream <size> <prompt> §7(builds live)",
            "§7    §f/fal splat <size> <prompt> §7(Gaussian splat)",
            "§bImage §7» §f/fal image [square|landscape|portrait] <prompt> §7(+ fast)",
            "§bVideo §7» §7hold an image, §f/fal video [short|medium|long] <prompt> §7(+ fast)",
            "§bPlace §7» right-click place · G/V/B rotate · H/N move · F snap · left-click remove",
            "§7Want it as a book? §f/fal guide",
    };

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("fal")
                .executes(ctx -> showHelp(ctx.getSource()))
                .then(literal("help").executes(ctx -> showHelp(ctx.getSource())))
                .then(literal("guide").executes(HelpCommand::giveGuide)));
    }

    private static int showHelp(FabricClientCommandSource source) {
        for (String line : HELP) {
            source.sendFeedback(Component.literal(line));
        }
        return 1;
    }

    private static int giveGuide(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft mc = Minecraft.getInstance();

        if (mc.getSingleplayerServer() == null || mc.player == null) {
            source.sendFeedback(Component.literal("§7[fal] The guide book is single-player only — here's the rundown:"));
            return showHelp(source);
        }

        UUID uuid = mc.player.getUUID();
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) return;
            GuideBook.giveTo(sp);
            GuideBook.markGiven();
            mc.execute(() -> source.sendFeedback(Component.literal("§a[fal] ✓ Added the falcraft guide to your inventory!")));
        });
        return 1;
    }
}
