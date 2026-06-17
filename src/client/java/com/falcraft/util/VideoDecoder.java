package com.falcraft.util;

import com.mojang.blaze3d.platform.NativeImage;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.scale.AWTUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decodes an mp4 (H.264) into a sequence of {@link NativeImage} frames using JCodec
 * (pure Java, no native libs). Runs on a worker thread.
 *
 * Frames are pulled WITH their presentation timestamps and sorted by timestamp before
 * use: JCodec returns frames in decode order, which for B-frame streams differs from
 * display order — without this sort, playback stutters and jumps backward a frame.
 * Frames are downscaled and the count is capped to keep playback memory bounded.
 */
public class VideoDecoder {
    private static final Logger LOGGER = LoggerFactory.getLogger("VideoDecoder");

    private static final int MAX_FRAMES = 120;     // smooth for short clips; bounds memory
    private static final int HARD_DECODE_CAP = 900; // safety against unexpectedly long inputs
    private static final int MAX_DIM = 512;
    private static final double DEFAULT_FPS = 12.0;

    /** Decoded frames (in display order) + the effective playback fps. */
    public record DecodedVideo(List<NativeImage> frames, double fps) {}

    private record TimedFrame(double ts, NativeImage img) {}

    public static DecodedVideo decode(File mp4) {
        List<TimedFrame> all = new ArrayList<>();
        SeekableByteChannel channel = null;
        try {
            channel = NIOUtils.readableChannel(mp4);
            FrameGrab grab = FrameGrab.createFrameGrab(channel);

            // Decode every frame, keeping its presentation timestamp.
            PictureWithMetadata pm;
            int safety = 0;
            while ((pm = grab.getNativeFrameWithMetadata()) != null && safety < HARD_DECODE_CAP) {
                safety++;
                NativeImage img = toNativeImage(downscale(AWTUtil.toBufferedImage(pm.getPicture())));
                all.add(new TimedFrame(pm.getTimestamp(), img));
            }

            if (all.isEmpty()) {
                return new DecodedVideo(new ArrayList<>(), DEFAULT_FPS);
            }

            // Restore display order.
            all.sort(Comparator.comparingDouble(TimedFrame::ts));

            // Sample down to MAX_FRAMES, spread evenly across the (now ordered) clip.
            int step = (all.size() > MAX_FRAMES) ? (int) Math.ceil((double) all.size() / MAX_FRAMES) : 1;
            List<NativeImage> kept = new ArrayList<>();
            List<Double> keptTs = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                if (i % step == 0 && kept.size() < MAX_FRAMES) {
                    kept.add(all.get(i).img());
                    keptTs.add(all.get(i).ts());
                } else {
                    all.get(i).img().close();
                }
            }

            double fps = DEFAULT_FPS;
            if (kept.size() >= 2) {
                double span = keptTs.get(keptTs.size() - 1) - keptTs.get(0);
                if (span > 0) fps = (kept.size() - 1) / span;
            }
            fps = Math.max(1.0, Math.min(30.0, fps));

            LOGGER.info("Decoded {} frames (of {} decoded, ~{} fps) from {}",
                    kept.size(), all.size(), String.format("%.1f", fps), mp4.getName());
            return new DecodedVideo(kept, fps);

        } catch (Throwable e) {
            LOGGER.error("Failed to decode video {}", mp4.getName(), e);
            for (TimedFrame f : all) f.img().close();
            return new DecodedVideo(new ArrayList<>(), DEFAULT_FPS);
        } finally {
            if (channel != null) {
                try { channel.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static BufferedImage downscale(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= MAX_DIM && h <= MAX_DIM) return src;

        double s = (double) MAX_DIM / Math.max(w, h);
        int nw = Math.max(1, (int) Math.round(w * s));
        int nh = Math.max(1, (int) Math.round(h * s));
        BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return scaled;
    }

    private static NativeImage toNativeImage(BufferedImage bi) {
        int w = bi.getWidth();
        int h = bi.getHeight();
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = bi.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int gg = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (gg << 8) | r);
            }
        }
        return img;
    }
}
