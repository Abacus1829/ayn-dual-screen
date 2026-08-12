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

        /// <summary>The bundles in this room that are still outstanding.</summary>
        public List<string> Remaining { get; set; }
    }

    /// <summary>An entry from the journal, reduced to what fits on a bottom screen.</summary>
    internal sealed class QuestDto
    {
        public string Name { get; set; }
        public string Objective { get; set; }

        /// <summary>Days left before it expires, or -1 when it never does.</summary>
        public int DaysLeft { get; set; }
        public bool Complete { get; set; }
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
    internal sealed class ActionDto
    {
        public string Type { get; set; }
        public int Index { get; set; } = -1;
        public int To { get; set; } = -1;
    }
}
