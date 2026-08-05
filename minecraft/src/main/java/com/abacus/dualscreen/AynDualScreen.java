package com.abacus.dualscreen;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * A second screen for Minecraft, mirroring what the Stardew and Terraria mods do.
 *
 * The shape is the same in all three: the game thread publishes a finished snapshot, a tiny HTTP server
 * hands it to a browser on the local network, and anything the browser asks for comes back through a
 * queue that only the game thread drains. Nothing here touches the game from a socket thread.
 *
 * Client-only by design. It reports what the local player can already see, so it has no business
 * running on a dedicated server and declares itself accordingly.
 */
@Mod(AynDualScreen.MOD_ID)
public final class AynDualScreen {

    public static final String MOD_ID = "ayndualscreen";
    public static final Logger LOG = LogUtils.getLogger();

    /**
     * Forge hands the loading context to the constructor on 1.21.
     *
     * Taking it as a parameter rather than calling the static {@code get()} accessors: those are
     * deprecated for removal, and they only work because loading happens to be single-threaded per mod.
     */
    public AynDualScreen(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.CLIENT, DualScreenConfig.SPEC);
        context.getModEventBus().addListener(this::clientSetup);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // enqueueWork rather than doing it here: setup events run in parallel across mods, and starting
        // a listener is not something to do from an arbitrary worker
        event.enqueueWork(() -> {
            MinecraftForge.EVENT_BUS.register(new DualScreenClient());
            DualScreenServer.start();
        });
    }
}
