using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.Xna.Framework;
using StardewValley;
using StardewValley.Buildings;
using StardewValley.GameData.FruitTrees;
using StardewValley.TerrainFeatures;
using SObject = StardewValley.Object;

namespace AynDualScreen
{
    /// <summary>
    /// The farm, as three lists: what is cooking, what needs petting, and what is ripening.
    /// </summary>
    /// <remarks>
    /// These are the three questions a second screen is genuinely good at answering, because each one
    /// otherwise costs a walk: which kegs are done, which animals have not been petted, which trees
    /// have fruit on them. None of it is guessed — every entry is read from the live save, so a
    /// content mod that adds a machine or an animal appears here without this file knowing about it.
    ///
    /// Everything here touches game state and must run on the game thread. It is rebuilt on the same
    /// slow timer as the villager and bundle panels rather than per snapshot: walking every location
    /// on a large farm is not something to do ten times a second.
    /// </remarks>
    internal static class FarmPanel
    {
        /// <summary>Machines whose "produce" is really storage, and which would drown the list.</summary>
        /// <remarks>
        /// A chest is not a machine and a fish pond is reported separately by the game. Excluded by
        /// behaviour rather than by name where possible, so this list stays short.
        /// </remarks>
        private static readonly HashSet<string> NotMachines = new(StringComparer.OrdinalIgnoreCase)
        {
            "Chest", "Stone Chest", "Junimo Chest", "Mini-Fridge", "Mini-Shipping Bin", "Workbench"
        };

        /// <summary>Everything the Farm page draws.</summary>
        /// <param name="iconFor">Resolves an item to a cached icon key, or null when icons are off.</param>
        public static FarmDto Build(Func<Item, string> iconFor, int maxPerList)
        {
            var farm = new FarmDto
            {
                Machines = new List<MachineDto>(),
                Animals = new List<AnimalDto>(),
                Trees = new List<FruitTreeDto>()
            };

            try
            {
                CollectMachinesAndTrees(farm, iconFor, maxPerList);
                CollectAnimals(farm, iconFor, maxPerList);
            }
            catch (Exception)
            {
                // A partial farm is worth more than none: whatever was gathered before the fault is
                // still returned, and the next rebuild tries again.
            }

            farm.MachinesReady = farm.Machines.Count(machine => machine.Ready);
            farm.AnimalsUnpetted = farm.Animals.Count(animal => !animal.Pet);
            farm.ProduceWaiting = farm.Animals.Count(animal => animal.Produce != null);
            farm.FruitWaiting = farm.Trees.Sum(tree => tree.Fruit);

            // Ready things first — that is the only reason to look at this page mid-day.
            farm.Machines.Sort((a, b) =>
            {
                if (a.Ready != b.Ready)
                    return a.Ready ? -1 : 1;
                return string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase);
            });

            return farm;
        }

        /// <summary>Walk every location once, picking up machines and fruit trees on the way.</summary>
        /// <remarks>
        /// One pass rather than two: the expensive part is enumerating locations and their objects, not
        /// the test applied to each. Greenhouse and Ginger Island trees count — they are still fruit
        /// somebody has to collect.
        /// </remarks>
        private static void CollectMachinesAndTrees(FarmDto farm, Func<Item, string> iconFor, int max)
        {
            foreach (GameLocation location in Game1.locations)
            {
                if (location == null)
                    continue;

                string where = location.DisplayName ?? location.Name ?? "";

                foreach (KeyValuePair<Vector2, SObject> pair in location.objects.Pairs)
                {
                    if (farm.Machines.Count >= max)
                        break;

                    SObject machine = pair.Value;
                    if (machine == null || NotMachines.Contains(machine.Name))
                        continue;

                    // What makes something a machine here is that it *processes*: it either holds a
                    // held object or is mid-cycle. That covers kegs, casks, furnaces, bee houses,
                    // crab pots and anything a content mod adds, without naming any of them.
                    Item held = machine.heldObject.Value;
                    bool working = machine.MinutesUntilReady > 0;
                    if (held == null && !working)
                        continue;

                    farm.Machines.Add(new MachineDto
                    {
                        Name = machine.DisplayName ?? machine.Name,
                        Location = where,
                        Produce = held?.DisplayName,
                        IconKey = held != null ? iconFor(held) : null,
                        Ready = held != null && machine.readyForHarvest.Value,
                        MinutesLeft = working ? machine.MinutesUntilReady : -1
                    });
                }

                foreach (KeyValuePair<Vector2, TerrainFeature> pair in location.terrainFeatures.Pairs)
                {
                    if (farm.Trees.Count >= max)
                        break;

                    if (pair.Value is not FruitTree tree)
                        continue;

                    farm.Trees.Add(BuildTree(tree, where, iconFor));
                }
            }
        }

