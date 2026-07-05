package com.falcraft.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The in-game "falcraft Guide" written book: a concise onboarding reference handed to
 * players (single-player) on first join and via /fal guide. Content mirrors /fal help.
 */
public class GuideBook {
    private static final Logger LOGGER = LoggerFactory.getLogger("GuideBook");

    private static final String[] PAGES = {
            "falcraft\nAI generation, right in Minecraft.\n\nSetup:\nRun /fal setkey \"YOUR_KEY\"\n(grab a key at fal.ai)\n\nThen flip the page for commands.",
            "3D models\n\n/fal generate <size> <prompt>\n/fal craft <size> <prompt>\n/fal stream <size> <prompt>\n/fal splat <size> <prompt>\n\nSize 16-128.\ncraft = textured,\nstream = watch it build live.",
            "Images\n\n/fal image <prompt>\nAdd square / landscape / portrait,\nor 'fast' for a quicker model.\n\nExample:\n/fal image landscape a castle",
            "Video\n\nHold an image, then:\n/fal video <prompt>\nAdd short / medium / long,\nor 'fast'.\n\nThe video keeps your image's\nshape and animates it.",
            "Placing things\n\nHold the item, look at a wall:\nRight-click = place\nG / V / B = rotate\nH / N = move\nF = snap to grid\nLeft-click a placed one = remove\n\nLost this? Run /fal guide.",
    };

    /** Builds the falcraft guide as a written_book item stack. */
    public static ItemStack create() {
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String page : PAGES) {
            pages.add(Filterable.passThrough(Component.literal(page)));
        }
        WrittenBookContent content = new WrittenBookContent(
                Filterable.passThrough("falcraft Guide"), "fal", 0, pages, true);

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        return book;
    }

    /** Gives the guide to a server player (single-player), dropping it if the inventory is full. */
    public static void giveTo(ServerPlayer player) {
        ItemStack book = create();
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
    }

    // ---- "given once" marker (global, so we don't re-hand it every world) ----

    private static Path markerPath() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("falcraft").resolve("guide-given");
    }

    public static boolean wasGiven() {
        return Files.exists(markerPath());
    }

    public static void markGiven() {
        try {
            Files.createDirectories(markerPath().getParent());
            Files.writeString(markerPath(), "1");
        } catch (Exception e) {
            LOGGER.warn("Could not write guide marker", e);
        }
    }
}
