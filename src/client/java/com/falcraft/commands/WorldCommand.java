package com.falcraft.commands;

import com.falcraft.util.PlacementPreview;
import com.falcraft.util.SplatParser;
import com.falcraft.util.SpzParser;
import com.falcraft.util.Voxelizer;
import com.falcraft.util.WorldVoxelizer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Voxelizes a Marble (World Labs) world splat and places it via the standard
 * ghost preview. For now this loads LOCAL .spz files (fast iteration on
 * already-generated worlds); the Marble generate API can be wired in later.
 *
 * Usage:
 *   /fal world load [blocksPerMeter] <path-to.spz>
 *
 * blocksPerMeter defaults to 4 (a real-world meter becomes 4 blocks). The Marble
 * world JSON (metric scale + ground plane) is auto-located next to the .spz.
 */
public class WorldCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldCommand");
    private static final Gson GSON = new Gson();
    private static final double DEFAULT_BPM = 4.0;

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("fal")
                .then(literal("world")
                        .then(literal("load")
                                .then(argument("args", StringArgumentType.greedyString())
                                        .executes(WorldCommand::executeLoad)))));
    }

    private static int executeLoad(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "args").trim();
        FabricClientCommandSource source = context.getSource();

        // Optional leading blocks-per-meter number: "/fal world load 4 C:\...\file.spz"
        double bpm = DEFAULT_BPM;
        String pathStr = raw;
        int firstSpace = raw.indexOf(' ');
        if (firstSpace > 0) {
            String head = raw.substring(0, firstSpace);
            try {
                bpm = Double.parseDouble(head);
                pathStr = raw.substring(firstSpace + 1).trim();
            } catch (NumberFormatException ignored) {
                // no bpm prefix; whole string is the path
            }
        }

        Path spzPath = Paths.get(stripQuotes(pathStr));
        if (!Files.exists(spzPath)) {
            source.sendError(Component.literal("§c[fal] File not found: " + spzPath));
            return 0;
        }

        final double finalBpm = bpm;
        source.sendFeedback(Component.literal("§d[fal] Loading world splat (" + finalBpm + " blocks/meter)..."));
        source.sendFeedback(Component.literal("§7[fal] " + spzPath.getFileName()));

        new Thread(() -> {
            try {
                byte[] bytes = Files.readAllBytes(spzPath);

                WorldVoxelizer.Options opts = WorldVoxelizer.Options.defaults();
                opts.blocksPerMeter = finalBpm;
                applyMarbleMetadata(spzPath, opts, source);

                String fileName = spzPath.toString();
                WorldVoxelizer.VoxelWorld world;
                if (fileName.endsWith(".glb")) {
                    // Marble collider mesh: watertight geometry with baked vertex colors (best quality)
                    world = WorldVoxelizer.voxelizeMesh(com.falcraft.util.GLBParser.parse(bytes, null), opts);
                } else {
                    SplatParser.SplatData splat = fileName.endsWith(".splat")
                            ? SplatParser.parse(bytes)
                            : SpzParser.parse(bytes);
                    world = WorldVoxelizer.voxelize(splat, opts);
                }
                if (world.voxels().isEmpty()) {
                    Minecraft.getInstance().execute(() ->
                            source.sendError(Component.literal("§c[fal] World produced no voxels!")));
                    return;
                }

                Voxelizer.VoxelGrid grid = toVoxelGrid(world);

                Minecraft.getInstance().execute(() -> {
                    PlacementPreview.startPlacement(grid);
                    source.sendFeedback(Component.literal(String.format(
                            "§a[fal] ✓ World ready: %d blocks, %dx%dx%d",
                            world.voxels().size(), world.sizeX(), world.sizeY(), world.sizeZ())));
                    source.sendFeedback(Component.literal(
                            "§e[fal] Right-click to place, G to rotate, H/N to move!"));
                });

            } catch (Exception e) {
                LOGGER.error("Failed to load world splat", e);
                String msg = e.getMessage();
                Minecraft.getInstance().execute(() ->
                        source.sendError(Component.literal("§c[fal] Error loading world: " + msg)));
            }
        }, "fal-WorldLoad-Thread").start();

        return 1;
    }

    /**
     * Looks for the Marble world JSON next to the .spz (e.g. "0-world-500k.spz" ->
     * "0-world.json") and applies metric_scale_factor + ground_plane_offset.
     */
    private static void applyMarbleMetadata(Path spzPath, WorldVoxelizer.Options opts,
            FabricClientCommandSource source) {
        Path json = findWorldJson(spzPath);
        if (json == null) {
            Minecraft.getInstance().execute(() -> source.sendFeedback(Component.literal(
                    "§6[fal] No world JSON found next to .spz - using scale 1.0, ground 0")));
            return;
        }
        try {
            JsonObject worldJson = GSON.fromJson(Files.readString(json), JsonObject.class);
            JsonObject meta = worldJson.getAsJsonObject("assets")
                    .getAsJsonObject("splats")
                    .getAsJsonObject("semantics_metadata");
            opts.metricScaleFactor = meta.get("metric_scale_factor").getAsDouble();
            opts.groundPlaneOffset = meta.get("ground_plane_offset").getAsDouble();
            LOGGER.info("Applied Marble metadata from {}: scale={} ground={}",
                    json.getFileName(), opts.metricScaleFactor, opts.groundPlaneOffset);
        } catch (Exception e) {
            LOGGER.warn("Could not read Marble metadata from {}: {}", json, e.getMessage());
        }
    }

    private static Path findWorldJson(Path spzPath) {
        String name = spzPath.getFileName().toString();
        // "0-world-500k.spz" / "0-world-full_res.spz" / "0-world.glb" -> "0-world.json"
        String base = name.replaceAll("-(100k|150k|500k|full_res)\\.spz$", "")
                .replaceAll("\\.(spz|glb)$", "");
        Path candidate = spzPath.resolveSibling(base + ".json");
        if (Files.exists(candidate)) return candidate;

        // Fallback: any *.json sibling containing semantics_metadata
        try (var stream = Files.list(spzPath.getParent())) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("semantics_metadata");
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Converts the MC-free VoxelWorld into the VoxelGrid the placement system uses. */
    private static Voxelizer.VoxelGrid toVoxelGrid(WorldVoxelizer.VoxelWorld world) {
        Map<BlockPos, Integer> voxels = new HashMap<>(world.voxels().size());
        for (Map.Entry<Long, Integer> e : world.voxels().entrySet()) {
            long key = e.getKey();
            voxels.put(new BlockPos(
                    WorldVoxelizer.unpackX(key),
                    WorldVoxelizer.unpackY(key) - world.minY(), // shift so the lowest layer sits on the ground
                    WorldVoxelizer.unpackZ(key)), e.getValue());
        }
        int size = Math.max(world.sizeX(), Math.max(world.sizeY(), world.sizeZ()));
        return new Voxelizer.VoxelGrid(voxels, size);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
