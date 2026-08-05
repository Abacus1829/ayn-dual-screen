package com.abacus.dualscreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.effect.MobEffect;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The little potion icons, so effects read at a glance instead of as a list of words.
 *
 * The game keeps these on their own atlas, and the sprite knows which file it came from — so the same
 * trick the item icons use works here: ask for the sprite, ask it its name, and read that PNG straight
 * out of whatever resource pack is active. No artwork ships with the mod.
 */
public final class EffectIcons {

    private static final Map<String, byte[]> ICONS = new ConcurrentHashMap<>();
    private static final byte[] MISSING = new byte[0];

    private EffectIcons() {
    }

    public static byte[] get(String id) {
        byte[] png = ICONS.get(id);
        return png == null || png.length == 0 ? null : png;
    }

    public static void clear() {
        ICONS.clear();
    }

    /**
     * Resolve an effect's icon once.
     *
     * <p>Client thread only.
     *
     * @return whether an icon is available for this effect
     */
    public static boolean prime(String id, Holder<MobEffect> effect) {
        if (id == null || effect == null) {
            return false;
        }

        byte[] known = ICONS.get(id);
        if (known != null) {
            return known.length > 0;
        }

        byte[] png = resolve(effect);
        ICONS.put(id, png);
        return png.length > 0;
    }

    private static byte[] resolve(Holder<MobEffect> effect) {
        try {
            Minecraft client = Minecraft.getInstance();
            TextureAtlasSprite sprite = client.getMobEffectTextures().get(effect);
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

            try (InputStream in = resource.get().open()) {
                return in.readAllBytes();
            }
        } catch (Exception error) {
            // an effect from a mod with an unusual texture setup simply gets no icon
            return MISSING;
        }
    }
}
