package com.falcraft.util;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts 3D mesh data to a voxel grid using surface super-sampling.
 *
 * For each triangle we pick a subdivision count so that barycentric samples land
 * roughly half a voxel apart, then drop each sample into its grid cell and average
 * the sampled color per cell. This is far simpler and more robust than exact
 * triangle/voxel clipping, handles thin/degenerate triangles gracefully, and
 * produces smoother per-voxel colors (averaged rather than winner-takes-all).
 *
 * Two color paths:
 * - Textured meshes (Hunyuan / Meshy, used by /fal craft and legacy): sample the
 *   texture at the interpolated UV.
 * - Untextured meshes with vertex colors (SAM-3D, used by /fal generate): blend the
 *   three vertex colors barycentrically, then boost saturation (SAM-3D is pastel).
 *
 * Adapted from the occ-voxelizer super-sampling approach.
 */
public class Voxelizer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Voxelizer");

    // Default gray for meshes with neither texture nor vertex colors.
    private static final int DEFAULT_COLOR = 0xAAAAAA;

    // Saturation boost factor for SAM-3D vertex colors (they tend to be desaturated).
    // 1.0 = no change. Only applied to the vertex-color path, not textured meshes.
    private static final float SATURATION_BOOST = 1.0f;

    /**
     * Represents a voxelized 3D model
     */
    public record VoxelGrid(Map<BlockPos, Integer> voxels, int size) {}

    /**
     * Per-cell color accumulator (running sum + count for averaging).
     */
    private static final class Acc {
        long r, g, b;
        int count;
    }

    public static VoxelGrid voxelize(GLBParser.MeshData mesh, int resolution) {
        return voxelize(mesh, resolution, null);
    }

    public static VoxelGrid voxelize(GLBParser.MeshData mesh, int resolution, TextureSampler textureSampler) {
        float[] vertices = mesh.vertices();
        int[] indices = mesh.indices();
        float[] uvs = mesh.uvs();
        int[] colors = mesh.colors();

        boolean hasUVs = uvs != null && uvs.length > 0;
        boolean hasTexture = textureSampler != null && hasUVs;
        boolean hasColors = colors != null && colors.length > 0;
        boolean useVertexColors = !hasTexture && hasColors;

        if (vertices.length == 0 || indices.length < 3) {
            return new VoxelGrid(new HashMap<>(), resolution);
        }

        // Bounding box
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < vertices.length; i += 3) {
            minX = Math.min(minX, vertices[i]);     maxX = Math.max(maxX, vertices[i]);
            minY = Math.min(minY, vertices[i + 1]); maxY = Math.max(maxY, vertices[i + 1]);
            minZ = Math.min(minZ, vertices[i + 2]); maxZ = Math.max(maxZ, vertices[i + 2]);
        }

        double maxDim = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        if (maxDim <= 0) {
            return new VoxelGrid(new HashMap<>(), resolution);
        }

        // Scale so the longest axis spans `resolution` cells; grid space == voxel units.
        double scale = resolution / maxDim;
        int maxIndex = resolution - 1;

        Map<BlockPos, Acc> candidates = new HashMap<>();

        for (int i = 0; i + 2 < indices.length; i += 3) {
            int idx0 = indices[i], idx1 = indices[i + 1], idx2 = indices[i + 2];

            // Vertices in grid space (voxel units, origin at bbox min).
            double ax = (vertices[idx0 * 3] - minX) * scale;
            double ay = (vertices[idx0 * 3 + 1] - minY) * scale;
            double az = (vertices[idx0 * 3 + 2] - minZ) * scale;
            double bx = (vertices[idx1 * 3] - minX) * scale;
            double by = (vertices[idx1 * 3 + 1] - minY) * scale;
            double bz = (vertices[idx1 * 3 + 2] - minZ) * scale;
            double cx = (vertices[idx2 * 3] - minX) * scale;
            double cy = (vertices[idx2 * 3 + 1] - minY) * scale;
            double cz = (vertices[idx2 * 3 + 2] - minZ) * scale;

            // Subdivision count: samples land ~half a voxel apart, capped for safety.
            double e1 = Math.sqrt(sq(bx - ax) + sq(by - ay) + sq(bz - az));
            double e2 = Math.sqrt(sq(cx - bx) + sq(cy - by) + sq(cz - bz));
            double e3 = Math.sqrt(sq(ax - cx) + sq(ay - cy) + sq(az - cz));
            double maxEdge = Math.max(e1, Math.max(e2, e3));
            int n = Math.min(64, Math.max(1, (int) Math.ceil(maxEdge * 2)));

            // UVs
            float ua = 0, va = 0, ub = 0, vb = 0, uc = 0, vc = 0;
            if (hasUVs) {
                ua = uvs[idx0 * 2]; va = uvs[idx0 * 2 + 1];
                ub = uvs[idx1 * 2]; vb = uvs[idx1 * 2 + 1];
                uc = uvs[idx2 * 2]; vc = uvs[idx2 * 2 + 1];
            }

            // Vertex colors
            int col0 = hasColors ? colors[idx0] : DEFAULT_COLOR;
            int col1 = hasColors ? colors[idx1] : DEFAULT_COLOR;
            int col2 = hasColors ? colors[idx2] : DEFAULT_COLOR;

            for (int p = 0; p <= n; p++) {
                for (int q = 0; q <= n - p; q++) {
                    double wa = (double) p / n;
                    double wb = (double) q / n;
                    double wc = 1.0 - wa - wb;

                    int gx = clamp((int) Math.floor(wa * ax + wb * bx + wc * cx), maxIndex);
                    int gy = clamp((int) Math.floor(wa * ay + wb * by + wc * cy), maxIndex);
                    int gz = clamp((int) Math.floor(wa * az + wb * bz + wc * cz), maxIndex);

                    int r, g, bl;
                    if (hasTexture) {
                        float su = (float) (wa * ua + wb * ub + wc * uc);
                        float sv = (float) (wa * va + wb * vb + wc * vc);
                        int rgb = textureSampler.sample(su, sv);
                        r = (rgb >> 16) & 0xFF; g = (rgb >> 8) & 0xFF; bl = rgb & 0xFF;
                    } else if (useVertexColors) {
                        r = (int) (wa * ((col0 >> 16) & 0xFF) + wb * ((col1 >> 16) & 0xFF) + wc * ((col2 >> 16) & 0xFF));
                        g = (int) (wa * ((col0 >> 8) & 0xFF) + wb * ((col1 >> 8) & 0xFF) + wc * ((col2 >> 8) & 0xFF));
                        bl = (int) (wa * (col0 & 0xFF) + wb * (col1 & 0xFF) + wc * (col2 & 0xFF));
                    } else {
                        r = (DEFAULT_COLOR >> 16) & 0xFF; g = (DEFAULT_COLOR >> 8) & 0xFF; bl = DEFAULT_COLOR & 0xFF;
                    }

                    BlockPos pos = new BlockPos(gx, gy, gz);
                    Acc acc = candidates.get(pos);
                    if (acc == null) {
                        acc = new Acc();
                        candidates.put(pos, acc);
                    }
                    acc.r += r; acc.g += g; acc.b += bl; acc.count++;
                }
            }
        }

        // Average each cell, applying saturation boost only on the vertex-color path.
        Map<BlockPos, Integer> voxels = new HashMap<>(candidates.size());
        for (Map.Entry<BlockPos, Acc> entry : candidates.entrySet()) {
            Acc acc = entry.getValue();
            int r = (int) (acc.r / acc.count);
            int g = (int) (acc.g / acc.count);
            int b = (int) (acc.b / acc.count);
            int color = (r << 16) | (g << 8) | b;
            if (useVertexColors && SATURATION_BOOST != 1.0f) {
                color = boostSaturation(color, SATURATION_BOOST);
            }
            voxels.put(entry.getKey(), color);
        }

        LOGGER.info("Voxelized mesh: {} triangles -> {} voxels (resolution {})",
                indices.length / 3, voxels.size(), resolution);

        return new VoxelGrid(voxels, resolution);
    }

    // ==================== GAUSSIAN SPLAT VOXELIZATION ====================

    // Gaussians below this opacity are treated as haze/filler and dropped.
    private static final float SPLAT_OPACITY_THRESHOLD = 0.15f;
    // Percentile clip per axis for robust bounds (ignores stray "floater" Gaussians).
    private static final double SPLAT_PERCENTILE_LOW = 0.01;
    private static final double SPLAT_PERCENTILE_HIGH = 0.99;

    /**
     * Voxelizes a Gaussian splat using the centroid approach: filter out low-opacity
     * Gaussians, compute robust (percentile-clipped) bounds to ignore floaters, scale the
     * longest axis to {@code resolution}, drop each Gaussian's center into its cell, and
     * average the per-cell color.
     *
     * Color comes straight from each Gaussian (no texture), so this is the splat analogue
     * of the mesh vertex-color path. Saturation boost is applied since TripoSplat colors
     * can read flat once averaged.
     *
     * @param splat      parsed Gaussian cloud
     * @param resolution number of voxels spanning the longest axis
     */
    public static VoxelGrid voxelizeSplat(SplatParser.SplatData splat, int resolution) {
        int n = splat.count();
        if (n == 0) {
            return new VoxelGrid(new HashMap<>(), resolution);
        }

        float[] pos = splat.positions();
        float[] op = splat.opacities();

        // 1) Opacity filter. Fall back to all Gaussians if the threshold is too aggressive.
        int[] kept = new int[n];
        int keptCount = 0;
        for (int i = 0; i < n; i++) {
            if (op[i] >= SPLAT_OPACITY_THRESHOLD) {
                kept[keptCount++] = i;
            }
        }
        if (keptCount < n / 20) { // fewer than 5% survived - threshold too strict, keep everything
            for (int i = 0; i < n; i++) kept[i] = i;
            keptCount = n;
            LOGGER.warn("Opacity filter kept <5% of Gaussians; using full cloud instead");
        }

        // 2) Robust per-axis bounds via percentile clip (drops floaters).
        double[] xs = new double[keptCount];
        double[] ys = new double[keptCount];
        double[] zs = new double[keptCount];
        for (int k = 0; k < keptCount; k++) {
            int i = kept[k];
            xs[k] = pos[i * 3];
            ys[k] = pos[i * 3 + 1];
            zs[k] = pos[i * 3 + 2];
        }
        double[] xb = percentileBounds(xs);
        double[] yb = percentileBounds(ys);
        double[] zb = percentileBounds(zs);

        double minX = xb[0], maxX = xb[1];
        double minY = yb[0], maxY = yb[1];
        double minZ = zb[0], maxZ = zb[1];

        double maxDim = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        if (maxDim <= 0) {
            return new VoxelGrid(new HashMap<>(), resolution);
        }

        double scale = resolution / maxDim;
        int maxIndex = resolution - 1;

        // 3) Drop centroids into cells, averaging color.
        Map<BlockPos, Acc> candidates = new HashMap<>();
        int[] colors = splat.colors();
        for (int k = 0; k < keptCount; k++) {
            int i = kept[k];
            int gx = clamp((int) Math.floor((pos[i * 3] - minX) * scale), maxIndex);
            int gy = clamp((int) Math.floor((pos[i * 3 + 1] - minY) * scale), maxIndex);
            int gz = clamp((int) Math.floor((pos[i * 3 + 2] - minZ) * scale), maxIndex);

            int rgb = colors[i];
            BlockPos p = new BlockPos(gx, gy, gz);
            Acc acc = candidates.get(p);
            if (acc == null) {
                acc = new Acc();
                candidates.put(p, acc);
            }
            acc.r += (rgb >> 16) & 0xFF;
            acc.g += (rgb >> 8) & 0xFF;
            acc.b += rgb & 0xFF;
            acc.count++;
        }

        Map<BlockPos, Integer> voxels = new HashMap<>(candidates.size());
        for (Map.Entry<BlockPos, Acc> entry : candidates.entrySet()) {
            Acc acc = entry.getValue();
            int color = (((int) (acc.r / acc.count)) << 16)
                    | (((int) (acc.g / acc.count)) << 8)
                    | ((int) (acc.b / acc.count));
            if (SATURATION_BOOST != 1.0f) {
                color = boostSaturation(color, SATURATION_BOOST);
            }
            voxels.put(entry.getKey(), color);
        }

        LOGGER.info("Voxelized splat: {} Gaussians ({} after opacity filter) -> {} voxels (resolution {})",
                n, keptCount, voxels.size(), resolution);

        return new VoxelGrid(voxels, resolution);
    }

    /**
     * Returns [low, high] bounds for a coordinate array using percentile clipping,
     * which discards a small fraction of outliers (floaters) on each end.
     */
    private static double[] percentileBounds(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int lo = (int) Math.floor(SPLAT_PERCENTILE_LOW * (sorted.length - 1));
        int hi = (int) Math.ceil(SPLAT_PERCENTILE_HIGH * (sorted.length - 1));
        lo = Math.max(0, Math.min(sorted.length - 1, lo));
        hi = Math.max(0, Math.min(sorted.length - 1, hi));
        return new double[]{sorted[lo], sorted[hi]};
    }

    private static double sq(double x) {
        return x * x;
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : (v > max ? max : v);
    }

    /**
     * Boosts the saturation of an RGB color (SAM-3D colors tend to be pastel).
     *
     * @param rgb    The input color (packed RGB)
     * @param factor Saturation multiplier (1.0 = no change)
     * @return The saturated color (packed RGB)
     */
    private static int boostSaturation(int rgb, float factor) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f;

        if (max == min) {
            return rgb; // Achromatic - nothing to boost
        }

        float d = max - min;
        float s = l > 0.5f ? d / (2f - max - min) : d / (max + min);

        float h;
        if (max == r) {
            h = ((g - b) / d + (g < b ? 6f : 0f)) / 6f;
        } else if (max == g) {
            h = ((b - r) / d + 2f) / 6f;
        } else {
            h = ((r - g) / d + 4f) / 6f;
        }

        s = Math.min(1.0f, s * factor);

        float r2, g2, b2;
        if (s == 0) {
            r2 = g2 = b2 = l;
        } else {
            float qv = l < 0.5f ? l * (1f + s) : l + s - l * s;
            float pv = 2f * l - qv;
            r2 = hueToRgb(pv, qv, h + 1f / 3f);
            g2 = hueToRgb(pv, qv, h);
            b2 = hueToRgb(pv, qv, h - 1f / 3f);
        }

        int ri = Math.min(255, Math.max(0, Math.round(r2 * 255)));
        int gi = Math.min(255, Math.max(0, Math.round(g2 * 255)));
        int bi = Math.min(255, Math.max(0, Math.round(b2 * 255)));
        return (ri << 16) | (gi << 8) | bi;
    }

    private static float hueToRgb(float p, float q, float t) {
        if (t < 0f) t += 1f;
        if (t > 1f) t -= 1f;
        if (t < 1f / 6f) return p + (q - p) * 6f * t;
        if (t < 1f / 2f) return q;
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f;
        return p;
    }
}
