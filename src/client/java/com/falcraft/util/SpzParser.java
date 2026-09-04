package com.falcraft.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

/**
 * Parses the Niantic ".spz" compressed Gaussian-splat format (as produced by
 * World Labs Marble worlds). Only legacy versions 1-3 (whole file gzipped,
 * 16-byte header) are supported, which is what Marble currently serves.
 *
 * Layout after gunzip (little-endian):
 *   header (16 bytes): uint32 magic "NGSP" (0x5053474E), uint32 version,
 *     uint32 numPoints, uint8 shDegree, uint8 fractionalBits, uint8 flags, uint8 reserved
 *   positions: 3 x 24-bit signed fixed point per Gaussian (fractionalBits fractional bits)
 *   alphas:    1 byte per Gaussian (sigmoid-space opacity * 255)
 *   colors:    3 bytes per Gaussian (SH DC coefficient: (b/255 - 0.5) / 0.15)
 *   scales:    3 bytes per Gaussian (log-space: b/16 - 10)
 *   rotations: version 2 = 3 bytes (quaternion xyz), version 3 = 4 bytes (smallest-three)
 *   sh:        numPoints * shDim * 3 bytes (skipped)
 *
 * Emits the same {@link SplatParser.SplatData} record as the .splat path so the
 * voxelizers are format-agnostic.
 */
public class SpzParser {
    private static final Logger LOGGER = LoggerFactory.getLogger("SpzParser");

    private static final int MAGIC = 0x5053474E; // "NGSP"
    private static final float SH_C0 = 0.28209479f; // SH degree-0 basis constant
    private static final float COLOR_SCALE = 0.15f;

    /**
     * Parses raw .spz file bytes (gzipped) into a {@link SplatParser.SplatData}.
     */
    public static SplatParser.SplatData parse(byte[] gzipped) throws IOException {
        byte[] data = gunzip(gzipped);
        if (data.length < 16) {
            throw new IOException("SPZ data too small (" + data.length + " bytes after gunzip)");
        }

        int magic = readUint32(data, 0);
        int version = readUint32(data, 4);
        int count = readUint32(data, 8);
        int shDegree = data[12] & 0xFF;
        int fractionalBits = data[13] & 0xFF;

        if (magic != MAGIC) {
            throw new IOException("Invalid SPZ magic: 0x" + Integer.toHexString(magic));
        }
        if (version < 1 || version > 3) {
            throw new IOException("Unsupported SPZ version: " + version);
        }
        if (count < 0 || count > 50_000_000) {
            throw new IOException("Implausible SPZ point count: " + count);
        }

        int posOff = 16;
        int alphaOff = posOff + count * 9;
        int colorOff = alphaOff + count;
        int scaleOff = colorOff + count * 3;
        // rotations + sh follow; not needed for voxelization

        int needed = scaleOff + count * 3;
        if (data.length < needed) {
            throw new IOException("SPZ data truncated: need " + needed + " bytes, have " + data.length);
        }

        float posScale = 1.0f / (1 << fractionalBits);

        float[] positions = new float[count * 3];
        int[] colors = new int[count];
        float[] opacities = new float[count];
        float[] scales = new float[count * 3];

        for (int i = 0; i < count; i++) {
            for (int c = 0; c < 3; c++) {
                int b = posOff + i * 9 + c * 3;
                int fixed = (data[b] & 0xFF) | ((data[b + 1] & 0xFF) << 8) | ((data[b + 2] & 0xFF) << 16);
                if ((fixed & 0x800000) != 0) fixed |= 0xFF000000; // sign-extend 24 -> 32 bit
                positions[i * 3 + c] = fixed * posScale;
            }

            opacities[i] = (data[alphaOff + i] & 0xFF) / 255f;

            int r = decodeColor(data[colorOff + i * 3] & 0xFF);
            int g = decodeColor(data[colorOff + i * 3 + 1] & 0xFF);
            int b = decodeColor(data[colorOff + i * 3 + 2] & 0xFF);
            colors[i] = (r << 16) | (g << 8) | b;

            for (int c = 0; c < 3; c++) {
                scales[i * 3 + c] = (float) Math.exp((data[scaleOff + i * 3 + c] & 0xFF) / 16.0f - 10.0f);
            }
        }

        LOGGER.info("Parsed SPZ: {} Gaussians (version {}, shDegree {}, fractionalBits {})",
                count, version, shDegree, fractionalBits);
        return new SplatParser.SplatData(positions, colors, opacities, scales, count);
    }

    /** Decodes a stored color byte (SH DC coefficient) to a display-space 0-255 channel. */
    private static int decodeColor(int stored) {
        float coeff = (stored / 255f - 0.5f) / COLOR_SCALE;
        int v = Math.round((0.5f + SH_C0 * coeff) * 255f);
        return Math.max(0, Math.min(255, v));
    }

    private static int readUint32(byte[] data, int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16) | ((data[off + 3] & 0xFF) << 24);
    }

    private static byte[] gunzip(byte[] gzipped) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(gzipped.length * 4);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
