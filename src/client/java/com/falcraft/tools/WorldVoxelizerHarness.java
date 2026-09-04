package com.falcraft.tools;

import com.falcraft.util.SplatParser;
import com.falcraft.util.SpzParser;
import com.falcraft.util.WorldVoxelizer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Headless test harness for the Marble world splat -> voxel pipeline. Runs without
 * launching Minecraft (no MC classes are loaded), so iteration takes seconds:
 *
 *   gradlew voxelizeWorld "-Pspz=<path>.spz" ["-Pjson=<0-world.json>"] ["-Pbpm=1.0"]
 *           ["-Popacity=0.15"] ["-Pdensity=0.0"] ["-PmaxAxis=512"] ["-Pout=<dir>"]
 *
 * If --json is given, metric_scale_factor and ground_plane_offset are read from the
 * Marble world response (assets.splats.semantics_metadata).
 *
 * Outputs into <out> (default: <spz dir>/voxel-out):
 *   voxels.ply     - colored point cloud of voxel centers (open in any 3D viewer)
 *   top.png        - top-down view (highest voxel per column)
 *   front.png      - front view looking along +Z (nearest voxel per (x,y))
 *   side.png       - side view looking along +X (nearest voxel per (z,y))
 *   stats.txt      - counts, bounds, per-Y fill histogram
 */
public class WorldVoxelizerHarness {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        Map<String, String> opts = parseArgs(args);
        String spzPath = opts.get("spz");
        String glbPath = opts.get("glb");
        if (spzPath == null && glbPath == null) {
            System.err.println("Usage: WorldVoxelizerHarness (--spz <file.spz|file.splat> | --glb <collider.glb>)"
                    + " [--json <world.json>] [--bpm <blocksPerMeter>] [--opacity <t>] [--density <t>]"
                    + " [--maxAxis <n>] [--out <dir>]");
            System.exit(1);
        }

        Path input = Paths.get(glbPath != null ? glbPath : spzPath);
        WorldVoxelizer.Options vopts = WorldVoxelizer.Options.defaults();

        if (opts.containsKey("json")) {
            JsonObject world = new Gson().fromJson(Files.readString(Paths.get(opts.get("json"))), JsonObject.class);
            JsonObject meta = world.getAsJsonObject("assets")
                    .getAsJsonObject("splats")
                    .getAsJsonObject("semantics_metadata");
            vopts.metricScaleFactor = meta.get("metric_scale_factor").getAsDouble();
            vopts.groundPlaneOffset = meta.get("ground_plane_offset").getAsDouble();
            System.out.printf("Marble metadata: metric_scale_factor=%.4f ground_plane_offset=%.4f%n",
                    vopts.metricScaleFactor, vopts.groundPlaneOffset);
        }
        if (opts.containsKey("bpm")) vopts.blocksPerMeter = Double.parseDouble(opts.get("bpm"));
        if (opts.containsKey("opacity")) vopts.opacityThreshold = Float.parseFloat(opts.get("opacity"));
        if (opts.containsKey("density")) vopts.cellDensityThreshold = Float.parseFloat(opts.get("density"));
        if (opts.containsKey("maxAxis")) vopts.maxAxisBlocks = Integer.parseInt(opts.get("maxAxis"));
        if (opts.containsKey("clip")) vopts.percentileClip = Double.parseDouble(opts.get("clip"));
        if (opts.containsKey("belowGround")) vopts.keepBelowGround = Integer.parseInt(opts.get("belowGround"));
        if (opts.containsKey("radiusM")) vopts.cropRadiusMeters = Double.parseDouble(opts.get("radiusM"));

        Path outDir = opts.containsKey("out")
                ? Paths.get(opts.get("out"))
                : input.toAbsolutePath().getParent().resolve("voxel-out");
        Files.createDirectories(outDir);

