package com.abacus.dualscreen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What you can make, and what you're short of.
 *
 * Reading recipes is the safe half of crafting: the client already knows every recipe, so listing them
 * and checking them against the inventory costs nothing and can't desync anything. Actually filling the
 * grid is the other half, and that goes through the same packet the vanilla recipe book uses rather
 * than by moving items around by hand — see {@link DualScreenClient}.
 */
public final class RecipeBrowser {

    /** Recipes returned in one response. Enough to browse, small enough to stay a quick request. */
    private static final int LIMIT = 120;

    private RecipeBrowser() {
    }

    /**
     * Build the recipe list.
     *
     * <p>Client thread only: it walks the recipe manager and the player's inventory.
     */
    public static String build(Minecraft client, LocalPlayer player, String query, boolean onlyCraftable) {
        JsonObject root = new JsonObject();
        JsonArray list = new JsonArray();

        if (client.level == null) {
            root.addProperty("ready", false);
            root.add("recipes", list);
            return root.toString();
        }

        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<ItemStack> pouch = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                pouch.add(stack);
            }
        }

        int found = 0;
        for (RecipeHolder<?> holder : client.level.getRecipeManager().getRecipes()) {
            if (found >= LIMIT) {
                break;
            }
            if (!(holder.value() instanceof CraftingRecipe recipe)) {
                continue;
            }

            ItemStack result;
            try {
                result = recipe.getResultItem(client.level.registryAccess());
            } catch (Exception error) {
                continue;
            }
            if (result == null || result.isEmpty()) {
                continue;
            }

            String name = result.getHoverName().getString();
            if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }

            boolean craftable = canMake(recipe, pouch);
            if (onlyCraftable && !craftable) {
                continue;
            }

            JsonObject entry = new JsonObject();
            entry.addProperty("id", holder.id().toString());
            entry.addProperty("name", name);
            entry.addProperty("item", BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
            entry.addProperty("count", result.getCount());
            entry.addProperty("craftable", craftable);

            // shaped recipes carry their own layout; shapeless ones are just a bag of ingredients
            int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 0;
            int height = recipe instanceof ShapedRecipe shaped ? shaped.getHeight() : 0;
            entry.addProperty("width", width);
            entry.addProperty("height", height);

            JsonArray grid = new JsonArray();
            for (Ingredient ingredient : recipe.getIngredients()) {
                grid.add(display(ingredient));
            }
            entry.add("grid", grid);

            // resolving art here means the page can show the result with its real icon
            if (IconCache.known(entry.get("item").getAsString())) {
                entry.addProperty("icon", IconCache.get(entry.get("item").getAsString()) != null);
            } else {
                entry.addProperty("icon", false);
            }

            list.add(entry);
            found++;
        }

        root.addProperty("ready", true);
        root.addProperty("count", found);
        root.add("recipes", list);
        return root.toString();
    }

    /** The item to show for an ingredient. An ingredient can accept several; the first one stands in. */
    private static String display(Ingredient ingredient) {
        try {
            ItemStack[] options = ingredient.getItems();
            if (options.length == 0) {
                return "";
            }
            return BuiltInRegistries.ITEM.getKey(options[0].getItem()).toString();
        } catch (Exception error) {
            return "";
        }
    }

    /**
     * Whether the inventory covers every ingredient.
     *
     * Deliberately approximate: it checks each ingredient can be satisfied by something you hold, not
     * that quantities line up across a whole shaped grid. That is the same answer the recipe book's
     * highlight gives, and it is the question worth answering from across the room.
     */
    private static boolean canMake(CraftingRecipe recipe, List<ItemStack> pouch) {
        try {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }

                boolean have = false;
                for (ItemStack stack : pouch) {
                    if (ingredient.test(stack)) {
                        have = true;
                        break;
                    }
                }
                if (!have) {
                    return false;
                }
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }
}
