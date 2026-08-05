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

        /// <summary>Whether monsters appear on the minimap.</summary>
        public bool ShowMonsters { get; set; } = true;

        /// <summary>Whether villagers appear on the minimap.</summary>
        public bool ShowNpcs { get; set; } = true;

        /// <summary>Whether farm animals appear on the minimap.</summary>
        public bool ShowAnimals { get; set; } = true;

        /// <summary>How many entries the journal panel sends. Zero hides it entirely.</summary>
        public int MaxQuests { get; set; } = 6;

        /// <summary>How many villagers the tracker sends. Nearest and birthdays come first.</summary>
        public int MaxVillagers { get; set; } = 40;
    }
}
