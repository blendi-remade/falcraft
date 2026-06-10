package com.falcraft.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Parses the binary ".splat" Gaussian-splat format (as produced by tripo3d/triposplat
 * with output_format = "splat").
 *
 * Layout: a flat array of 32-byte records, one per Gaussian, little-endian:
 *   bytes  0..11  position  : 3 x float32 (x, y, z)
 *   bytes 12..23  scale     : 3 x float32 (sx, sy, sz)   [parsed, currently unused]
 *   bytes 24..27  color     : 4 x uint8   (r, g, b, a)   <- already decoded from SH + sigmoid
 *   bytes 28..31  rotation  : 4 x uint8   (quaternion, (q*128)+128) [unused for now]
 *
 * Unlike the .ply format, color and opacity are pre-decoded to bytes here, so no
 * spherical-harmonics / sigmoid / exp math is needed.
 */
public class SplatParser {
    private static final Logger LOGGER = LoggerFactory.getLogger("SplatParser");

    private static final int RECORD_SIZE = 32;

    /**
     * Parsed Gaussian splat cloud.
     *
     * @param positions 3 floats per Gaussian (x, y, z), length = 3 * count
     * @param colors    one packed 0xRRGGBB int per Gaussian, length = count
     * @param opacities  alpha in 0..1 per Gaussian, length = count
     * @param scales    3 floats per Gaussian (sx, sy, sz), length = 3 * count (kept for a
     *                  possible future ellipsoid-density voxelizer; unused by the centroid path)
     * @param count     number of Gaussians
     */
    public record SplatData(float[] positions, int[] colors, float[] opacities, float[] scales, int count) {}

    /**
     * Parses raw .splat bytes into a {@link SplatData}.
     *
     * @param data the raw .splat file contents
     * @return the parsed Gaussian cloud
     */
    public static SplatData parse(byte[] data) {
        if (data == null || data.length < RECORD_SIZE) {
            throw new IllegalArgumentException("Splat data is empty or too small (" +
                    (data == null ? 0 : data.length) + " bytes)");
        }

        if (data.length % RECORD_SIZE != 0) {
            LOGGER.warn("Splat data length {} is not a multiple of {}; trailing bytes ignored",
                    data.length, RECORD_SIZE);
        }

        int count = data.length / RECORD_SIZE;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        float[] positions = new float[count * 3];
        float[] scales = new float[count * 3];
        int[] colors = new int[count];
        float[] opacities = new float[count];

        for (int i = 0; i < count; i++) {
            int base = i * RECORD_SIZE;

            positions[i * 3]     = buf.getFloat(base);
            positions[i * 3 + 1] = buf.getFloat(base + 4);
            positions[i * 3 + 2] = buf.getFloat(base + 8);

            scales[i * 3]     = buf.getFloat(base + 12);
            scales[i * 3 + 1] = buf.getFloat(base + 16);
            scales[i * 3 + 2] = buf.getFloat(base + 20);

            int r = buf.get(base + 24) & 0xFF;
            int g = buf.get(base + 25) & 0xFF;
            int b = buf.get(base + 26) & 0xFF;
            int a = buf.get(base + 27) & 0xFF;

            colors[i] = (r << 16) | (g << 8) | b;
            opacities[i] = a / 255f;

            // bytes 28..31 (rotation quaternion) intentionally skipped for the centroid voxelizer
        }

        LOGGER.info("Parsed splat: {} Gaussians", count);
        return new SplatData(positions, colors, opacities, scales, count);
    }
}