        private static FruitTreeDto BuildTree(FruitTree tree, string where, Func<Item, string> iconFor)
        {
            var dto = new FruitTreeDto
            {
                Location = where,
                Fruit = tree.fruit?.Count ?? 0,
                DaysToMature = Math.Max(0, tree.daysUntilMature.Value)
            };

            // The name comes from the fruit it bears, because "Fruit Tree" on eight rows tells nobody
            // anything. Falls back to the generic name if the data cannot be resolved.
            try
            {
                Item sample = tree.fruit?.FirstOrDefault();
                if (sample != null)
                {
                    dto.Name = sample.DisplayName;
                    dto.IconKey = iconFor(sample);
                }
                else if (tree.GetData() is FruitTreeData data && data.Fruit?.Count > 0)
                {
                    Item grown = ItemRegistry.Create(data.Fruit[0].ItemId, allowNull: true);
                    dto.Name = grown?.DisplayName;
                    dto.IconKey = grown != null ? iconFor(grown) : null;
                }
            }
            catch (Exception)
            {
                // best effort; the row still shows with its days-to-mature
            }

            dto.Name ??= "Fruit tree";
            return dto;
        }

        /// <summary>Every animal in every building, and whether it still owes you something today.</summary>
        private static void CollectAnimals(FarmDto farm, Func<Item, string> iconFor, int max)
        {
            foreach (GameLocation location in Game1.locations)
            {
                if (location == null)
                    continue;

                foreach (FarmAnimal animal in AnimalsIn(location))
                {
                    if (farm.Animals.Count >= max)
                        return;

                    if (animal == null)
                        continue;

                    var dto = new AnimalDto
                    {
                        Name = animal.displayName ?? animal.Name,
                        Type = animal.displayType ?? animal.type.Value,
                        Building = animal.home?.buildingType.Value ?? location.DisplayName ?? location.Name,
                        Friendship = animal.friendshipTowardFarmer.Value,
                        Pet = animal.wasPet.Value,
                        Fed = animal.fullness.Value > 0
                    };

                    /*
                     * No placeholder icon for an animal with nothing to give.
                     *
                     * A row of grey question marks down the list reads as "something is broken" rather
                     * than as "this cow has already been milked today", so the produce fields simply
                     * stay null and the client leaves the space empty.
                     */
                    try
                    {
                        string produceId = animal.currentProduce.Value;
                        if (!string.IsNullOrEmpty(produceId))
                        {
                            Item produce = ItemRegistry.Create(produceId, allowNull: true);
                            if (produce != null)
                            {
                                dto.Produce = produce.DisplayName;
                                dto.IconKey = iconFor(produce);
                            }
                        }
                    }
                    catch (Exception)
                    {
                        // leave produce null, which is the honest answer
                    }

                    farm.Animals.Add(dto);
                }
            }
        }

        /// <summary>The animals a location holds, whether it is a barn interior or the farm itself.</summary>
        private static IEnumerable<FarmAnimal> AnimalsIn(GameLocation location)
        {
            if (location is AnimalHouse house)
                return house.animals.Values;

            if (location is StardewValley.Farm outside)
                return outside.animals.Values;

            return Array.Empty<FarmAnimal>();
        }
    }
}
