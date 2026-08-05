package com.abacus.dualscreen;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Entity;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mob heads, cut out of each mob's own texture, for use as map markers.
 *
 * A coloured dot tells you something is there; a head tells you what. Minecraft has no head icon to
 * borrow, but almost every mob texture follows the same humanoid-ish layout the player skin does — the
 * front of the head at (8,8) in a 64-wide sheet, with the hat layer at (40,8) — so the face can be cut
 * straight out and scaled by however much bigger the sheet is.
 *
 * That layout is a convention, not a rule. Mobs that don't follow it (several of the four-legged
 * animals, and anything with an unusual sheet) come out wrong, so a failed or nonsensical crop falls
 * back to the plain dot rather than drawing nonsense on the map.
 *
 * Nothing is redistributed: the pixels come out of the player's own installed game or resource pack at
 * runtime, and are served only to that player's own screen.
 */
public final class HeadCache {

    private static final Map<String, byte[]> HEADS = new ConcurrentHashMap<>();
    private static final byte[] MISSING = new byte[0];

    /** Resolutions per tick. Cutting up a texture is cheap, but a mob farm can present many at once. */
    private static final int PER_TICK = 4;

    /** The face, in the coordinates of a 64-wide sheet. */
    private static final int FACE_X = 8;
    private static final int FACE_Y = 8;
    private static final int FACE = 8;

    /** The hat/overlay layer, drawn over the face where it isn't transparent. */
    private static final int HAT_X = 40;
    private static final int HAT_Y = 8;

    private HeadCache() {
    }

    public static byte[] get(String typeId) {
        byte[] png = HEADS.get(typeId);
        return png == null || png.length == 0 ? null : png;
    }

    public static boolean known(String typeId) {
        return HEADS.containsKey(typeId);
    }

    public static void clear() {
        HEADS.clear();
    }

    /**
     * Cut out a head if this type hasn't been seen yet.
     *
     * <p>Client thread only: it asks the render dispatcher which texture the entity uses.
     *
     * @return whether the budget was spent
     */
    public static boolean prime(String typeId, Entity entity, int spentThisTick) {
        if (typeId == null || entity == null || HEADS.containsKey(typeId) || spentThisTick >= PER_TICK) {
            return false;
        }

        HEADS.put(typeId, resolve(entity));
        return true;
    }

    private static byte[] resolve(Entity entity) {
        NativeImage sheet = null;
        NativeImage head = null;

        try {
            Minecraft client = Minecraft.getInstance();
            EntityRenderer<?> renderer = client.getEntityRenderDispatcher().getRenderer(entity);
            if (renderer == null) {
                return MISSING;
            }

            @SuppressWarnings("unchecked")
            ResourceLocation texture = ((EntityRenderer<Entity>) renderer).getTextureLocation(entity);
            if (texture == null) {
                return MISSING;
            }

            Optional<Resource> resource = client.getResourceManager().getResource(texture);
            if (resource.isEmpty()) {
                return MISSING;
            }

            byte[] bytes;
            try (InputStream in = resource.get().open()) {
                bytes = in.readAllBytes();
            }

            sheet = NativeImage.read(bytes);

            // sheets are authored at 64 wide and scaled up by packs, so the crop scales with them
            int scale = sheet.getWidth() / 64;
            if (scale < 1) {
                return MISSING;
            }

            int size = FACE * scale;
            int faceX = FACE_X * scale;
            int faceY = FACE_Y * scale;
            if (faceX + size > sheet.getWidth() || faceY + size > sheet.getHeight()) {
                return MISSING;
            }

            head = new NativeImage(NativeImage.Format.RGBA, size, size, false);
            boolean anything = false;

            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int pixel = sheet.getPixelRGBA(faceX + x, faceY + y);
                    if ((pixel >>> 24) != 0) {
                        anything = true;
                    }
                    head.setPixelRGBA(x, y, pixel);
                }
            }

            // a fully transparent face means the layout guess was wrong for this mob
            if (!anything) {
                return MISSING;
            }

            int hatX = HAT_X * scale;
            int hatY = HAT_Y * scale;
            if (hatX + size <= sheet.getWidth() && hatY + size <= sheet.getHeight()) {
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int pixel = sheet.getPixelRGBA(hatX + x, hatY + y);
                        if ((pixel >>> 24) != 0) {
                            head.setPixelRGBA(x, y, pixel);
                        }
                    }
                }
            }

            return head.asByteArray();
        } catch (Exception error) {
            return MISSING;
        } finally {
            if (sheet != null) {
                sheet.close();
            }
            if (head != null) {
                head.close();
            }
        }
    }
}
