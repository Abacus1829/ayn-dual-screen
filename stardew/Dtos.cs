using System.Collections.Generic;

namespace AynDualScreen
{
    /// <summary>A snapshot of everything the second screen redraws continuously.</summary>
    internal sealed class StateDto
    {
        public bool Ready { get; set; }
        public long Tick { get; set; }

        public string LocationId { get; set; }
        public string LocationName { get; set; }
        public int MapRev { get; set; }

        /// <summary>Brightness (0-255) of the game's menu box, so the page can pick readable ink. -1 if unknown.</summary>
        public int MenuLuma { get; set; } = -1;

        /// <summary>Where the player sits on the game's world map, or null if this location isn't mapped.</summary>
        public WorldPosDto World { get; set; }

        /// <summary>Bumped when the world map region changes, so the client refetches the image.</summary>
        public int WorldRev { get; set; }

        /// <summary>The open container, or null when none is open.</summary>
        public ChestDto Chest { get; set; }

        public int TimeOfDay { get; set; }
        public int DayOfMonth { get; set; }
        public string DayOfWeek { get; set; }
        public string Season { get; set; }
        public int Year { get; set; }
        public string Weather { get; set; }

        public int Money { get; set; }
        public float Stamina { get; set; }
        public int MaxStamina { get; set; }
        public int Health { get; set; }
        public int MaxHealth { get; set; }

        public float X { get; set; }
        public float Y { get; set; }
        public int Facing { get; set; }

        public int SelectedSlot { get; set; }
        public int HotbarSize { get; set; }

        /// <summary>How many 12-slot rows the backpack has been upgraded to: 1, 2 or 3.</summary>
        /// <remarks>
        /// The Today page shows every unlocked row and rotates them through the hotbar with L/R, so it
        /// needs to know how many are real. Derived from the player's item capacity rather than from a
        /// purchase flag, because that is what actually governs the slots.
        /// </remarks>
        public int BackpackRows { get; set; } = 1;

        /// <summary>What is in the shipping bin right now.</summary>
        public ShippingDto Shipping { get; set; }

        /// <summary>Tomorrow's forecast, so the evening's planning can happen on the second screen.</summary>
        public string WeatherTomorrow { get; set; }

        /// <summary>The day's luck, as the Fortune Teller reports it: roughly -0.1 to +0.1.</summary>
        public double DailyLuck { get; set; }

        /// <summary>Whose birthday it is today.</summary>
        public List<string> Birthdays { get; set; }

        /// <summary>Today's festival, or null.</summary>
        public string Festival { get; set; }

        /// <summary>Whether the Travelling Cart is in the forest today.</summary>
        public bool CartToday { get; set; }

        /// <summary>What the config lets this screen do, so the UI can grey out what it can't.</summary>
        public PermissionsDto Can { get; set; }

        public List<SlotDto> Inventory { get; set; }
        public List<EntityDto> Entities { get; set; }
        public List<QuestDto> Quests { get; set; }
        public SkillsDto Skills { get; set; }
    }

    /// <summary>
    /// Which actions the mod will actually accept.
    /// </summary>
    /// <remarks>
    /// Sent so a button that has been switched off in the config can be greyed out instead of looking
    /// broken — a tap that silently does nothing is worse than no button.
    /// </remarks>
    internal sealed class PermissionsDto
    {
        public bool Trash { get; set; }
        public bool Drop { get; set; }
        public bool Edit { get; set; }
        public bool Eat { get; set; }

        public bool Use { get; set; }
    }

    /// <summary>One villager: where they are and how you're getting on.</summary>
    internal sealed class VillagerDto
    {
        public string Name { get; set; }
        public string Location { get; set; }
        public float X { get; set; }
        public float Y { get; set; }
        public int Hearts { get; set; }
        public int MaxHearts { get; set; }
        public bool Birthday { get; set; }

        /// <summary>Whether you've already spoken to them today.</summary>
        public bool Talked { get; set; }

        /// <summary>Whether they're in the location you're standing in.</summary>
        public bool Here { get; set; }
    }

    /// <summary>The community centre bundle board.</summary>
    internal sealed class CommunityDto
    {
        /// <summary>False when there's no centre to report on — not found yet, or the Joja route was taken.</summary>
        public bool Available { get; set; }

        public bool Complete { get; set; }
        public int BundlesDone { get; set; }
        public int BundlesTotal { get; set; }
        public List<CommunityRoomDto> Rooms { get; set; }
    }

    internal sealed class CommunityRoomDto
    {
        public string Name { get; set; }
        public bool Complete { get; set; }
        public int Done { get; set; }
        public int Total { get; set; }

        /// <summary>Every bundle in this room, finished ones included, in the board's own order.</summary>
        public List<BundleDto> Bundles { get; set; }
    }

