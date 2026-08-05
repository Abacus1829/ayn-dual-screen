package com.abacus.dualscreen;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Item icons, as PNG bytes the page can put straight in an img tag.
 *
 * The texture is found through the item's own baked model rather than by guessing at
 * {@code textures/item/<name>.png}: plenty of items don't follow that pattern, and blocks live under a
 * different folder entirely. Asking the model for its particle icon is what the game itself does when
 * it needs one image to stand for a stack, so it resolves both without a pile of special cases.
 *
 * Nothing here redistributes anything. The bytes come out of the player's own installed game (or
 * whatever resource pack they have on) at runtime, and are served only to that player's own screen.
 */
public final class IconCache {

    /** Resolved icons, keyed by item id. Empty array means "looked, found nothing" so it isn't retried. */
    private static final Map<String, byte[]> ICONS = new ConcurrentHashMap<>();

    private static final byte[] MISSING = new byte[0];

    /** Resolving touches the model manager, so it happens on the client thread, a few per tick. */
    private static final int PER_TICK = 6;

    /** Render size. Bigger than a slot so the art still looks sharp on a high-density handheld. */
    private static final int SIZE = 64;

    private IconCache() {
    }

    /** The PNG for an item id, or null if there isn't one. Safe to call from a request thread. */
    public static byte[] get(String itemId) {
        byte[] png = ICONS.get(itemId);
        return png == null || png.length == 0 ? null : png;
    }

    public static boolean known(String itemId) {
        return ICONS.containsKey(itemId);
    }

    /** Items waiting to be drawn, and the ones already queued so they aren't queued twice. */
    private static final java.util.Queue<Pending> QUEUE = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.Set<String> QUEUED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record Pending(String id, ItemStack stack) {
    }

    /** Logged once. A failing render falls back silently otherwise, which hides the reason. */
    private static volatile boolean reportedFailure;

    /**
     * Note that an item needs artwork.
     *
     * <p>The drawing itself deliberately doesn't happen here. A tick runs between frames, where the
     * GL state is whatever the last frame left behind — rendering an item there is exactly the kind of
     * thing that silently produces nothing. The work is queued and done during the frame instead, by
     * {@link #drawQueued()}.
     *
     * @return whether this call queued something
     */
    public static boolean prime(String itemId, ItemStack stack, int spentThisTick) {
        if (itemId == null || stack == null || stack.isEmpty() || ICONS.containsKey(itemId)) {
            return false;
        }
        if (spentThisTick >= PER_TICK || !QUEUED.add(itemId)) {
            return false;
        }

        QUEUE.add(new Pending(itemId, stack.copy()));
        return true;
    }

    /**
     * Draw a couple of the queued items.
     *
     * <p>Called from inside the GUI render pass, where the projection, blending and shaders are all
     * set up for exactly this kind of drawing. Two per frame keeps a freshly opened inventory from
     * costing a visible stutter while still filling in within a second.
     */
    public static void drawQueued() {
        for (int i = 0; i < 2; i++) {
            Pending next = QUEUE.poll();
            if (next == null) {
                return;
            }
            ICONS.put(next.id(), resolve(next.stack()));
        }
    }

    /**
     * Render the stack exactly as the game draws it in a GUI slot.
     *
     * <p>This is what makes a block look like a block. The sprite fallback below can only ever give one
     * flat face, because that is all a particle icon is; putting the real item renderer in front of an
     * off-screen target gets the actual model, in the actual GUI projection, with the same lighting the
     * inventory uses — cube for blocks, sprite for items, damage bar and all.
     *
     * <p>Render thread only, which the client tick is.
     *
     * @return the PNG, or null if anything about the render failed
     */
    private static byte[] render(ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        if (!RenderSystem.isOnRenderThread()) {
            return null;
        }

        TextureTarget target = null;
        NativeImage image = null;

        try {
            target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);

            // the GUI projection is 16 units across, which is one slot; the target is that at SIZE pixels
            Matrix4f projection = new Matrix4f().setOrtho(0f, 16f, 16f, 0f, 1000f, 21000f);
            Matrix4f previousProjection = RenderSystem.getProjectionMatrix();
            VertexSorting previousSorting = RenderSystem.getVertexSorting();
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);

