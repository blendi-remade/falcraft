package com.falcraft.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceLocation;
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
import java.util.List;

/**
 * Manages AI-generated image "canvases" displayed in the world as full-fidelity
 * textured quads (Route B). Handles:
 * - a live placement preview that follows the player's crosshair onto walls,
 * - registering the downloaded PNG as a DynamicTexture,
 * - persisting placed canvases to disk and reloading them per world/dimension.
 *
 * All client-side; like falcraft's block placement, persistence is single-player oriented.
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
    private static final double WALL_OFFSET = 0.02; // push quad off the wall to avoid z-fighting

    /** A placement: where a canvas sits and how big it is. */
    public record CanvasTransform(Vec3 center, Direction facing, double width, double height) {}

    /** A placed (or persisted) image canvas. */
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

    /** Gson DTO for the on-disk manifest (no ResourceLocation/Vec3). */
    private static final class CanvasRecord {
        String id, dim, facing;
        double x, y, z, width, height;
    }

    private static final List<ImageCanvas> placed = new ArrayList<>();

    // Preview state
    private static boolean previewActive = false;
    private static byte[] pendingBytes = null;
    private static String pendingId = null;
    private static ResourceLocation pendingTexture = null;
    private static double aspect = 1.0; // height / width of the source image
    private static int widthBlocks = DEFAULT_WIDTH_BLOCKS;
    private static int forcedFacingIndex = -1; // -1 = auto from wall/look
    private static boolean snapEnabled = true; // snap center to the targeted block's face

    // Persistence load guard
    private static String loadedDim = null;

    // ==================== PREVIEW ====================

    /**
     * Starts the placement preview for a freshly generated image.
     * @param pngBytes the downloaded PNG
     */
    public static void startPreview(byte[] pngBytes) {
        try {
            NativeImage img = NativeImage.read(new ByteArrayInputStream(pngBytes));
            int w = img.getWidth();
            int h = img.getHeight();

            String id = String.valueOf(System.currentTimeMillis());
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("falcraft", "canvas/" + id);
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));

            pendingBytes = pngBytes;
            pendingId = id;
            pendingTexture = loc;
            aspect = h > 0 ? (double) h / w : 1.0;
            widthBlocks = DEFAULT_WIDTH_BLOCKS;
            forcedFacingIndex = -1;
            previewActive = true;

            LOGGER.info("Image preview started ({}x{}, aspect {}) texture {}", w, h, aspect, loc);
        } catch (Exception e) {
            LOGGER.error("Failed to start image preview", e);
            previewActive = false;
        }
    }

    public static boolean isPreviewActive() {
        return previewActive;
    }

    public static ResourceLocation getPreviewTexture() {
        return pendingTexture;
    }

    /** Computes where the canvas would land right now, from the player's crosshair. */
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
            facing = (forcedFacingIndex >= 0)
                    ? HORIZONTALS[forcedFacingIndex]
                    : nearestHorizontalTowardPlayer(look);
            center = eye.add(look.scale(Math.max(width, 4.0)));
        }

        Vec3 normal = Vec3.atLowerCornerOf(facing.getNormal());

        // Snap so the image's edges align to block boundaries (item-frame style).
        // Quantizes the two in-plane axes (vertical + the horizontal perpendicular to the
        // facing normal); the depth axis is left on the wall surface.
        if (snapEnabled) {
            double x = center.x, y = snapCenter(center.y, height), z = center.z;
            if (Math.abs(normal.x) > 0.5) {
                z = snapCenter(center.z, width); // facing E/W -> in-plane horizontal is Z
            } else {
                x = snapCenter(center.x, width); // facing N/S -> in-plane horizontal is X
            }
            center = new Vec3(x, y, z);
        }

        // Push the quad slightly off the surface, along the facing normal (toward the viewer).
        center = center.add(normal.scale(WALL_OFFSET));

        return new CanvasTransform(center, facing, width, height);
    }

    /** Snaps a center coordinate so the rectangle's lower edge (center - length/2) lands on an integer. */
    private static double snapCenter(double c, double length) {
        return Math.round(c - length / 2.0) + length / 2.0;
    }

    /** The image faces the player: normal points back toward the eye (opposite the look dir). */
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

    /** Confirms placement at the current target, persists it, and exits preview. */
    public static void confirmPlacement() {
        if (!previewActive) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        CanvasTransform t = computeTargetTransform(player);
        String dim = mc.level.dimension().location().toString();

        ImageCanvas canvas = new ImageCanvas(pendingId, dim, t.center(), t.facing(),
                t.width(), t.height(), pendingTexture);
        placed.add(canvas);
        saveCanvas(canvas, pendingBytes);

        LOGGER.info("Placed image canvas {} at {} facing {} ({}x{} blocks)",
                pendingId, t.center(), t.facing(), t.width(), t.height());

        // Exit preview but keep the texture (now owned by the placed canvas).
        previewActive = false;
        pendingBytes = null;
        pendingId = null;
        pendingTexture = null;
    }

    /** Cancels the preview and releases the pending texture. */
    public static void cancelPreview() {
        if (!previewActive) return;
        if (pendingTexture != null) {
            Minecraft.getInstance().getTextureManager().release(pendingTexture);
        }
        previewActive = false;
        pendingBytes = null;
        pendingId = null;
        pendingTexture = null;
        LOGGER.info("Cancelled image preview");
    }

    public static List<ImageCanvas> getPlacedCanvases() {
        return placed;
    }

    // ==================== REMOVAL ====================

    /**
     * Ray-casts the player's look against placed canvases and removes the nearest one hit.
     * @return true if a canvas was removed (caller can cancel the block-break)
     */
    public static boolean removeLookedAt(Player player) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 dir = player.getLookAngle();
        ImageCanvas hit = pickCanvas(eye, dir, 16.0);
        if (hit == null) return false;
        removeCanvas(hit);
        LOGGER.info("Removed image canvas {}", hit.id);
        return true;
    }

    /** Returns the nearest canvas whose rectangle the ray (eye + t*dir) intersects, or null. */
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
            double b = d.y; // up component
            if (Math.abs(a) <= c.width / 2.0 && Math.abs(b) <= c.height / 2.0) {
                best = c;
                bestT = t;
            }
        }
        return best;
    }

    private static void removeCanvas(ImageCanvas c) {
        Minecraft.getInstance().getTextureManager().release(c.texture);
        placed.remove(c);
        try {
            Files.deleteIfExists(canvasDir().resolve(c.id + ".png"));
            List<CanvasRecord> records = readManifest();
            records.removeIf(r -> c.id.equals(r.id));
            Files.writeString(manifestPath(), GSON.toJson(records));
        } catch (Exception e) {
            LOGGER.error("Failed to delete persisted canvas {}", c.id, e);
        }
    }

    /** Removes every canvas in the current dimension. Returns how many were removed. */
    public static int clearCurrentDimension() {
        int count = placed.size();
        // Copy to avoid concurrent modification while removeCanvas mutates `placed`.
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

    private static void saveCanvas(ImageCanvas c, byte[] pngBytes) {
        try {
            Path dir = canvasDir();
            Files.createDirectories(dir);
            Files.write(dir.resolve(c.id + ".png"), pngBytes);

            List<CanvasRecord> records = readManifest();
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
            LOGGER.error("Failed to persist image canvas {}", c.id, e);
        }
    }

    private static List<CanvasRecord> readManifest() {
        try {
            Path mf = manifestPath();
            if (!Files.exists(mf)) return new ArrayList<>();
            String json = Files.readString(mf);
            List<CanvasRecord> list = GSON.fromJson(json, new TypeToken<List<CanvasRecord>>() {}.getType());
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            LOGGER.error("Failed to read canvas manifest", e);
            return new ArrayList<>();
        }
    }

    /**
     * Loads/unloads persisted canvases as the player changes worlds.
     * Called every client tick (cheap guard).
     */
    public static void tickLoad(Minecraft mc) {
        if (mc.level == null) {
            if (loadedDim != null) {
                releaseAll();
                loadedDim = null;
            }
            return;
        }
        String dim = mc.level.dimension().location().toString();
        if (dim.equals(loadedDim)) return;

        releaseAll();
        loadForDimension(dim);
        loadedDim = dim;
    }

    private static void releaseAll() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (ImageCanvas c : placed) {
            tm.release(c.texture);
        }
        placed.clear();
    }

    private static void loadForDimension(String dim) {
        List<CanvasRecord> records = readManifest();
        int loaded = 0;
        for (CanvasRecord r : records) {
            if (!dim.equals(r.dim)) continue;
            try {
                Path png = canvasDir().resolve(r.id + ".png");
                if (!Files.exists(png)) continue;

                NativeImage img = NativeImage.read(new ByteArrayInputStream(Files.readAllBytes(png)));
                ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("falcraft", "canvas/" + r.id);
                Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));

                Direction facing = Direction.byName(r.facing);
                if (facing == null) facing = Direction.NORTH;

                placed.add(new ImageCanvas(r.id, r.dim, new Vec3(r.x, r.y, r.z), facing,
                        r.width, r.height, loc));
                loaded++;
            } catch (Exception e) {
                LOGGER.error("Failed to load persisted canvas {}", r.id, e);
            }
        }
        if (loaded > 0) {
            LOGGER.info("Loaded {} image canvas(es) for dimension {}", loaded, dim);
        }
    }
}
