package com.abacus.dualscreen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The atlas: explored ground, kept.
 *
 * The first version of this sent one square picture centred on the player and threw it away on every
 * rebuild, so the map could only ever show where you were standing. An atlas is the other idea — the
 * world is cut into fixed tiles, each is filled in the first time you walk over it, and it stays filled
 * afterwards. That is what lets you pan back over somewhere you've been and see it.
 *
 * Tiles are aligned to a fixed world grid rather than to the player, so a tile's contents never shift
 * and the browser can cache each revision of it forever.
 *
 * Writes happen on the client thread only. Reads come from request threads, which is why the finished
 * PNG for each tile is a volatile field rather than something a reader has to build.
 */
public final class AtlasStore {

    /** Blocks per tile edge. 64 keeps a tile cheap to re-encode while not making thousands of them. */
    public static final int TILE = 64;

    /** PNGs re-encoded per pass, so filling in a lot of new ground doesn't land in one frame. */
    private static final int ENCODES_PER_PASS = 3;

    private static final class Tile {
        final int[] pixels = new int[TILE * TILE];
        boolean dirty;
        volatile byte[] png;
        volatile long rev;

        /** When this tile was last written to, so the oldest can be dropped if the atlas gets large. */
        long touched;
    }

    private static final Map<Long, Tile> TILES = new ConcurrentHashMap<>();

    /** Tiles whose pixels have moved since they were last encoded. */
    private static final java.util.Queue<Long> DIRTY = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Which dimension the current tiles belong to. Changing it throws them away. */
    private static volatile String dimension = "";

    private static volatile long revision;

    private AtlasStore() {
    }