        long t0 = System.currentTimeMillis();
        byte[] raw = Files.readAllBytes(input);
        int sourceCount;
        long t1;
        WorldVoxelizer.VoxelWorld world;
        if (glbPath != null) {
            com.falcraft.util.GLBParser.MeshData mesh = com.falcraft.util.GLBParser.parse(raw, null);
            sourceCount = mesh.indices().length / 3;
            t1 = System.currentTimeMillis();
            world = WorldVoxelizer.voxelizeMesh(mesh, vopts);
        } else {
            SplatParser.SplatData splat = spzPath.endsWith(".splat")
                    ? SplatParser.parse(raw)
                    : SpzParser.parse(raw);
            sourceCount = splat.count();
            t1 = System.currentTimeMillis();
            world = WorldVoxelizer.voxelize(splat, vopts);
        }
        long t2 = System.currentTimeMillis();

        writePly(world, outDir.resolve("voxels.ply"));
        writeTopView(world, outDir.resolve("top.png"));
        writeFrontView(world, outDir.resolve("front.png"));
        writeSideView(world, outDir.resolve("side.png"));
        writeStats(world, sourceCount, outDir.resolve("stats.txt"), t1 - t0, t2 - t1);
        long t3 = System.currentTimeMillis();

        System.out.printf("parse %dms | voxelize %dms | outputs %dms%n", t1 - t0, t2 - t1, t3 - t2);
        System.out.printf("%d voxels in %dx%dx%d (minY %d) -> %s%n",
                world.voxels().size(), world.sizeX(), world.sizeY(), world.sizeZ(), world.minY(), outDir);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                String value = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
                map.put(key, value);
            }
        }
        return map;
    }

    // ==================== OUTPUTS ====================

    private static void writePly(WorldVoxelizer.VoxelWorld world, Path path) throws Exception {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.US_ASCII))) {
            out.println("ply");
            out.println("format ascii 1.0");
            out.println("element vertex " + world.voxels().size());
            out.println("property float x");
            out.println("property float y");
            out.println("property float z");
            out.println("property uchar red");
            out.println("property uchar green");
            out.println("property uchar blue");
            out.println("end_header");
            for (Map.Entry<Long, Integer> e : world.voxels().entrySet()) {
                long key = e.getKey();
                int rgb = e.getValue();
                out.printf("%d %d %d %d %d %d%n",
                        WorldVoxelizer.unpackX(key), WorldVoxelizer.unpackY(key), WorldVoxelizer.unpackZ(key),
                        (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            }
        }
    }

    /** Top-down: highest voxel per (x,z) column, mild height shading. */
    private static void writeTopView(WorldVoxelizer.VoxelWorld world, Path path) throws Exception {
        int w = Math.max(1, world.sizeX()), h = Math.max(1, world.sizeZ());
        int[] bestY = new int[w * h];
        int[] color = new int[w * h];
        java.util.Arrays.fill(bestY, Integer.MIN_VALUE);
        for (Map.Entry<Long, Integer> e : world.voxels().entrySet()) {
            long key = e.getKey();
            int x = WorldVoxelizer.unpackX(key), y = WorldVoxelizer.unpackY(key), z = WorldVoxelizer.unpackZ(key);
            int idx = z * w + x;
            if (y > bestY[idx]) {
                bestY[idx] = y;
                color[idx] = e.getValue();
            }
        }
        int maxY = world.minY() + world.sizeY();
        writeImage(path, w, h, (x, z) -> {
            int idx = z * w + x;
            if (bestY[idx] == Integer.MIN_VALUE) return 0xFF000000;
            double shade = 0.55 + 0.45 * (bestY[idx] - world.minY()) / Math.max(1.0, maxY - world.minY());
            return shadeRgb(color[idx], shade);
        });
    }

    /** Front view: looking along +Z (nearest = lowest z wins), mild depth shading. */
    private static void writeFrontView(WorldVoxelizer.VoxelWorld world, Path path) throws Exception {
        int w = Math.max(1, world.sizeX()), h = Math.max(1, world.sizeY());
        int[] bestZ = new int[w * h];
        int[] color = new int[w * h];
        java.util.Arrays.fill(bestZ, Integer.MAX_VALUE);
        for (Map.Entry<Long, Integer> e : world.voxels().entrySet()) {
            long key = e.getKey();
            int x = WorldVoxelizer.unpackX(key), y = WorldVoxelizer.unpackY(key), z = WorldVoxelizer.unpackZ(key);
            int row = (world.sizeY() - 1) - (y - world.minY()); // flip so up is up
            int idx = row * w + x;
            if (z < bestZ[idx]) {
                bestZ[idx] = z;
                color[idx] = e.getValue();
            }
        }
        writeImage(path, w, h, (x, row) -> {
            int idx = row * w + x;
            if (bestZ[idx] == Integer.MAX_VALUE) return 0xFF000000;
            double shade = 1.0 - 0.35 * bestZ[idx] / Math.max(1.0, world.sizeZ());
            return shadeRgb(color[idx], shade);
        });
    }

    /** Side view: looking along +X (nearest = lowest x wins), mild depth shading. */
    private static void writeSideView(WorldVoxelizer.VoxelWorld world, Path path) throws Exception {
        int w = Math.max(1, world.sizeZ()), h = Math.max(1, world.sizeY());
        int[] bestX = new int[w * h];
        int[] color = new int[w * h];
        java.util.Arrays.fill(bestX, Integer.MAX_VALUE);
        for (Map.Entry<Long, Integer> e : world.voxels().entrySet()) {
            long key = e.getKey();
            int x = WorldVoxelizer.unpackX(key), y = WorldVoxelizer.unpackY(key), z = WorldVoxelizer.unpackZ(key);
            int row = (world.sizeY() - 1) - (y - world.minY());
            int idx = row * w + z;
            if (x < bestX[idx]) {
                bestX[idx] = x;
                color[idx] = e.getValue();
            }
        }
        writeImage(path, w, h, (z, row) -> {
            int idx = row * w + z;
            if (bestX[idx] == Integer.MAX_VALUE) return 0xFF000000;
            double shade = 1.0 - 0.35 * bestX[idx] / Math.max(1.0, world.sizeX());
            return shadeRgb(color[idx], shade);
        });
    }

    private interface PixelFn {
        int argb(int px, int py);
    }

    private static void writeImage(Path path, int w, int h, PixelFn fn) throws Exception {
        // Upscale small worlds so the PNGs are comfortably viewable.
        int scale = Math.max(1, 512 / Math.max(w, h));
        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int argb = fn.argb(px, py);
                for (int sy = 0; sy < scale; sy++) {
                    for (int sx = 0; sx < scale; sx++) {
                        img.setRGB(px * scale + sx, py * scale + sy, argb);
                    }
                }
            }
        }
        ImageIO.write(img, "png", path.toFile());
    }

    private static int shadeRgb(int rgb, double shade) {
        shade = Math.max(0.0, Math.min(1.0, shade));
        int r = (int) (((rgb >> 16) & 0xFF) * shade);
        int g = (int) (((rgb >> 8) & 0xFF) * shade);
        int b = (int) ((rgb & 0xFF) * shade);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void writeStats(WorldVoxelizer.VoxelWorld world, int sourceCount,
            Path path, long parseMs, long voxelizeMs) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("source primitives (gaussians/triangles): %d%n", sourceCount));
        sb.append(String.format("voxels: %d%n", world.voxels().size()));
        sb.append(String.format("size: %d x %d x %d (minY %d)%n",
                world.sizeX(), world.sizeY(), world.sizeZ(), world.minY()));
        sb.append(String.format("blocksPerSplatUnit: %.4f%n", world.blocksPerSplatUnit()));
        long volume = (long) world.sizeX() * world.sizeY() * world.sizeZ();
        sb.append(String.format("fill: %.2f%%%n", volume == 0 ? 0 : 100.0 * world.voxels().size() / volume));
        sb.append(String.format("parse: %dms, voxelize: %dms%n", parseMs, voxelizeMs));

        // Per-Y fill histogram (helps spot floor/ceiling/sky layers).
        Map<Integer, Integer> byY = new java.util.TreeMap<>();
        for (long key : world.voxels().keySet()) {
            byY.merge(WorldVoxelizer.unpackY(key), 1, Integer::sum);
        }
        sb.append(String.format("%nvoxels per Y layer:%n"));
        int maxCount = byY.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        for (Map.Entry<Integer, Integer> e : byY.entrySet()) {
            int bars = Math.max(1, e.getValue() * 60 / maxCount);
            sb.append(String.format("y=%4d %7d %s%n", e.getKey(), e.getValue(), "#".repeat(bars)));
        }

        Files.writeString(path, sb.toString());
    }
}
