namespace AynDualScreen
{
    /// <summary>Settings written to <c>config.json</c> the first time the mod runs.</summary>
    internal sealed class ModConfig
    {
        /// <summary>The TCP port the second-screen server listens on.</summary>
        public int Port { get; set; } = 27301;

        /// <summary>Whether to accept connections from other devices on the network. When false the server is only reachable from this machine.</summary>
        /// <remarks>
        /// On by default, because a handheld as the second screen is the point of the mod and loopback-only
        /// makes that impossible. The trade-off is real and worth knowing: anyone on the same network can
        /// open the page and move or destroy your items. Set this to false on a network you don't trust, or
        /// set <see cref="AllowTrash"/> to false to at least take away the destructive button.
        /// </remarks>
        public bool AllowLanAccess { get; set; } = true;

        /// <summary>How many times per second the game state snapshot is refreshed (1-60).</summary>
        public int UpdatesPerSecond { get; set; } = 10;

        /// <summary>Whether to extract item sprites from the game and serve them to the second screen.</summary>
        public bool EnableItemIcons { get; set; } = true;

        /// <summary>Whether the second screen is allowed to destroy items via the trash button.</summary>
        public bool AllowTrash { get; set; } = true;

        /// <summary>Whether the screen may throw items on the ground.</summary>
        public bool AllowDrop { get; set; } = true;

        /// <summary>Whether the screen may rearrange the inventory: dragging between slots, and Sort.</summary>
        public bool AllowInventoryEdit { get; set; } = true;

        /// <summary>Whether the screen may make the player eat or drink the selected item.</summary>
        public bool AllowEat { get; set; } = true;

        /// <summary>Whether holding a hotbar slot on the second screen uses what is in it.</summary>
        /// <remarks>
        /// Separate from <see cref="AllowEat"/> because they are different risks: eating the wrong
        /// thing costs one item, while swinging a pickaxe acts on the world. Anyone who wants the
        /// panel to be strictly a readout turns this off and keeps the rest.
        /// </remarks>
        public bool AllowUse { get; set; } = true;

        /// <summary>Whether the game's own world map is offered as an alternative to the tile minimap.</summary>
        /// <remarks>Turning this off also stops the per-character world-map lookups each snapshot.</remarks>
        public bool EnableWorldMap { get; set; } = true;

        /// <summary>Whether an open chest is mirrored onto the second screen.</summary>
        public bool ShowChests { get; set; } = true;

        /// <summary>Whether monsters appear on the minimap.</summary>
        public bool ShowMonsters { get; set; } = true;

        /// <summary>Whether villagers appear on the minimap.</summary>
        public bool ShowNpcs { get; set; } = true;

        /// <summary>Whether farm animals appear on the minimap.</summary>
        public bool ShowAnimals { get; set; } = true;

        /// <summary>Whether the optional game-codes endpoints are served at all.</summary>
        /// <remarks>
        /// Off by default and deliberately so. With this false the mod serves no <c>/codes</c> and no
        /// <c>/code</c>, advertises nothing, and rejects any request to either -- it is the dashboard
        /// it always was, entirely local, with nothing that can alter a save. Turning it on is a
        /// decision, not an upgrade that happened to you.
        ///
        /// Nothing else in the mod reads this. Telemetry, the map, the inventory panel and every
        /// existing action work identically either way.
        /// </remarks>
        public bool EnableGameCodes { get; set; } = false;

        /// <summary>How many entries the journal panel sends. Zero hides it entirely.</summary>
        /// <remarks>
        /// Six was the number that fitted when the journal was four lines beside the map. It is its own
        /// page now, and it scrolls, so the cap is only here to stop a runaway quest mod from making
        /// every snapshot enormous.
        /// </remarks>
        public int MaxQuests { get; set; } = 20;

        /// <summary>How many villagers the tracker sends. Nearest and birthdays come first.</summary>
        public int MaxVillagers { get; set; } = 40;

        /// <summary>
        /// How many entries each Farm list sends: machines, animals and fruit trees separately.
        /// </summary>
        /// <remarks>
        /// A cap rather than everything, because a late-game farm can hold several hundred kegs and
        /// nobody scrolls through that on a handheld. Ready machines are sorted to the top, so the cap
        /// removes the least interesting rows first.
        /// </remarks>
        public int MaxFarmEntries { get; set; } = 60;

        /// <summary>Send villager face icons for the map and the calendar's birthdays.</summary>
        public bool EnableNpcIcons { get; set; } = true;
    }
}