    private static long key(int tileX, int tileZ) {
        return ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL);
    }

    /** Floor division, so tiles line up either side of zero instead of doubling up on it. */
    private static int toTile(int world) {
        return Math.floorDiv(world, TILE);
    }

    public static byte[] png(int tileX, int tileZ) {
        Tile tile = TILES.get(key(tileX, tileZ));
        return tile == null ? null : tile.png;
    }

    /** Drop the in-memory atlas when leaving a world. What's on disk stays, and is read back on return. */
    public static void forget() {
        TILES.clear();
        dimension = "";
        folder = null;
        revision++;
    }

    /**
     * Sample the ground around the player into the tiles it falls in.
     *
     * <p>Client thread only.
     *
     * @param radius how far out to sample, in blocks
     */
    public static void update(ClientLevel level, int centreX, int centreZ, int radius) {
        String now = level.dimension().location().toString();
        if (!now.equals(dimension)) {
            // a different dimension is a different map; keeping the old one would paint the Nether
            // over the Overworld at the same coordinates
            TILES.clear();
            DIRTY.clear();
            dimension = now;
            folder = folderFor(now);
            load();
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // read the clock once for the whole pass instead of per column, which is tens of thousands
        long stamp = System.currentTimeMillis();

        for (int worldZ = centreZ - radius; worldZ <= centreZ + radius; worldZ++) {
            for (int worldX = centreX - radius; worldX <= centreX + radius; worldX++) {
                if (!level.hasChunkAt(worldX, worldZ)) {
                    continue;
                }

                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                cursor.set(worldX, top - 1, worldZ);
                BlockState state = level.getBlockState(cursor);
                MapColor colour = state.getMapColor(level, cursor);
                if (colour == null || colour == MapColor.NONE) {
                    continue;
                }

                int packed = shade(colour.col, top);

                long id = key(toTile(worldX), toTile(worldZ));
                Tile tile = TILES.computeIfAbsent(id, ignored -> new Tile());
                int index = Math.floorMod(worldZ, TILE) * TILE + Math.floorMod(worldX, TILE);
                tile.touched = stamp;

                if (tile.pixels[index] != packed) {
                    tile.pixels[index] = packed;
                    // queued once per change, not once per pixel: a tile has four thousand of them
                    if (!tile.dirty) {
                        tile.dirty = true;
                        DIRTY.add(id);
                    }
                }
            }
        }

        encodeSome();
        prune();
    }

    /**
     * Keep the atlas from growing without bound.
     *
     * Explored ground is the point of this, so nothing is dropped until there is a lot of it — and when
     * something has to go it is whatever was walked longest ago, which is the least likely to be looked
     * at again.
     */
    private static void prune() {
        // the size check is cheap; the sort below is not, so bail before reaching it
        int cap = DualScreenConfig.get().atlasMemoryTiles.get();
        if (TILES.size() <= cap) {
            return;
        }

        TILES.entrySet().stream()
                .sorted((left, right) -> Long.compare(left.getValue().touched, right.getValue().touched))
                .limit(TILES.size() - cap)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(TILES::remove);
    }

    /**
     * Turn a few changed tiles into PNGs. Bounded so a long walk spreads the cost over several ticks.
     *
     * Works from a list of what actually changed rather than scanning every tile. The scan was fine
     * with a dozen tiles and quietly became a sweep of thousands, several times a second, once the
     * atlas started being kept — nearly all of it looking at tiles that had not moved.
     */
    private static void encodeSome() {
        int done = 0;

        while (done < ENCODES_PER_PASS) {
            Long id = DIRTY.poll();
            if (id == null) {
                return;
            }

            Tile tile = TILES.get(id);
            if (tile == null || !tile.dirty) {
                continue;
            }

            NativeImage image = null;
            try {
                image = new NativeImage(NativeImage.Format.RGBA, TILE, TILE, false);
                for (int y = 0; y < TILE; y++) {
                    for (int x = 0; x < TILE; x++) {
                        image.setPixelRGBA(x, y, tile.pixels[y * TILE + x]);
                    }
                }
                tile.png = image.asByteArray();
                tile.dirty = false;
                tile.rev = ++revision;
                done++;
                save(id, tile.png);
            } catch (Exception error) {
                tile.dirty = false;
                AynDualScreen.LOG.warn("[Ayn Dual Screen] Tile encode failed: {}",
                        String.valueOf(error.getMessage()));
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }
    }

    /**
     * What the page needs to know: which tiles exist and which revision each is on.
     *
     * Only tiles near the viewport would be needed in principle, but the whole index is a few hundred
     * bytes even after a long session, and sending it whole means the page can draw anywhere the player
     * has been without another round trip.
     */
    public static JsonObject index() {
        JsonObject json = new JsonObject();
        json.addProperty("tile", TILE);
        json.addProperty("dimension", dimension);

        JsonArray list = new JsonArray();
        List<Map.Entry<Long, Tile>> entries = new ArrayList<>(TILES.entrySet());

        for (Map.Entry<Long, Tile> entry : entries) {
            Tile tile = entry.getValue();
            if (tile.png == null) {
                continue;
            }

            long id = entry.getKey();
            JsonObject item = new JsonObject();
            item.addProperty("x", (int) (id >> 32));
            item.addProperty("z", (int) (id & 0xFFFFFFFFL));
            item.addProperty("rev", tile.rev);
            list.add(item);
        }

        json.add("tiles", list);
        json.addProperty("count", list.size());
        return json;
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Keeping the atlas between sessions
     *
     * An atlas you lose when you quit isn't really an atlas — walking somewhere and having it there
     * tomorrow is the whole point. Tiles are written out as they're encoded and read back the first
     * time a dimension is entered.
     *
     * All the file work happens off the client thread. Disk is slow and unpredictable, and nothing
     * here is worth a stutter: a tile that fails to save is simply drawn again next time you walk
     * past it.
     * ---------------------------------------------------------------------------------------------
     */

    private static final java.util.concurrent.ExecutorService DISK =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "AynDualScreen atlas");
                thread.setDaemon(true);
                return thread;
            });

    /** Where the current dimension's tiles live, or null before a dimension is known. */
    private static volatile java.nio.file.Path folder;

    private static java.nio.file.Path folderFor(String dimensionId) {
        // the id is namespaced, e.g. minecraft:the_nether, and a colon is not a legal path character
        String safe = dimensionId.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
                .resolve("ayndualscreen").resolve("atlas").resolve(safe);
    }

    private static void save(long id, byte[] png) {
        java.nio.file.Path where = folder;
        if (where == null || png == null) {
            return;
        }

        DISK.submit(() -> {
            try {
                java.nio.file.Files.createDirectories(where);
                java.nio.file.Files.write(where.resolve(((int) (id >> 32)) + "_" + ((int) id) + ".png"), png);
            } catch (Exception ignored) {
                // a tile that won't save just gets redrawn next time; not worth telling anyone about
            }
        });
    }

    /**
     * Read a dimension's saved tiles back in.
     *
     * The pixels are decoded as well as the PNG kept, because a tile that is only half-explored has to
     * be paintable over — otherwise walking to the edge of somewhere you have been would do nothing.
     */
    private static void load() {
        java.nio.file.Path where = folder;
        if (where == null || !java.nio.file.Files.isDirectory(where)) {
            return;
        }

        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(where)) {
            files.filter(path -> path.getFileName().toString().endsWith(".png")).forEach(path -> {
                String name = path.getFileName().toString().replace(".png", "");
                String[] parts = name.split("_");
                if (parts.length != 2) {
                    return;
                }

                NativeImage image = null;
                try {
                    int tileX = Integer.parseInt(parts[0]);
                    int tileZ = Integer.parseInt(parts[1]);
                    byte[] png = java.nio.file.Files.readAllBytes(path);

                    image = NativeImage.read(png);
                    if (image.getWidth() != TILE || image.getHeight() != TILE) {
                        return;
                    }

                    Tile tile = new Tile();
                    for (int y = 0; y < TILE; y++) {
                        for (int x = 0; x < TILE; x++) {
                            tile.pixels[y * TILE + x] = image.getPixelRGBA(x, y);
                        }
                    }
                    tile.png = png;
                    tile.rev = ++revision;
                    tile.touched = System.currentTimeMillis();
                    TILES.put(key(tileX, tileZ), tile);
                } catch (Exception ignored) {
                    // a corrupt or half-written tile is dropped rather than allowed to break the load
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            });
        } catch (Exception error) {
            AynDualScreen.LOG.warn("[Ayn Dual Screen] Could not read the saved atlas: {}",
                    String.valueOf(error.getMessage()));
        }
    }

    /**
     * Darken or lighten by height, so the map reads as terrain instead of a flat colour field.
     *
     * Vanilla shades each map colour in three fixed steps; this is a continuous version of the same
     * idea, keyed off sea level so caves and mountains fall either side of neutral.
     */
    private static int shade(int rgb, int height) {
        float lift = Math.max(-0.35f, Math.min(0.35f, (height - 63) / 120f));

        int red = clamp(((rgb >> 16) & 0xFF) * (1 + lift));
        int green = clamp(((rgb >> 8) & 0xFF) * (1 + lift));
        int blue = clamp((rgb & 0xFF) * (1 + lift));

        // NativeImage packs ABGR, not ARGB - getting this backwards swaps red and blue everywhere
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    private static int clamp(float value) {
        return (int) Math.max(0, Math.min(255, value));
    }
}