    /// <summary>One bundle, and what it is still short of.</summary>
    internal sealed class BundleDto
    {
        public string Name { get; set; }
        public bool Complete { get; set; }

        /// <summary>Slots already filled, and how many the bundle wants.</summary>
        /// <remarks>
        /// Not always the same as the ingredient count: several bundles are "any five of these nine",
        /// and reporting nine would make a finishable bundle look impossible.
        /// </remarks>
        public int Have { get; set; }

        public int Need { get; set; }

        /// <summary>The ingredients whose slots are still empty.</summary>
        public List<BundleItemDto> Missing { get; set; }
    }

    /// <summary>One ingredient a bundle is still waiting for.</summary>
    internal sealed class BundleItemDto
    {
        public string Name { get; set; }
        public int Count { get; set; }

        /// <summary>0 normal, 1 silver, 2 gold, 4 iridium — the bundle's own requirement.</summary>
        public int Quality { get; set; }

        public string IconKey { get; set; }
    }

    /// <summary>An entry from the journal, reduced to what fits on a bottom screen.</summary>
    internal sealed class QuestDto
    {
        public string Name { get; set; }
        public string Objective { get; set; }

        /// <summary>Days left before it expires, or -1 when it never does.</summary>
        public int DaysLeft { get; set; }
        public bool Complete { get; set; }

        /// <summary>Gold on completion, or 0 when the reward is not money.</summary>
        public int RewardGold { get; set; }

        /// <summary>The reward in words, when there is one worth naming.</summary>
        public string Reward { get; set; }

        /// <summary>
        /// Whether Stardew itself permits cancelling this one.
        /// </summary>
        /// <remarks>
        /// Story quests are not cancellable and the game hides the button for them. The client is told
        /// rather than guessing, because a Cancel that silently does nothing is worse than no Cancel.
        /// </remarks>
        public bool Cancellable { get; set; }

        /// <summary>The quest's id, which is what a cancel has to name.</summary>
        public int Id { get; set; } = -1;
    }

    /// <summary>One inventory slot. <see cref="Name"/> is null for an empty slot.</summary>
    internal sealed class SlotDto
    {
        public int Index { get; set; }
        public string Name { get; set; }
        public int Stack { get; set; }
        public int Quality { get; set; }
        public string Category { get; set; }
        public string IconKey { get; set; }
        public bool Edible { get; set; }

        /// <summary>Water left in a watering can, and its capacity. Both -1 for anything else.</summary>
        /// <remarks>Sent as a pair so the client can draw a bar without knowing which cans hold what.</remarks>
        public int Water { get; set; } = -1;
        public int WaterMax { get; set; } = -1;

        /// <summary>A weapon's special-action cooldown: milliseconds remaining, and the full length.</summary>
        public int Cooldown { get; set; } = -1;
        public int CooldownMax { get; set; } = -1;
    }

    /// <summary>Something to draw on the minimap that moves.</summary>
    internal sealed class EntityDto
    {
        public string Kind { get; set; }
        public string Name { get; set; }
        public float X { get; set; }
        public float Y { get; set; }

        /// <summary>Key for <c>/npc/{key}.png</c>, or null if this entity has no face to draw.</summary>
        public string IconKey { get; set; }

        /// <summary>Position in world-map pixels, or null if this spot isn't on the world map.</summary>
        public float? Wx { get; set; }
        public float? Wy { get; set; }
    }

    /// <summary>Where the player is on the game's own world map, in that map's pixel space.</summary>
    internal sealed class WorldPosDto
    {
        public string Region { get; set; }
        public float X { get; set; }
        public float Y { get; set; }
    }

    /// <summary>The world map image the game itself draws, and the pixel rectangle it covers.</summary>
    internal sealed class WorldMapDto
    {
        public bool Available { get; set; }
        public string Region { get; set; }

        /// <summary>Bumped on every rebuild. Goes in the image URL, because the region id alone doesn't change when only the artwork does.</summary>
        public int Rev { get; set; }

        /// <summary>Offset of the region within map-pixel space; entity positions are relative to this.</summary>
        public int X { get; set; }
        public int Y { get; set; }
        public int Width { get; set; }
        public int Height { get; set; }
    }

    /// <summary>A container the player has open, mirrored onto the second screen.</summary>
    internal sealed class ChestDto
    {
        public bool Open { get; set; }
        public string Name { get; set; }

        /// <summary>Whether items may be moved. False for containers this mod won't risk touching.</summary>
        public bool CanEdit { get; set; }

        public List<SlotDto> Items { get; set; }
    }

    internal sealed class SkillsDto
    {
        public int Farming { get; set; }
        public int Mining { get; set; }
        public int Foraging { get; set; }
        public int Fishing { get; set; }
        public int Combat { get; set; }
    }

