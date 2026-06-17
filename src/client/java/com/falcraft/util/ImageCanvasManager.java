package com.falcraft.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages AI-generated image "canvases" displayed in the world as full-fidelity
 * textured quads, driven by an item lifecycle (see {@link MapImageFactory}).
 *
 * Each image is keyed by a falcraft id and stored as a full-res PNG on disk. A tagged
 * {@code filled_map} item carries that id; holding the item shows a placement preview
 * (full-res quad), right-click places it, and breaking a placed canvas returns the item.
 *
 * Textures are cached by id (registered lazily, released only on world unload) so the
 * same image can be previewed, placed, and re-placed without re-registering.
 * Client-side / single-player oriented.
 */
public class ImageCanvasManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ImageCanvasManager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Direction[] HORIZONTALS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    private static final int DEFAULT_WIDTH_BLOCKS = 4;
    private static final int MIN_WIDTH_BLOCKS = 1;
    private static final int MAX_WIDTH_BLOCKS = 32;
    private static final double WALL_OFFSET = 0.02;

    public record CanvasTransform(Vec3 center, Direction facing, double width, double height) {}

    public static final class ImageCanvas {
        public final String id;
        public final String dim;
        public final Vec3 center;
        public final Direction facing;
        public final double width;
        public final double height;
        public final ResourceLocation texture;

        ImageCanvas(String id, String dim, Vec3 center, Direction facing,
                    double width, double height, ResourceLocation texture) {
            this.id = id;
            this.dim = dim;
            this.center = center;
            this.facing = facing;
            this.width = width;
            this.height = height;
            this.texture = texture;
        }

        public CanvasTransform toTransform() {
            return new CanvasTransform(center, facing, width, height);
        }
    }

    private static final class CanvasRecord {
        String id, dim, facing;
        double x, y, z, width, height;
    }

    private static final List<ImageCanvas> placed = new ArrayList<>();

    // Texture + aspect caches keyed by falcraft id (registered lazily from disk).
    private static final Map<String, ResourceLocation> textureCache = new HashMap<>();
    private static final Map<String, Double> aspectCache = new HashMap<>();
    private static final Map<String, VideoPlayback> videos = new HashMap<>();
    private static final java.util.Set<String> decoding = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Playback state for an animated (video) canvas: frames cycled into one DynamicTexture. */
    private static final class VideoPlayback {
        final DynamicTexture tex;
        final List<NativeImage> frames;
        final double fps;
        long startNanos = 0;
        int lastIdx = -1;
        VideoPlayback(DynamicTexture tex, List<NativeImage> frames, double fps) {
            this.tex = tex;
            this.frames = frames;
            this.fps = fps;
        }
    }

    // Preview state (driven by the currently-held tagged map item)
    private static boolean previewActive = false;
    private static String pendingId = null;
    private static ResourceLocation pendingTexture = null;
    private static double aspect = 1.0;
    private static int widthBlocks = DEFAULT_WIDTH_BLOCKS;
    private static int forcedFacingIndex = -1;
    private static boolean snapEnabled = true;

    private static String loadedDim = null;

    // ==================== IMAGE STORAGE ====================

    public static String newId() {
        return String.valueOf(System.currentTimeMillis());
    }

    public static void saveImageBytes(String id, byte[] png) {
        try {
            Path dir = canvasDir();
            Files.createDirectories(dir);
            Files.write(dir.resolve(id + ".png"), png);
        } catch (Exception e) {
            LOGGER.error("Failed to save image bytes for {}", id, e);
        }
    }

    public static byte[] readImageBytes(String id) {
        try {
            Path png = canvasDir().resolve(id + ".png");
            return Files.exists(png) ? Files.readAllBytes(png) : null;
        } catch (Exception e) {
            LOGGER.error("Failed to read image bytes for {}", id, e);
            return null;
        }
    }

    private static Path videoFile(String id) {
        return canvasDir().resolve(id + ".mp4");
    }

    public static void saveVideoBytes(String id, byte[] mp4) {
        try {
            Files.createDirectories(canvasDir());
            Files.write(videoFile(id), mp4);
        } catch (Exception e) {
            LOGGER.error("Failed to save video bytes for {}", id, e);
        }
    }

    /** Decodes a previously-saved mp4 (off-thread). */
    public static VideoDecoder.DecodedVideo decodeSavedVideo(String id) {
        return VideoDecoder.decode(videoFile(id).toFile());
    }

    // ==================== VIDEO PLAYBACK ====================

    /**
     * Registers decoded video frames as an animated texture (render thread).
     * The texture is one DynamicTexture whose pixels are swapped each displayed frame.
     */
    public static void registerVideo(String id, List<NativeImage> frames, double fps) {
        if (frames.isEmpty() || textureCache.containsKey(id)) return;
        NativeImage first = frames.get(0);
        aspectCache.put(id, first.getWidth() > 0 ? (double) first.getHeight() / first.getWidth() : 1.0);

        DynamicTexture tex = new DynamicTexture(cloneImage(first));
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("falcraft", "canvas/" + id);
        Minecraft.getInstance().getTextureManager().register(loc, tex);
        textureCache.put(id, loc);
        videos.put(id, new VideoPlayback(tex, frames, fps));
        LOGGER.info("Registered video {} ({} frames, {} fps)", id, frames.size(), String.format("%.1f", fps));
    }

    /** Decodes a persisted mp4 off-thread and registers it once ready. */
    private static void decodeVideoAsync(String id) {
        if (textureCache.containsKey(id) || !decoding.add(id)) return;
        new Thread(() -> {
            try {
                VideoDecoder.DecodedVideo dv = VideoDecoder.decode(videoFile(id).toFile());
                if (dv.frames().isEmpty()) {
                    decoding.remove(id);
                    return;
                }
                Minecraft.getInstance().execute(() -> {
                    registerVideo(id, dv.frames(), dv.fps());
                    decoding.remove(id);
                });
            } catch (Exception e) {
                LOGGER.error("Async video decode failed for {}", id, e);
                decoding.remove(id);
            }
        }, "fal-VideoDecode-" + id).start();
    }

    /** Advances all playing videos, uploading the current frame. Called each client tick. */
    public static void tickVideos() {
        if (videos.isEmpty()) return;
        long now = System.nanoTime();
        for (VideoPlayback vp : videos.values()) {
            int n = vp.frames.size();
            if (n <= 1) continue;
            if (vp.startNanos == 0) vp.startNanos = now;
            double elapsed = (now - vp.startNanos) / 1_000_000_000.0;
            int idx = (int) (elapsed * vp.fps) % n;
            if (idx != vp.lastIdx && vp.tex.getPixels() != null) {
                vp.tex.getPixels().copyFrom(vp.frames.get(idx));
                vp.tex.upload();
                vp.lastIdx = idx;
            }
        }
    }

    private static NativeImage cloneImage(NativeImage src) {
        NativeImage copy = new NativeImage(src.getWidth(), src.getHeight(), false);
        copy.copyFrom(src);
        return copy;
    }

    // ==================== TEXTURE CACHE ====================

    /** Public accessor: returns the registered texture for an id (loads from disk if needed), or null. */
    public static ResourceLocation getOrLoadTexture(String id) {
        return getTexture(id);
    }

    /** Returns the registered texture for an id, loading + registering it from disk if needed. */
    private static ResourceLocation getTexture(String id) {
        ResourceLocation cached = textureCache.get(id);
        if (cached != null) return cached;

        // Video id: decode the mp4 off-thread and register lazily (returns null until ready).
        if (Files.exists(videoFile(id))) {
            decodeVideoAsync(id);
            return null;
        }

        byte[] bytes = readImageBytes(id);
        if (bytes == null) return null;
        try {
            NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes));
            aspectCache.put(id, img.getWidth() > 0 ? (double) img.getHeight() / img.getWidth() : 1.0);
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("falcraft", "canvas/" + id);
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
            textureCache.put(id, loc);
            return loc;
        } catch (Exception e) {
            LOGGER.error("Failed to register texture for {}", id, e);
            return null;
        }
    }

    // ==================== ITEM-DRIVEN PREVIEW ====================

    /**
     * Ensures a placement preview is active for the given id (called while the player
     * holds the matching tagged map). No-op if already previewing that id.
     */
    public static void startPreviewForId(String id) {
        if (previewActive && id.equals(pendingId)) return;

        ResourceLocation tex = getTexture(id);
        if (tex == null) {
            LOGGER.warn("No image on disk for held canvas id {}", id);
            return;
        }
        pendingId = id;
        pendingTexture = tex;
        aspect = aspectCache.getOrDefault(id, 1.0);
        widthBlocks = DEFAULT_WIDTH_BLOCKS;
        forcedFacingIndex = -1;
        previewActive = true;
    }

    public static boolean isPreviewActive() {
        return previewActive;
    }

    public static ResourceLocation getPreviewTexture() {
        return pendingTexture;
    }

    public static void cancelPreview() {
        previewActive = false;
        pendingId = null;
        pendingTexture = null;
    }

    public static CanvasTransform computeTargetTransform(LocalPlayer player) {
        double width = widthBlocks;
        double height = width * aspect;

        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(200.0));
        ClipContext ctx = new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
        BlockHitResult hit = player.level().clip(ctx);

        Direction facing;
        Vec3 center;
        if (hit.getType() == HitResult.Type.BLOCK) {
            Direction face = hit.getDirection();
            facing = (forcedFacingIndex >= 0)
                    ? HORIZONTALS[forcedFacingIndex]
                    : (face.getAxis().isHorizontal() ? face : nearestHorizontalTowardPlayer(look));
            center = hit.getLocation();
        } else {
            facing = (forcedFacingIndex >= 0) ? HORIZONTALS[forcedFacingIndex] : nearestHorizontalTowardPlayer(look);
            center = eye.add(look.scale(Math.max(width, 4.0)));
        }

        Vec3 normal = Vec3.atLowerCornerOf(facing.getNormal());

        if (snapEnabled) {
            double x = center.x, y = snapCenter(center.y, height), z = center.z;
            if (Math.abs(normal.x) > 0.5) {
                z = snapCenter(center.z, width);
            } else {
                x = snapCenter(center.x, width);
            }
            center = new Vec3(x, y, z);
        }

        center = center.add(normal.scale(WALL_OFFSET));
        return new CanvasTransform(center, facing, width, height);
    }

    private static double snapCenter(double c, double length) {
        return Math.round(c - length / 2.0) + length / 2.0;
    }

    private static Direction nearestHorizontalTowardPlayer(Vec3 look) {
        double dx = -look.x;
        double dz = -look.z;
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    public static void rotateFacing() {
        forcedFacingIndex = (forcedFacingIndex < 0) ? 0 : (forcedFacingIndex + 1) % 4;
    }

    public static void grow() {
        widthBlocks = Math.min(MAX_WIDTH_BLOCKS, widthBlocks + 1);
    }

    public static void shrink() {
        widthBlocks = Math.max(MIN_WIDTH_BLOCKS, widthBlocks - 1);
    }

    public static int getWidthBlocks() {
        return widthBlocks;
    }

    public static void toggleSnap() {
        snapEnabled = !snapEnabled;
    }

    public static boolean isSnapEnabled() {
        return snapEnabled;
    }

    /**
     * Places the previewed canvas at the current target.
     * @return true if a canvas was placed (caller should consume one held item)
     */
    public static boolean confirmPlacement() {
        if (!previewActive) return false;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return false;

        CanvasTransform t = computeTargetTransform(player);
        String dim = mc.level.dimension().location().toString();

        ImageCanvas canvas = new ImageCanvas(pendingId, dim, t.center(), t.facing(),
                t.width(), t.height(), pendingTexture);
        placed.add(canvas);
        saveCanvasRecord(canvas);

        LOGGER.info("Placed image canvas {} at {} facing {} ({}x{})",
                pendingId, t.center(), t.facing(), t.width(), t.height());

        previewActive = false;
        pendingId = null;
        pendingTexture = null;
        return true;
    }

    public static List<ImageCanvas> getPlacedCanvases() {
        return placed;
    }

    // ==================== REMOVAL ====================

    /**
     * Ray-casts the player's look against placed canvases and removes the nearest one hit.
     * The PNG and texture are kept (the item can be re-placed).
     * @return the removed canvas's id, or null if nothing was hit
     */
    public static String removeLookedAt(Player player) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 dir = player.getLookAngle();
        ImageCanvas hit = pickCanvas(eye, dir, 16.0);
        if (hit == null) return null;
        removeCanvas(hit);
        LOGGER.info("Removed image canvas {}", hit.id);
        return hit.id;
    }

    private static ImageCanvas pickCanvas(Vec3 eye, Vec3 dir, double maxDist) {
        ImageCanvas best = null;
        double bestT = maxDist;
        for (ImageCanvas c : placed) {
            Vec3 n = Vec3.atLowerCornerOf(c.facing.getNormal());
            double denom = dir.dot(n);
            if (Math.abs(denom) < 1e-6) continue;
            double t = c.center.subtract(eye).dot(n) / denom;
            if (t < 0 || t > bestT) continue;

            Vec3 p = eye.add(dir.scale(t));
            Vec3 right = new Vec3(0, 1, 0).cross(n).normalize();
            Vec3 d = p.subtract(c.center);
            double a = d.dot(right);
            double b = d.y;
            if (Math.abs(a) <= c.width / 2.0 && Math.abs(b) <= c.height / 2.0) {
                best = c;
                bestT = t;
            }
        }
        return best;
    }

    private static void removeCanvas(ImageCanvas c) {
        placed.remove(c);
        try {
            List<CanvasRecord> records = readManifest();
            records.removeIf(r -> c.id.equals(r.id));
            Files.writeString(manifestPath(), GSON.toJson(records));
        } catch (Exception e) {
            LOGGER.error("Failed to update manifest after removing {}", c.id, e);
        }
        // Keep PNG + cached texture: the returned item can be re-placed.
    }

    /** Removes every placed canvas in the current dimension. Returns how many were removed. */
    public static int clearCurrentDimension() {
        int count = placed.size();
        for (ImageCanvas c : new ArrayList<>(placed)) {
            removeCanvas(c);
        }
        return count;
    }

    // ==================== PERSISTENCE ====================

    private static Path canvasDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("falcraft").resolve("canvases");
    }

    private static Path manifestPath() {
        return canvasDir().resolve("canvases.json");
    }

    private static void saveCanvasRecord(ImageCanvas c) {
        try {
            Files.createDirectories(canvasDir());
            List<CanvasRecord> records = readManifest();
            records.removeIf(r -> c.id.equals(r.id)); // avoid duplicates on re-place
            CanvasRecord r = new CanvasRecord();
            r.id = c.id;
            r.dim = c.dim;
            r.facing = c.facing.getName();
            r.x = c.center.x;
            r.y = c.center.y;
            r.z = c.center.z;
            r.width = c.width;
            r.height = c.height;
            records.add(r);
            Files.writeString(manifestPath(), GSON.toJson(records));
        } catch (Exception e) {
            LOGGER.error("Failed to persist canvas record {}", c.id, e);
        }
    }

    private static List<CanvasRecord> readManifest() {
        try {
            Path mf = manifestPath();
            if (!Files.exists(mf)) return new ArrayList<>();
            List<CanvasRecord> list = GSON.fromJson(Files.readString(mf),
                    new TypeToken<List<CanvasRecord>>() {}.getType());
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            LOGGER.error("Failed to read canvas manifest", e);
            return new ArrayList<>();
        }
    }

    /** Loads/unloads canvases + textures as the player changes worlds. Called every client tick. */
    public static void tickLoad(Minecraft mc) {
        if (mc.level == null) {
            if (loadedDim != null) {
                releaseAllTextures();
                placed.clear();
                cancelPreview();
                loadedDim = null;
            }
            return;
        }
        String dim = mc.level.dimension().location().toString();
        if (dim.equals(loadedDim)) return;

        placed.clear();
        loadForDimension(dim);
        loadedDim = dim;
    }

    private static void releaseAllTextures() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation loc : textureCache.values()) {
            tm.release(loc);
        }
        // Releasing the DynamicTexture closes its internal image; close the held frame buffers too.
        for (VideoPlayback vp : videos.values()) {
            for (NativeImage frame : vp.frames) {
                frame.close();
            }
        }
        videos.clear();
        textureCache.clear();
        aspectCache.clear();
    }

    private static void loadForDimension(String dim) {
        int loaded = 0;
        for (CanvasRecord r : readManifest()) {
            if (!dim.equals(r.dim)) continue;
            ResourceLocation tex = getTexture(r.id);
            if (tex == null) continue;
            Direction facing = Direction.byName(r.facing);
            if (facing == null) facing = Direction.NORTH;
            placed.add(new ImageCanvas(r.id, r.dim, new Vec3(r.x, r.y, r.z), facing, r.width, r.height, tex));
            loaded++;
        }
        if (loaded > 0) {
            LOGGER.info("Loaded {} image canvas(es) for dimension {}", loaded, dim);
        }
    }
}
