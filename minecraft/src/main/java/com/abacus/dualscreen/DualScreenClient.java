package com.abacus.dualscreen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Everything that touches the game.
 *
 * The rule the other two mods follow holds here too: game state is read only on the client thread, and
 * only ever published as a finished string. The HTTP workers read those strings and drop commands into
 * {@link #COMMANDS}; this class drains that queue on the next tick. No lock, and no chance of a socket
 * thread walking the inventory while the game is halfway through changing it.
 */
public final class DualScreenClient {

    /** The whole hotbar plus main inventory. Armour and offhand are reported separately. */
    private static final int INVENTORY_SLOTS = 36;

    private static final ConcurrentLinkedQueue<Map<String, String>> COMMANDS = new ConcurrentLinkedQueue<>();

    /** Item and entity ids, which never change for a given type and are otherwise rebuilt every tick. */
    private static final Map<net.minecraft.world.item.Item, String> ITEM_IDS = new ConcurrentHashMap<>();
    private static final Map<net.minecraft.world.entity.EntityType<?>, String> TYPE_IDS =
            new ConcurrentHashMap<>();

    private static volatile String stateJson = "{\"ready\":false,\"game\":\"minecraft\"}";
    private static volatile String mapJson = "{\"ready\":false}";
    private static volatile String recipeJson = "{\"ready\":false,\"recipes\":[]}";

    public static String recipeJson() {
        return recipeJson;
    }

    /**
     * Bumped every time a new snapshot is published, and waited on by request threads.
     *
     * This is what removes the polling delay: a request can say which revision it already has and be
     * released the instant there is a newer one, instead of the page guessing an interval and being
     * wrong in both directions — late when the game moves, wasteful when it doesn't.
     */
    private static final Object SIGNAL = new Object();
    private static volatile long stateRev;

    private int mapTick;
    private double lastMapX = Double.NaN;
    private double lastMapZ = Double.NaN;

    /** Icon resolutions spent this tick, so a freshly opened inventory doesn't decode forty at once. */
    private int iconsThisTick;

    public static String stateJson() {
        return stateJson;
    }

    public static String mapJson() {
        return mapJson;
    }

    public static long stateRev() {
        return stateRev;
    }

    /**
     * Hold a request until there is something newer than [since], or the wait runs out.
     *
     * The timeout matters as much as the wait: a connection that never returns looks identical to a
     * dead one, so this always answers, and a caller with nothing new just asks again.
     */
    public static String awaitState(long since, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (SIGNAL) {
            while (stateRev <= since) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    SIGNAL.wait(left);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return stateJson;
    }

    /** Publish, then release anyone waiting. Order matters: the text must be visible before the count. */
    private static void publishState(String json) {
        stateJson = json;
        synchronized (SIGNAL) {
            stateRev++;
            SIGNAL.notifyAll();
        }
    }

    public static void enqueue(Map<String, String> command) {
        if (command != null && !command.isEmpty()) {
            COMMANDS.add(command);
        }
    }

    /**
     * Draw any queued item art, inside the render loop.
     *
     * A client tick fires twenty times a second regardless of frames, and its GL state is whatever the
     * last frame happened to leave. Rendering an item there is the kind of thing that quietly produces
     * nothing at all. This runs at the end of a frame instead, where the context is unambiguously the
     * one that has just been drawing.
     */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent.Post event) {
        IconCache.drawQueued();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        ClientLevel level = client.level;

        if (player == null || level == null) {
            publishState("{\"ready\":false,\"game\":\"minecraft\"}");
            mapJson = "{\"ready\":false}";
            COMMANDS.clear();
            // a resource pack can change between worlds, so cached art must not outlive the session
            IconCache.clear();
            HeadCache.clear();
            EffectIcons.clear();
            AtlasStore.forget();
            return;
        }

        drain(client, player);

        // Every tick. The snapshot is a few kilobytes of JSON off objects already in memory, which is
        // nothing next to a frame of rendering — and anything less shows up as the second screen
        // lagging behind the game, which is the one thing it cannot afford to do.
        this.iconsThisTick = 0;
        try {
            publishState(buildState(client, player, level).toString());
        } catch (Exception error) {
            AynDualScreen.LOG.warn("[Ayn Dual Screen] Snapshot failed: {}", String.valueOf(error.getMessage()));
        }

        maybeRebuildMap(level, player);
    }

    /**
     * Redraw the terrain only when it would actually look different.
     *
     * Sampling thousands of columns is the expensive part of all this, and standing still doesn't change
     * any of them. Gating on movement means the map can refresh quickly while you walk — which is what
     * makes it feel smooth — without burning the same work over and over while you're in a chest.
     */
    private void maybeRebuildMap(ClientLevel level, LocalPlayer player) {
        int interval = DualScreenConfig.get().mapInterval.get();
        if (++this.mapTick % interval != 0) {
            return;
        }

        double moved = Double.isNaN(this.lastMapX)
                ? Double.MAX_VALUE
                : Math.abs(player.getX() - this.lastMapX) + Math.abs(player.getZ() - this.lastMapZ);

        // a full pass at least every few seconds anyway, so block changes around you don't go stale
        boolean stale = this.mapTick % (interval * 12) == 0;
        if (moved < 2.0 && !stale) {
            return;
        }

        this.lastMapX = player.getX();
        this.lastMapZ = player.getZ();

        AtlasStore.update(level, player.getBlockX(), player.getBlockZ(),
                DualScreenConfig.get().mapRadius.get());

        JsonObject json = AtlasStore.index();
        json.addProperty("ready", true);
        mapJson = json.toString();
    }

    /*********
     * Snapshot
     *********/
    private JsonObject buildState(Minecraft client, LocalPlayer player, ClientLevel level) {
        JsonObject state = new JsonObject();

        // the app tells the three mods apart by this; the other two are identified by fields they
        // happen to have, which stops being reliable once there are three of them
        state.addProperty("game", "minecraft");
        state.addProperty("ready", true);
        state.addProperty("tick", level.getGameTime());

        // what the page sends back as ?since=, so it can ask for the next one rather than this one.
        // publishState increments by exactly one, so this is the number this snapshot will be given.
        state.addProperty("rev", stateRev + 1);

        state.addProperty("worldName", worldName(client));
        state.addProperty("dimension", level.dimension().location().getPath());
        state.addProperty("biome", biome(level, player.blockPosition()));

        long dayTime = level.getDayTime();
        state.addProperty("day", dayTime / 24000L);
        state.addProperty("timeOfDay", dayTime % 24000L);
        state.addProperty("raining", level.isRaining());
        state.addProperty("thundering", level.isThundering());

        state.addProperty("health", player.getHealth());
        state.addProperty("healthMax", player.getMaxHealth());
        state.addProperty("food", player.getFoodData().getFoodLevel());
        state.addProperty("saturation", player.getFoodData().getSaturationLevel());
        state.addProperty("armor", player.getArmorValue());
        state.addProperty("air", player.getAirSupply());
        state.addProperty("airMax", player.getMaxAirSupply());
        state.addProperty("xpLevel", player.experienceLevel);
        state.addProperty("xpProgress", player.experienceProgress);

        state.addProperty("x", player.getX());
        state.addProperty("y", player.getY());
        state.addProperty("z", player.getZ());
        // Minecraft yaw is 0 south and grows clockwise; the page turns it into a compass heading
        state.addProperty("yaw", player.getYRot());

        state.addProperty("selectedSlot", player.getInventory().selected);
        state.add("inventory", buildInventory(player));
        state.add("armorSlots", buildArmor(player));
        state.add("offhand", describe(player.getOffhandItem()));
        state.add("effects", buildEffects(player));
        state.add("entities", buildEntities(player, level));

        state.add("craft", buildCrafting(player));

        JsonObject can = new JsonObject();
        can.addProperty("hotbar", DualScreenConfig.get().allowHotbarSelect.get());
        can.addProperty("chat", DualScreenConfig.get().allowChat.get());
        can.addProperty("drop", DualScreenConfig.get().allowDrop.get());
        state.add("can", can);

        return state;
    }

    private JsonArray buildInventory(LocalPlayer player) {
        JsonArray slots = new JsonArray();
        for (int i = 0; i < INVENTORY_SLOTS; i++) {
            slots.add(describe(player.getInventory().getItem(i)));
        }
        return slots;
    }

    private JsonArray buildArmor(LocalPlayer player) {
        JsonArray slots = new JsonArray();
        // the list runs feet to head; reversed here so the page can draw it the way the inventory does
        for (int i = player.getInventory().armor.size() - 1; i >= 0; i--) {
            slots.add(describe(player.getInventory().armor.get(i)));
        }
        return slots;
    }

    /** One item slot. Empty slots still get an object, so the page can index straight into the array. */
    private JsonObject describe(ItemStack stack) {
        JsonObject item = new JsonObject();
        if (stack == null || stack.isEmpty()) {
            item.addProperty("empty", true);
            return item;
        }

        // Registry lookup and toString for forty-odd slots, twenty times a second, all producing the
        // same handful of strings. An item's id never changes, so it is worth remembering.
        String id = ITEM_IDS.computeIfAbsent(stack.getItem(),
                which -> BuiltInRegistries.ITEM.getKey(which).toString());

        item.addProperty("empty", false);
        item.addProperty("name", stack.getHoverName().getString());
        item.addProperty("count", stack.getCount());
        item.addProperty("id", id);

        // resolve the artwork while we're on the client thread and holding the stack; the page then
        // asks for it by id over a separate request, which keeps the snapshot small
        if (IconCache.prime(id, stack, this.iconsThisTick)) {
            this.iconsThisTick++;
        }
        item.addProperty("icon", IconCache.known(id) && IconCache.get(id) != null);

        if (stack.isDamageableItem()) {
            int max = stack.getMaxDamage();
            item.addProperty("durability", max - stack.getDamageValue());
            item.addProperty("durabilityMax", max);
        }

        if (stack.isEnchanted()) {
            item.addProperty("enchanted", true);
        }

        return item;
    }

    /**
     * The crafting grid that is actually open, whatever size it is.
     *
     * The player's own inventory gives a 2x2; a table gives a 3x3; both put the result in slot 0 and
     * the grid in the slots straight after. Reading the live menu rather than assuming a size is what
     * lets the second screen change shape when you walk up to a table, and it means the grid shows
     * what is really in it rather than the page's idea of what should be.
     */
    private JsonObject buildCrafting(LocalPlayer player) {
        JsonObject craft = new JsonObject();
        AbstractContainerMenu menu = player.containerMenu;

        int width;
        int height;
        if (menu instanceof InventoryMenu) {
            width = 2;
            height = 2;
        } else if (menu instanceof CraftingMenu) {
            width = 3;
            height = 3;
        } else {
            // a chest or a furnace has no grid; say so rather than showing an empty one
            craft.addProperty("open", false);
            return craft;
        }

        craft.addProperty("open", true);
        craft.addProperty("table", width == 3);
        craft.addProperty("width", width);
        craft.addProperty("height", height);

        JsonArray grid = new JsonArray();
        for (int i = 1; i <= width * height; i++) {
            grid.add(i < menu.slots.size() ? describe(menu.slots.get(i).getItem()) : describe(ItemStack.EMPTY));
        }
        craft.add("grid", grid);
        craft.add("result", describe(menu.slots.isEmpty() ? ItemStack.EMPTY : menu.slots.get(0).getItem()));

        return craft;
    }

    private JsonArray buildEffects(LocalPlayer player) {
        JsonArray effects = new JsonArray();
        for (MobEffectInstance active : player.getActiveEffects()) {
            String id = BuiltInRegistries.MOB_EFFECT.getKey(active.getEffect().value()) == null
                    ? active.getEffect().value().getDescriptionId()
                    : BuiltInRegistries.MOB_EFFECT.getKey(active.getEffect().value()).toString();

            JsonObject effect = new JsonObject();
            effect.addProperty("id", id);
            effect.addProperty("name", active.getEffect().value().getDisplayName().getString());
            effect.addProperty("amplifier", active.getAmplifier());
            effect.addProperty("seconds", active.isInfiniteDuration() ? -1 : active.getDuration() / 20);
            effect.addProperty("beneficial", active.getEffect().value().isBeneficial());
            effect.addProperty("icon", EffectIcons.prime(id, active.getEffect()));
            effects.add(effect);
        }
        return effects;
    }

    /**
     * Everything nearby worth a dot on the map.
     *
     * Capped and sorted by distance rather than truncated arbitrarily: a mob farm or an item-strewn
     * death pile can otherwise fill the list with things far away while hiding the creeper next to you.
     */
    private JsonArray buildEntities(LocalPlayer player, ClientLevel level) {
        DualScreenConfig config = DualScreenConfig.get();
        int range = config.entityRange.get();

        AABB box = player.getBoundingBox().inflate(range);
        List<Entity> nearby = level.getEntities(player, box, candidate -> !candidate.isRemoved());

        // kind is worked out once and carried, rather than recomputed when the JSON is written; this
        // runs over every nearby entity every tick, so a doubled instanceof chain is real work
        List<Entity> kept = new ArrayList<>();
        Map<Entity, String> kinds = new java.util.IdentityHashMap<>();

        for (Entity entity : nearby) {
            String kind = classify(entity);
            if (kind == null) {
                continue;
            }
            kinds.put(entity, kind);
            if (!config.showHostiles.get() && "hostile".equals(kind)) {
                continue;
            }
            if (!config.showPassives.get() && "passive".equals(kind)) {
                continue;
            }
            if (!config.showPlayers.get() && "player".equals(kind)) {
                continue;
            }
            if (!config.showItems.get() && "item".equals(kind)) {
                continue;
            }
            kept.add(entity);
        }

        kept.sort(Comparator.comparingDouble(player::distanceToSqr));

        JsonArray entities = new JsonArray();
        int cap = Math.min(kept.size(), config.maxEntities.get());
        int heads = 0;

        for (int i = 0; i < cap; i++) {
            Entity entity = kept.get(i);
            String type = TYPE_IDS.computeIfAbsent(entity.getType(),
                    which -> BuiltInRegistries.ENTITY_TYPE.getKey(which).toString());

            JsonObject dot = new JsonObject();
            dot.addProperty("kind", kinds.get(entity));
            dot.addProperty("name", entity.getDisplayName().getString());
            dot.addProperty("type", type);
            dot.addProperty("x", entity.getX());
            dot.addProperty("z", entity.getZ());
            dot.addProperty("y", entity.getY());

            // cut the head out while we're on the client thread and holding the entity; the page then
            // asks for it by type, once, and the browser caches it
            if (config.showHeads.get()) {
                if (HeadCache.prime(type, entity, heads)) {
                    heads++;
                }
                dot.addProperty("head", HeadCache.get(type) != null);
            }

            entities.add(dot);
        }
        return entities;
    }

    private String classify(Entity entity) {
        if (entity instanceof Player) {
            return "player";
        }
        if (entity instanceof Monster) {
            return "hostile";
        }
        if (entity instanceof Animal) {
            return "passive";
        }
        if (entity instanceof ItemEntity) {
            return "item";
        }
        return null;
    }

    private String worldName(Minecraft client) {
        if (client.hasSingleplayerServer() && client.getSingleplayerServer() != null) {
            return client.getSingleplayerServer().getWorldData().getLevelName();
        }

        ServerData server = client.getCurrentServer();
        if (server != null) {
            return server.name == null || server.name.isBlank() ? server.ip : server.name;
        }
        return "Minecraft";
    }

    private String biome(ClientLevel level, BlockPos where) {
        return level.getBiome(where)
                .unwrapKey()
                .map(key -> key.location().getPath().replace('_', ' '))
                .orElse("unknown");
    }

    /*********
     * Commands
     *********/
    /**
     * Act on whatever the page asked for, on the tick after it asked.
     *
     * Every one of these is gated by config and defaults conservative. Anything that could lose an item
     * or say something in chat is off until the player turns it on — a second screen is a convenience,
     * and a convenience that can empty your inventory by mis-tap is not one.
     */
    private void drain(Minecraft client, LocalPlayer player) {
        Map<String, String> command;
        while ((command = COMMANDS.poll()) != null) {
            String action = command.getOrDefault("do", "");

            try {
                switch (action) {
                    case "hotbar" -> {
                        if (!DualScreenConfig.get().allowHotbarSelect.get()) {
                            break;
                        }
                        int slot = parse(command.get("slot"), -1);
                        if (slot < 0 || slot > 8 || client.getConnection() == null) {
                            break;
                        }
                        player.getInventory().selected = slot;
                        client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
                    }

                    case "drop" -> {
                        if (DualScreenConfig.get().allowDrop.get()) {
                            // LocalPlayer.drop sends the packet itself; false means the held item, not the stack
                            player.drop(false);
                        }
                    }

                    case "recipes" -> {
                        // answered into a field the request thread reads, since the browser must be
                        // built here on the client thread
                        recipeJson = RecipeBrowser.build(client, player,
                                command.get("q"), "1".equals(command.get("craftable")));
                    }

                    case "craft" -> {
                        if (!DualScreenConfig.get().allowCrafting.get()) {
                            break;
                        }
                        placeRecipe(client, player, command.get("id"), "1".equals(command.get("all")));
                    }

                    case "take" -> {
                        if (DualScreenConfig.get().allowCrafting.get()) {
                            takeResult(client, player);
                        }
                    }

                    case "chat" -> {
                        if (!DualScreenConfig.get().allowChat.get() || player.connection == null) {
                            break;
                        }
                        String text = command.getOrDefault("text", "").trim();
                        if (text.isEmpty()) {
                            break;
                        }
                        if (text.startsWith("/")) {
                            player.connection.sendCommand(text.substring(1));
                        } else {
                            player.connection.sendChat(text);
                        }
                    }

                    default -> {
                        // an unknown action is a page/mod version mismatch, not something to crash over
                    }
                }
            } catch (Exception error) {
                AynDualScreen.LOG.warn("[Ayn Dual Screen] Action '{}' failed: {}", action,
                        String.valueOf(error.getMessage()));
            }
        }
    }

    /**
     * Fill an open crafting grid with a recipe.
     *
     * <p>Uses the same packet the vanilla recipe book sends, which is the whole reason this is safe:
     * the server does the moving and applies its own rules, so nothing here can invent items or leave
     * the client's idea of the inventory out of step with the server's.
     *
     * <p>It only works while a crafting screen is actually open — the player's own 2x2 or a table —
     * because there is no grid to place into otherwise.
     */
    private void placeRecipe(Minecraft client, LocalPlayer player, String id, boolean all) {
        if (id == null || client.level == null || player.connection == null) {
            return;
        }

        AbstractContainerMenu menu = player.containerMenu;
        if (!(menu instanceof RecipeBookMenu<?, ?>)) {
            AynDualScreen.LOG.info("[Ayn Dual Screen] Craft ignored: no crafting grid is open.");
            return;
        }

        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return;
        }

        client.level.getRecipeManager().byKey(key).ifPresent(holder ->
                player.connection.send(new ServerboundPlaceRecipePacket(menu.containerId, holder, all)));
    }

    /**
     * Take what's in the result slot, as a shift-click would.
     *
     * Goes through the game's own click handler rather than a hand-built packet: that is what keeps the
     * client's idea of the inventory in step with the server's, sends the right state id, and applies
     * the same rules as clicking the slot yourself. Crafting is the one place where getting this wrong
     * shows up as items appearing and then vanishing a moment later.
     */
    private void takeResult(Minecraft client, LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (client.gameMode == null || menu.slots.isEmpty()) {
            return;
        }
        if (!(menu instanceof InventoryMenu) && !(menu instanceof CraftingMenu)) {
            return;
        }
        if (menu.slots.get(0).getItem().isEmpty()) {
            return;
        }

        // slot 0 is the result in both menus; QUICK_MOVE is the shift-click that fills the inventory
        client.gameMode.handleInventoryMouseClick(
                menu.containerId, 0, 0, ClickType.QUICK_MOVE, player);
    }

    private static int parse(String text, int fallback) {
        try {
            return text == null ? fallback : Integer.parseInt(text.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