    /// <summary>The current location's tile grid. Rebuilt rarely and cached by the client until <see cref="Rev"/> changes.</summary>
    internal sealed class MapDto
    {
        public int Rev { get; set; }
        public string LocationId { get; set; }
        public string LocationName { get; set; }
        public int Width { get; set; }
        public int Height { get; set; }

        /// <summary>One string per row, one character per tile. See <c>TileCode</c> for the alphabet.</summary>
        public List<string> Rows { get; set; }

        public List<WarpDto> Warps { get; set; }
    }

    internal sealed class WarpDto
    {
        public int X { get; set; }
        public int Y { get; set; }
        public string Target { get; set; }
    }

    /// <summary>A command sent from the second screen's touch UI.</summary>
    /// <summary>A game code the second screen asked to run.</summary>
    /// <remarks>Its own type rather than another ActionDto case, so the two paths cannot be confused and the code path can be removed wholesale.</remarks>
    internal sealed class CodeDto
    {
        public string Code { get; set; }
        public string Command { get; set; }
        public string Value { get; set; }
    }

    internal sealed class ActionDto
    {
        public string Type { get; set; }
        public int Index { get; set; } = -1;
        public int To { get; set; } = -1;
    }
/// <summary>What has gone into the shipping bin today.</summary>
    /// <remarks>
    /// Value is what the bin is currently worth, which is not the same as tonight's income: the game
    /// applies profit margins and the Gatherer-style perks at sale time. It is reported as "what is in
    /// the box" rather than as a prediction, because a number that turns out wrong at 2am is worse
    /// than no number.
    /// </remarks>
    internal sealed class ShippingDto
    {
        public int Count { get; set; }
        public int Value { get; set; }
        public List<SlotDto> Items { get; set; }
    }

    /// <summary>One machine on the farm, and whether it has anything waiting.</summary>
    internal sealed class MachineDto
    {
        public string Name { get; set; }
        public string Location { get; set; }

        /// <summary>What it is making, or null when it is idle.</summary>
        public string Produce { get; set; }
        public string IconKey { get; set; }

        /// <summary>True when the item can be collected now.</summary>
        public bool Ready { get; set; }

        /// <summary>Minutes of game time left, or -1 when idle or unknown.</summary>
        public int MinutesLeft { get; set; } = -1;
    }

    /// <summary>A farm animal, and what it is offering today.</summary>
    internal sealed class AnimalDto
    {
        public string Name { get; set; }
        public string Type { get; set; }
        public string Building { get; set; }

        /// <summary>Null when there is nothing to collect. No placeholder is sent — see the client.</summary>
        public string Produce { get; set; }
        public string IconKey { get; set; }

        public int Friendship { get; set; }
        public bool Pet { get; set; }
        public bool Fed { get; set; }
    }

    /// <summary>A fruit tree, wherever it is planted.</summary>
    internal sealed class FruitTreeDto
    {
        public string Name { get; set; }
        public string Location { get; set; }
        public string IconKey { get; set; }

        /// <summary>How many fruit are waiting on it.</summary>
        public int Fruit { get; set; }

        /// <summary>Days until it bears, or 0 once it is mature.</summary>
        public int DaysToMature { get; set; }
    }

    /// <summary>Everything the Farm page shows, in one fetch.</summary>
    internal sealed class FarmDto
    {
        public List<MachineDto> Machines { get; set; }
        public List<AnimalDto> Animals { get; set; }
        public List<FruitTreeDto> Trees { get; set; }

        /// <summary>The counts the page puts in its summary strip, so the client counts nothing itself.</summary>
        public int MachinesReady { get; set; }
        public int AnimalsUnpetted { get; set; }
        public int ProduceWaiting { get; set; }
        public int FruitWaiting { get; set; }
    }

    /// <summary>One square of the season calendar.</summary>
    internal sealed class CalendarDayDto
    {
        public int Day { get; set; }

        /// <summary>Villagers with a birthday on this day, in the order the calendar shows them.</summary>
        public List<string> Birthdays { get; set; }

        /// <summary>Icon keys for those villagers, so the page can draw portraits rather than initials.</summary>
        public List<string> Portraits { get; set; }

        public string Festival { get; set; }
        public bool Cart { get; set; }
        public bool Today { get; set; }
        public bool Past { get; set; }
    }

    /// <summary>A season, as the in-game calendar lays it out: four weeks of seven days.</summary>
    internal sealed class CalendarDto
    {
        public string Season { get; set; }
        public int Year { get; set; }
        public int Today { get; set; }

        /// <summary>Always 28 entries. The grid is fixed, so the client never has to work out a shape.</summary>
        public List<CalendarDayDto> Days { get; set; }
    }
}