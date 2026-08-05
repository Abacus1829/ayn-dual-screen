package com.abacus.dualscreen;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Everything the second screen can be told to do differently.
 *
 * LAN access is on by default, matching the other two mods: a second screen that only answers the PC it
 * runs on is useless, and the first thing anyone hit before was a connection refused with no clue why.
 */
public final class DualScreenConfig {

    public static final ForgeConfigSpec SPEC;
    private static final DualScreenConfig INSTANCE;

    public final ForgeConfigSpec.IntValue port;
    public final ForgeConfigSpec.BooleanValue allowLanAccess;

    public final ForgeConfigSpec.BooleanValue showHostiles;
    public final ForgeConfigSpec.BooleanValue showPassives;
    public final ForgeConfigSpec.BooleanValue showPlayers;
    public final ForgeConfigSpec.IntValue maxEntities;
    public final ForgeConfigSpec.IntValue entityRange;

    public final ForgeConfigSpec.IntValue mapRadius;
    public final ForgeConfigSpec.IntValue mapInterval;
    public final ForgeConfigSpec.BooleanValue showHeads;
    public final ForgeConfigSpec.BooleanValue showItems;
    public final ForgeConfigSpec.IntValue atlasMemoryTiles;

    public final ForgeConfigSpec.BooleanValue allowHotbarSelect;
    public final ForgeConfigSpec.BooleanValue allowChat;
    public final ForgeConfigSpec.BooleanValue allowDrop;
    public final ForgeConfigSpec.BooleanValue allowCrafting;

    private DualScreenConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Where the second screen listens.").push("network");

        this.port = builder
                .comment("TCP port for the second screen. 27302 by default so it doesn't collide with the",
                        "Terraria and Stardew mods, which both use 27301.")
                .defineInRange("port", 27302, 1024, 65535);

        this.allowLanAccess = builder
                .comment("Answer other devices on your network, not just this PC.",
                        "On by default - a second screen on a handheld is the whole point.",
                        "Turn it off to bind loopback only.")
                .define("allowLanAccess", true);

        builder.pop();
        builder.comment("What appears on the minimap.").push("map");

        this.showHostiles = builder.define("showHostiles", true);
        this.showPassives = builder.define("showPassives", true);
        this.showPlayers = builder.define("showPlayers", true);

        this.maxEntities = builder
                .comment("Cap on entity markers. The nearest are kept; a mob farm can otherwise put",
                        "hundreds of dots on the map for no benefit.")
                .defineInRange("maxEntities", 80, 10, 500);

        this.entityRange = builder
                .comment("How far out to look for entities, in blocks.")
                .defineInRange("entityRange", 64, 16, 256);

        this.mapRadius = builder
                .comment("Half-width of the terrain map, in blocks. Larger costs more per rebuild:",
                        "the sample count is (2*radius)^2.")
                .defineInRange("mapRadius", 64, 16, 160);

        this.mapInterval = builder
                .comment("Ticks between checks for whether the terrain map needs redrawing.",
                        "A redraw only actually happens if you have moved since the last one, so a low",
                        "number here costs nothing while you stand still and keeps the map under you",
                        "while you run. Raise it if sampling ever shows up in your frame times.")
                .defineInRange("mapIntervalTicks", 4, 1, 200);

        this.showHeads = builder
                .comment("Draw mobs as their own head instead of a coloured dot.",
                        "The head is cut out of the mob's texture at runtime. Most mobs follow the",
                        "player-skin layout and come out right; a few don't, and those quietly fall back",
                        "to a dot rather than drawing something wrong.")
                .define("showHeads", true);

        this.showItems = builder
                .comment("Include dropped items as markers. Off by default - a mob farm or a death pile",
                        "puts hundreds of them on the map and crowds out everything worth seeing.")
                .define("showItems", false);

        this.atlasMemoryTiles = builder
                .comment("How many 64x64 tiles of explored ground to keep. The oldest are dropped past",
                        "this. Each tile is a few kilobytes, so the default is a handful of megabytes even",
                        "after a long session.")
                .defineInRange("atlasMemoryTiles", 4096, 256, 65536);

        builder.pop();
        builder.comment("What the second screen is allowed to do, as opposed to just show.").push("control");

        this.allowHotbarSelect = builder
                .comment("Let a tap on the hotbar change the held item.")
                .define("allowHotbarSelect", true);

        this.allowChat = builder
                .comment("Let the second screen send chat messages and commands as you.",
                        "Off by default: it is the one control here that can do something irreversible.")
                .define("allowChat", false);

        this.allowDrop = builder
                .comment("Let the second screen drop the held stack.")
                .define("allowDrop", false);

        this.allowCrafting = builder
                .comment("Let the second screen fill an open crafting grid from the recipe list.",
                        "It sends the same packet the vanilla recipe book does, so the server does the",
                        "moving and applies its own rules - but it only works while a crafting screen is",
                        "actually open, because there is no grid to fill otherwise.",
                        "Browsing recipes works regardless; this is only about placing them.")
                .define("allowCrafting", true);

        builder.pop();
    }

    static {
        Pair<DualScreenConfig, ForgeConfigSpec> pair =
                new ForgeConfigSpec.Builder().configure(DualScreenConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    public static DualScreenConfig get() {
        return INSTANCE;
    }
}
