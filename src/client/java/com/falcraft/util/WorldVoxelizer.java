package com.falcraft.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Voxelizes a Marble (World Labs) world splat into a metric voxel grid.
 *
 * Unlike {@link Voxelizer#voxelizeSplat}, which cube-fits a single *object* into an
 * N^3 grid, worlds keep their native proportions and real-world scale:
 *
 *   grid coords (blocks) = splat units * metricScaleFactor (meters) * blocksPerMeter
 *
 * The Marble response provides {@code metric_scale_factor} and {@code ground_plane_offset}
 * (semantics_metadata); the ground plane sits at splat-Y = -groundPlaneOffset and maps to
 * grid Y = 0, so the world naturally rests on the Minecraft surface it's built at.
 *
 * Deliberately Minecraft-independent (long-packed coords, plain records) so it can run
 * in the headless harness (tools/WorldVoxelizerHarness) for fast iteration without
 * launching the game.
 */
public class WorldVoxelizer {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldVoxelizer");

    /** Tuning knobs, all optional - {@link Options#defaults()} matches Marble output well. */
    public static final class Options {
        /** Splat units -> meters (Marble semantics_metadata.metric_scale_factor). */
        public double metricScaleFactor = 1.0;
        /** Ground plane at splat-Y = -offset maps to grid Y = 0 (semantics_metadata.ground_plane_offset). */
        public double groundPlaneOffset = 0.0;
        /** Blocks per meter; 1.0 = life-size, 2.0 = double scale, etc. */
        public double blocksPerMeter = 1.0;
        /** Gaussians below this opacity are ignored (haze/filler). */
        public float opacityThreshold = 0.15f;
        /** Cells whose accumulated opacity-weight is below this are dropped (isolated noise). */
        public float cellDensityThreshold = 0.25f;
        /** Blocks below grid Y = -this are clipped (below-floor splats). Floor splats sit
         *  slightly below Marble's fitted ground plane, so keep at least 1. */
        public int keepBelowGround = 1;
        /** Max Gaussian footprint radius in cells per axis (caps cost of huge backdrop splats). */
        public int maxSplatRadius = 2;
        /** Fill empty cells with at least this many filled face-neighbors (0 disables). */
        public int solidifyNeighbors = 5;
        /** Solidify passes. */
        public int solidifyPasses = 2;
        /** Per-axis percentile clip (each end) for robust bounds; 0 disables. */
        public double percentileClip = 0.005;
        /** Hard cap on the longest grid axis; scales the world down if exceeded. */
        public int maxAxisBlocks = 512;
        /** Crop to this horizontal radius (meters) around the world origin (Marble worlds
         *  are camera-centered; outdoor scenes have km of ocean/sky backdrop). 0 = off. */
        public double cropRadiusMeters = 0.0;

        public static Options defaults() {
            return new Options();
        }
    }

    /**
     * A voxelized world region. Coordinates are non-negative for X/Z and start at
     * {@code -keepBelowGround} for Y (ground level = Y 0). Keys are packed via {@link #pack}.
     */
    public record VoxelWorld(Map<Long, Integer> voxels, int sizeX, int sizeY, int sizeZ, int minY,
                             double blocksPerSplatUnit) {}

    /** Packs grid coords into a long key (21 bits each, offset-signed Y). */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) ((y + 0x100000) & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    public static int unpackX(long key) {
        return (int) (key >>> 42) & 0x1FFFFF;
    }

    public static int unpackY(long key) {
        return ((int) (key >>> 21) & 0x1FFFFF) - 0x100000;
    }

    public static int unpackZ(long key) {
        return (int) key & 0x1FFFFF;
    }

    /** Per-cell accumulator: opacity-weighted color sum. */
    private static final class Acc {
        double r, g, b, weight;
    }

    public static VoxelWorld voxelize(SplatParser.SplatData splat, Options opts) {
        int n = splat.count();
        if (n == 0) {
            return new VoxelWorld(new HashMap<>(), 0, 0, 0, 0, 1.0);
        }

        float[] pos = splat.positions();
        float[] op = splat.opacities();
        int[] colors = splat.colors();

        // 1) Opacity filter.
        int[] kept = new int[n];
        int keptCount = 0;
        for (int i = 0; i < n; i++) {
            if (op[i] >= opts.opacityThreshold) {
                kept[keptCount++] = i;
            }
        }
        if (keptCount < n / 20) {
            for (int i = 0; i < n; i++) kept[i] = i;
            keptCount = n;
            LOGGER.warn("Opacity filter kept <5% of Gaussians; using full cloud instead");
        }

        // 2) Robust horizontal bounds via percentile clip; Y is anchored to the ground plane.
        double[] xs = new double[keptCount];
        double[] ys = new double[keptCount];
        double[] zs = new double[keptCount];
        for (int k = 0; k < keptCount; k++) {
            int i = kept[k];
            xs[k] = pos[i * 3];
            ys[k] = pos[i * 3 + 1];
            zs[k] = pos[i * 3 + 2];
        }
        double[] xb = percentileBounds(xs, opts.percentileClip);
        double[] yb = percentileBounds(ys, opts.percentileClip);
        double[] zb = percentileBounds(zs, opts.percentileClip);

        // 3) Scale: splat units -> blocks, capped so the longest axis fits maxAxisBlocks.
        double blocksPerUnit = opts.metricScaleFactor * opts.blocksPerMeter;
        double groundY = -opts.groundPlaneOffset;
        double spanX = (xb[1] - xb[0]) * blocksPerUnit;
        double spanZ = (zb[1] - zb[0]) * blocksPerUnit;
        double spanY = (yb[1] - groundY) * blocksPerUnit;
        double longest = Math.max(spanX, Math.max(spanZ, spanY));
        if (longest > opts.maxAxisBlocks) {
            double shrink = opts.maxAxisBlocks / longest;
            blocksPerUnit *= shrink;
            LOGGER.info("World spans {} blocks on longest axis; scaling down by {}",
                    Math.round(longest), String.format("%.3f", shrink));
        }

        // 4) Rasterize each Gaussian's footprint into cells (not just its centroid).
        // The per-axis stddev in blocks decides how many cells it covers; weight falls
        // off with the Gaussian and is scaled by opacity. This is what closes up walls:
        // Marble uses large flat splats for surfaces, and a centroid-only drop leaves holes.
        float[] scales = splat.scales();
        Map<Long, Acc> cells = new HashMap<>(keptCount / 2);
        int clippedBounds = 0, clippedGround = 0;
        for (int k = 0; k < keptCount; k++) {
            int i = kept[k];
            double x = pos[i * 3], y = pos[i * 3 + 1], z = pos[i * 3 + 2];
            if (x < xb[0] || x > xb[1] || y < yb[0] || y > yb[1] || z < zb[0] || z > zb[1]) {
                clippedBounds++;
                continue;
            }

            // Center in grid space (fractional blocks).
            double cx = (x - xb[0]) * blocksPerUnit;
            double cy = (y - groundY) * blocksPerUnit;
            double cz = (z - zb[0]) * blocksPerUnit;
            if (cy < -opts.keepBelowGround) {
                clippedGround++;
                continue;
            }

            // Per-axis stddev in blocks (rotation ignored - fine at block resolution).
            double sx = clampSigma(scales != null ? scales[i * 3] * blocksPerUnit : 0);
            double sy = clampSigma(scales != null ? scales[i * 3 + 1] * blocksPerUnit : 0);
            double sz = clampSigma(scales != null ? scales[i * 3 + 2] * blocksPerUnit : 0);

            int rx = footprintRadius(sx, opts.maxSplatRadius);
            int ry = footprintRadius(sy, opts.maxSplatRadius);
            int rz = footprintRadius(sz, opts.maxSplatRadius);

            int gx0 = (int) Math.floor(cx), gy0 = (int) Math.floor(cy), gz0 = (int) Math.floor(cz);
            int rgb = colors[i];
            double cr = (rgb >> 16) & 0xFF, cg = (rgb >> 8) & 0xFF, cb = rgb & 0xFF;

            for (int gx = Math.max(gx0 - rx, 0); gx <= gx0 + rx; gx++) {
                double dx = (gx + 0.5 - cx) / sx;
                for (int gy = Math.max(gy0 - ry, -opts.keepBelowGround); gy <= gy0 + ry; gy++) {
                    double dy = (gy + 0.5 - cy) / sy;
                    for (int gz = Math.max(gz0 - rz, 0); gz <= gz0 + rz; gz++) {
                        double dz = (gz + 0.5 - cz) / sz;
                        double falloff = Math.exp(-0.5 * (dx * dx + dy * dy + dz * dz));
                        double w = op[i] * falloff;
                        if (w < 0.01) continue;

                        Acc acc = cells.computeIfAbsent(pack(gx, gy, gz), key -> new Acc());
                        acc.r += cr * w;
                        acc.g += cg * w;
                        acc.b += cb * w;
                        acc.weight += w;
                    }
                }
            }
        }

        // 5) Density threshold + weighted color average.
        Map<Long, Integer> voxels = new HashMap<>(cells.size());
        for (Map.Entry<Long, Acc> entry : cells.entrySet()) {
            Acc acc = entry.getValue();
            if (acc.weight < opts.cellDensityThreshold) continue;
            int r = (int) Math.min(255, acc.r / acc.weight);
            int g = (int) Math.min(255, acc.g / acc.weight);
            int b = (int) Math.min(255, acc.b / acc.weight);
            voxels.put(entry.getKey(), (r << 16) | (g << 8) | b);
        }

        // 6) Solidify: close pinholes that survive splatting (empty cells nearly
        // surrounded by filled ones get the average neighbor color).
        int solidified = solidify(voxels, opts);

        int maxX = 0, maxY = 0, maxZ = 0, minY = 0;
        for (long key : voxels.keySet()) {
            maxX = Math.max(maxX, unpackX(key));
            maxY = Math.max(maxY, unpackY(key));
            maxZ = Math.max(maxZ, unpackZ(key));
            minY = Math.min(minY, unpackY(key));
        }

        LOGGER.info("Voxelized world: {} Gaussians ({} after opacity filter, {} clipped bounds, {} below ground)"
                        + " -> {} voxels ({} solidified) in {}x{}x{} (ground at Y=0)",
                n, keptCount, clippedBounds, clippedGround,
                voxels.size(), solidified, maxX + 1, maxY - minY + 1, maxZ + 1);

        return new VoxelWorld(voxels, maxX + 1, maxY - minY + 1, maxZ + 1, minY, blocksPerUnit);
    }

    /**
     * Voxelizes a Marble collider mesh (GLB with baked vertex colors) into the same
     * metric grid as {@link #voxelize}. Because the collider is a closed surface, triangle
     * super-sampling produces hole-free walls/floors - use this as the geometry source
     * when the GLB is available; the splat path remains for color-only or fallback.
     */
    public static VoxelWorld voxelizeMesh(GLBParser.MeshData mesh, Options opts) {
        float[] vertices = mesh.vertices();
        int[] indices = mesh.indices();
        int[] colors = mesh.colors();
        if (vertices.length == 0 || indices.length < 3) {
            return new VoxelWorld(new HashMap<>(), 0, 0, 0, 0, 1.0);
        }

        // Optional origin-centered crop (splat units): drop far backdrop geometry.
        double cropR = opts.cropRadiusMeters > 0 && opts.metricScaleFactor > 0
                ? opts.cropRadiusMeters / opts.metricScaleFactor
                : Double.MAX_VALUE;
        double cropR2 = cropR == Double.MAX_VALUE ? Double.MAX_VALUE : cropR * cropR;

        // Robust bounds over in-crop vertices (collider has a few far outlier verts).
        int vcount = vertices.length / 3;
        double[] xs = new double[vcount];
        double[] ys = new double[vcount];
        double[] zs = new double[vcount];
        int inCrop = 0;
        for (int i = 0; i < vcount; i++) {
            double vx = vertices[i * 3], vy = vertices[i * 3 + 1], vz = vertices[i * 3 + 2];
            if (vx * vx + vz * vz > cropR2) continue;
            xs[inCrop] = vx;
            ys[inCrop] = vy;
            zs[inCrop] = vz;
            inCrop++;
        }
        if (inCrop == 0) {
            return new VoxelWorld(new HashMap<>(), 0, 0, 0, 0, 1.0);
        }
        xs = java.util.Arrays.copyOf(xs, inCrop);
        ys = java.util.Arrays.copyOf(ys, inCrop);
        zs = java.util.Arrays.copyOf(zs, inCrop);
        double[] xb = percentileBounds(xs, opts.percentileClip);
        double[] yb = percentileBounds(ys, opts.percentileClip);
        double[] zb = percentileBounds(zs, opts.percentileClip);
        // Structures (a house on a flat world) are legitimately sparse up high - a broad
        // percentile clip decapitates them. Clip the top of Y only at 0.05%.
        yb[1] = percentileBounds(ys, 0.0005)[1];

        double blocksPerUnit = opts.metricScaleFactor * opts.blocksPerMeter;
        // Ground = the higher of Marble's fitted plane and the content's own floor.
        // Interiors: the metadata plane is right and clips the below-floor collider skirt.
        // Outdoor worlds: the metadata plane can sit far below the water/terrain surface,
        // which would float everything - the content floor (low percentile of Y) wins there.
        double groundY = Math.max(-opts.groundPlaneOffset, yb[0]);
        double spanX = (xb[1] - xb[0]) * blocksPerUnit;
        double spanZ = (zb[1] - zb[0]) * blocksPerUnit;
        double spanY = (yb[1] - groundY) * blocksPerUnit;
        double longest = Math.max(spanX, Math.max(spanZ, spanY));
        if (longest > opts.maxAxisBlocks) {
            double shrink = opts.maxAxisBlocks / longest;
            blocksPerUnit *= shrink;
            LOGGER.info("Mesh world spans {} blocks on longest axis; scaling down by {}",
                    Math.round(longest), String.format("%.3f", shrink));
        }

        // Grid-space upper limits from the robust bounds (skip outlier geometry).
        int limX = (int) Math.ceil((xb[1] - xb[0]) * blocksPerUnit);
        int limY = (int) Math.ceil((yb[1] - groundY) * blocksPerUnit);
        int limZ = (int) Math.ceil((zb[1] - zb[0]) * blocksPerUnit);

        // Triangle super-sampling in grid space (same approach as Voxelizer.voxelize):
        // subdivide so samples land ~half a voxel apart, average sampled colors per cell.
        Map<Long, Acc> cells = new HashMap<>();
        for (int t = 0; t + 2 < indices.length; t += 3) {
            int i0 = indices[t], i1 = indices[t + 1], i2 = indices[t + 2];

            double ax = (vertices[i0 * 3] - xb[0]) * blocksPerUnit;
            double ay = (vertices[i0 * 3 + 1] - groundY) * blocksPerUnit;
            double az = (vertices[i0 * 3 + 2] - zb[0]) * blocksPerUnit;
            double bx = (vertices[i1 * 3] - xb[0]) * blocksPerUnit;
            double by = (vertices[i1 * 3 + 1] - groundY) * blocksPerUnit;
            double bz = (vertices[i1 * 3 + 2] - zb[0]) * blocksPerUnit;
            double cx = (vertices[i2 * 3] - xb[0]) * blocksPerUnit;
            double cy = (vertices[i2 * 3 + 1] - groundY) * blocksPerUnit;
            double cz = (vertices[i2 * 3 + 2] - zb[0]) * blocksPerUnit;

            double e1 = Math.sqrt(sq(bx - ax) + sq(by - ay) + sq(bz - az));
            double e2 = Math.sqrt(sq(cx - bx) + sq(cy - by) + sq(cz - bz));
            double e3 = Math.sqrt(sq(ax - cx) + sq(ay - cy) + sq(az - cz));
            double maxEdge = Math.max(e1, Math.max(e2, e3));
            int n = Math.min(64, Math.max(1, (int) Math.ceil(maxEdge * 2)));

            int col0 = colors != null ? colors[i0] : 0xAAAAAA;
            int col1 = colors != null ? colors[i1] : 0xAAAAAA;
            int col2 = colors != null ? colors[i2] : 0xAAAAAA;

            for (int p = 0; p <= n; p++) {
                for (int q = 0; q <= n - p; q++) {
                    double wa = (double) p / n;
                    double wb = (double) q / n;
                    double wc = 1.0 - wa - wb;

                    double gyd = wa * ay + wb * by + wc * cy;
                    int gy = (int) Math.floor(gyd);
                    if (gy < -opts.keepBelowGround || gy > limY) continue;

                    int gx = (int) Math.floor(wa * ax + wb * bx + wc * cx);
                    int gz = (int) Math.floor(wa * az + wb * bz + wc * cz);
                    if (gx < 0 || gz < 0 || gx > limX || gz > limZ) continue;

                    double r = wa * ((col0 >> 16) & 0xFF) + wb * ((col1 >> 16) & 0xFF) + wc * ((col2 >> 16) & 0xFF);
                    double g = wa * ((col0 >> 8) & 0xFF) + wb * ((col1 >> 8) & 0xFF) + wc * ((col2 >> 8) & 0xFF);
                    double b = wa * (col0 & 0xFF) + wb * (col1 & 0xFF) + wc * (col2 & 0xFF);

                    Acc acc = cells.computeIfAbsent(pack(gx, gy, gz), key -> new Acc());
                    acc.r += r;
                    acc.g += g;
                    acc.b += b;
                    acc.weight += 1.0;
                }
            }
        }

        Map<Long, Integer> voxels = new HashMap<>(cells.size());
        for (Map.Entry<Long, Acc> entry : cells.entrySet()) {
            Acc acc = entry.getValue();
            int r = (int) Math.min(255, acc.r / acc.weight);
            int g = (int) Math.min(255, acc.g / acc.weight);
            int b = (int) Math.min(255, acc.b / acc.weight);
            voxels.put(entry.getKey(), (r << 16) | (g << 8) | b);
        }

        int maxX = 0, maxY = 0, maxZ = 0, minY = 0;
        for (long key : voxels.keySet()) {
            maxX = Math.max(maxX, unpackX(key));
            maxY = Math.max(maxY, unpackY(key));
            maxZ = Math.max(maxZ, unpackZ(key));
            minY = Math.min(minY, unpackY(key));
        }

        LOGGER.info("Voxelized collider mesh: {} triangles -> {} voxels in {}x{}x{} (ground at Y=0)",
                indices.length / 3, voxels.size(), maxX + 1, maxY - minY + 1, maxZ + 1);
        return new VoxelWorld(voxels, maxX + 1, maxY - minY + 1, maxZ + 1, minY, blocksPerUnit);
    }

    private static double sq(double x) {
        return x * x;
    }

    /** Clamps a per-axis stddev (in blocks) to a sane range. */
    private static double clampSigma(double sigma) {
        return Math.max(0.35, Math.min(4.0, sigma));
    }

    /** Footprint radius in cells for a given stddev: cover ~1.2 sigma, capped. */
    private static int footprintRadius(double sigma, int max) {
        return Math.min(max, (int) Math.ceil(sigma * 1.2 - 0.5));
    }

    /**
     * Fills empty cells that have >= solidifyNeighbors filled face-neighbors with the
     * average neighbor color. Closes pinholes in walls/floors without inflating edges.
     * @return number of cells added
     */
    private static int solidify(Map<Long, Integer> voxels, Options opts) {
        if (opts.solidifyNeighbors <= 0 || opts.solidifyNeighbors > 6 || voxels.isEmpty()) return 0;

        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int totalAdded = 0;

        for (int pass = 0; pass < opts.solidifyPasses; pass++) {
            // Candidate empty cells = face-neighbors of filled cells.
            Map<Long, Integer> toAdd = new HashMap<>();
            java.util.Set<Long> candidates = new java.util.HashSet<>();
            for (long key : voxels.keySet()) {
                int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
                for (int[] o : offsets) {
                    int nx = x + o[0], nz = z + o[2];
                    if (nx < 0 || nz < 0) continue;
                    long nk = pack(nx, y + o[1], nz);
                    if (!voxels.containsKey(nk)) candidates.add(nk);
                }
            }

            for (long key : candidates) {
                int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
                int count = 0;
                long r = 0, g = 0, b = 0;
                for (int[] o : offsets) {
                    Integer c = voxels.get(pack(x + o[0], y + o[1], z + o[2]));
                    if (c != null) {
                        count++;
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                    }
                }
                if (count >= opts.solidifyNeighbors) {
                    toAdd.put(key, (int) ((r / count) << 16 | (g / count) << 8 | (b / count)));
                }
            }

            voxels.putAll(toAdd);
            totalAdded += toAdd.size();
            if (toAdd.isEmpty()) break;
        }
        return totalAdded;
    }

    private static double[] percentileBounds(double[] values, double clip) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int lo = (int) Math.floor(clip * (sorted.length - 1));
        int hi = (int) Math.ceil((1.0 - clip) * (sorted.length - 1));
        lo = Math.max(0, Math.min(sorted.length - 1, lo));
        hi = Math.max(0, Math.min(sorted.length - 1, hi));
        return new double[]{sorted[lo], sorted[hi]};
    }
}