            // this is JOML's matrix stack, not a PoseStack - the two have different push/pop names
            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.identity();
            modelView.translate(0f, 0f, -11000f);
            RenderSystem.applyModelViewMatrix();

            GuiGraphics graphics = new GuiGraphics(client, client.renderBuffers().bufferSource());
            graphics.renderItem(stack, 0, 0);
            graphics.flush();

            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);

            image = new NativeImage(SIZE, SIZE, false);
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, false);
            // the framebuffer's origin is bottom-left and a PNG's is top-left
            image.flipY();

            // an entirely transparent result means the render silently did nothing
            boolean anything = false;
            for (int y = 0; y < SIZE && !anything; y++) {
                for (int x = 0; x < SIZE; x++) {
                    if ((image.getPixelRGBA(x, y) >>> 24) != 0) {
                        anything = true;
                        break;
                    }
                }
            }

            return anything ? image.asByteArray() : null;
        } catch (Throwable error) {
            // A GL failure must not take the game with it, but it must not be invisible either: this is
            // the difference between "blocks look flat" and knowing why they do.
            if (!reportedFailure) {
                reportedFailure = true;
                AynDualScreen.LOG.warn("[Ayn Dual Screen] Item rendering failed, falling back to flat "
                        + "sprites for every item. Cause: {}: {}",
                        error.getClass().getSimpleName(), String.valueOf(error.getMessage()));
            }
            return null;
        } finally {
            if (image != null) {
                image.close();
            }
            if (target != null) {
                target.destroyBuffers();
            }
            // whatever happened, hand the screen back to the game
            try {
                client.getMainRenderTarget().bindWrite(true);
            } catch (Throwable ignored) {
                // nothing useful to do if even this fails
            }
        }
    }

    private static byte[] resolve(ItemStack stack) {
        byte[] rendered = render(stack);
        if (rendered != null) {
            return rendered;
        }

        try {
            Minecraft client = Minecraft.getInstance();
            BakedModel model = client.getItemRenderer().getModel(stack, client.level, client.player, 0);
            TextureAtlasSprite sprite = model.getParticleIcon();
            if (sprite == null) {
                return MISSING;
            }

            ResourceLocation name = sprite.contents().name();
            ResourceLocation file = ResourceLocation.fromNamespaceAndPath(
                    name.getNamespace(), "textures/" + name.getPath() + ".png");

            Optional<Resource> resource = client.getResourceManager().getResource(file);
            if (resource.isEmpty()) {
                return MISSING;
            }

            byte[] bytes;
            try (InputStream in = resource.get().open()) {
                bytes = in.readAllBytes();
            }

            return firstFrame(bytes);
        } catch (Exception error) {
            // a modded item with an unusual model is not worth failing a snapshot over
            return MISSING;
        }
    }

    /**
     * Animated textures are stored as a vertical strip of frames.
     *
     * Served as-is, lava or prismarine would arrive as a tall ribbon of every frame at once. Cropping to
     * the top square gives the still image the inventory would show anyway.
     */
    private static byte[] firstFrame(byte[] png) {
        int width = readInt(png, 16);
        int height = readInt(png, 20);

        if (width <= 0 || height <= width) {
            return png;
        }

        NativeImage full = null;
        NativeImage frame = null;
        try {
            full = NativeImage.read(png);
            frame = new NativeImage(NativeImage.Format.RGBA, width, width, false);
            for (int y = 0; y < width; y++) {
                for (int x = 0; x < width; x++) {
                    frame.setPixelRGBA(x, y, full.getPixelRGBA(x, y));
                }
            }
            return frame.asByteArray();
        } catch (Exception error) {
            return png;
        } finally {
            if (full != null) {
                full.close();
            }
            if (frame != null) {
                frame.close();
            }
        }
    }

    /** PNG stores width and height as big-endian ints in the IHDR chunk, at byte 16 and 20. */
    private static int readInt(byte[] bytes, int at) {
        if (bytes == null || bytes.length < at + 4) {
            return -1;
        }
        return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
                | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
    }

    /** Dropped when the player leaves a world: a resource pack change would otherwise leave stale art. */
    public static void clear() {
        ICONS.clear();
        QUEUE.clear();
        QUEUED.clear();
        reportedFailure = false;
    }
}
