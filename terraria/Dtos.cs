using System.Collections.Generic;

namespace AynDualScreen
{
	/// <summary>A snapshot of everything the second screen redraws continuously.</summary>
	internal sealed class StateDto
	{
		public bool Ready { get; set; }
		public long Tick { get; set; }

		public string WorldName { get; set; }
		public int WorldWidth { get; set; }
		public int WorldHeight { get; set; }
		public bool HardMode { get; set; }
		public string Difficulty { get; set; }

		/// <summary>Minutes since midnight, 0-1440. The client formats the clock.</summary>
		public float TimeMinutes { get; set; }
		public bool DayTime { get; set; }
		public int MoonPhase { get; set; }

		/// <summary>Weather and world events worth a line in the HUD, most notable first.</summary>
		public List<string> Events { get; set; }

		public int Life { get; set; }
		public int LifeMax { get; set; }
		public int Mana { get; set; }
		public int ManaMax { get; set; }
		public int Defense { get; set; }
		public int Breath { get; set; }
		public int BreathMax { get; set; }

		/// <summary>Player position in tile coordinates.</summary>
		public float X { get; set; }
		public float Y { get; set; }
		public int Direction { get; set; }

		/// <summary>PvP team index: 0 none, then red, green, blue, yellow, pink.</summary>
		public int Team { get; set; }

		/// <summary>The team's colour as "#rrggbb", taken from the game rather than guessed at.</summary>
		public string TeamColor { get; set; }

		/// <summary>Depth in feet, matching the vanilla depth meter: negative above sea level.</summary>
		public int DepthFeet { get; set; }
		public string Layer { get; set; }
		public string Biome { get; set; }

		/// <summary>Total wealth in copper, across the inventory and the coin slots.</summary>
		public long Coins { get; set; }

		public int SelectedSlot { get; set; }
		public int HotbarSize { get; set; }
		public int MapRev { get; set; }

		/// <summary>Total stack of everything on hand that restores life, and the same for mana.</summary>
		public int HealingItems { get; set; }
		public int ManaItems { get; set; }

		/// <summary>Seconds of Potion Sickness left, or 0 when a healing potion can be used now.</summary>
		public int PotionCooldown { get; set; }

		/// <summary>What the config lets this screen do, so the UI can grey out what it can't.</summary>
		public PermissionsDto Can { get; set; }

		public List<SlotDto> Inventory { get; set; }
		public List<EquipDto> Equipment { get; set; }
		public List<BuffDto> Buffs { get; set; }
		public List<EntityDto> Entities { get; set; }
		public BossDto Boss { get; set; }
	}

	/// <summary>One inventory slot. <see cref="Name"/> is null for an empty slot.</summary>
	internal sealed class SlotDto
	{
		public int Index { get; set; }
		public string Name { get; set; }
		public int Stack { get; set; }
		public int Rare { get; set; }
		public string IconKey { get; set; }

		/// <summary>A short "damage 24 · 8% crit"-style line, built from whichever stats the item actually has.</summary>
		public string Meta { get; set; }

		/// <summary>Whether the item is one <c>QuickHeal</c>/<c>QuickBuff</c> would reach for; drives the action buttons.</summary>
		public bool Healing { get; set; }
		public bool Buffing { get; set; }
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
		public bool QuickUse { get; set; }
	}

	/// <summary>One equipped item: armour or an accessory. <see cref="Name"/> is null for an empty slot.</summary>
	internal sealed class EquipDto
	{
		public int Index { get; set; }

		/// <summary>"Helmet", "Chest", "Legs" or "Accessory" — what the slot is for, shown when it's empty.</summary>
		public string Slot { get; set; }

		public string Name { get; set; }
		public int Rare { get; set; }
		public int Defense { get; set; }
		public string IconKey { get; set; }
	}

	internal sealed class BuffDto
	{
		public int Type { get; set; }
		public string Name { get; set; }

		/// <summary>Remaining seconds, or -1 for a buff with no timer.</summary>
		public int Seconds { get; set; }
		public string IconKey { get; set; }
	}

	/// <summary>Something to draw on the minimap that moves. Coordinates are world tiles.</summary>
	internal sealed class EntityDto
	{
		public string Kind { get; set; }
		public string Name { get; set; }
		public float X { get; set; }
		public float Y { get; set; }

		/// <summary>Which way a player entity is facing, so its arrow can point the same way. 0 for anything else.</summary>
		public int Direction { get; set; }

		/// <summary>PvP team index for a player entity, or -1 when it isn't a player.</summary>
		public int Team { get; set; }

		/// <summary>
		/// The team's colour as "#rrggbb", read from the game so it matches what the player sees in-game.
		/// Null when the entity has no team.
		/// </summary>
		public string TeamColor { get; set; }
	}

	internal sealed class BossDto
	{
		public string Name { get; set; }
		public int Life { get; set; }
		public int LifeMax { get; set; }
	}

	/// <summary>
	/// The rendered minimap. Sent as its own payload rather than as a tile grid: a Terraria world is far too large to
	/// ship as text, and the game already keeps a per-tile explored colour in <c>Main.Map</c>.
	/// </summary>
	internal sealed class MinimapDto
	{
		public int Rev { get; set; }

		/// <summary>"local" (a window around the player) or "world" (the whole world, downsampled).</summary>
		public string Mode { get; set; }

		/// <summary>Top-left corner of the image in world tiles.</summary>
		public int OriginX { get; set; }
		public int OriginY { get; set; }

		/// <summary>Image size in pixels.</summary>
		public int Width { get; set; }
		public int Height { get; set; }

		/// <summary>World tiles per image pixel. 1 in local mode, more in world mode.</summary>
		public int Step { get; set; }

		/// <summary>The image itself, as a <c>data:image/png;base64,…</c> URI.</summary>
		public string Png { get; set; }
	}

	/// <summary>
	/// The NPC the player is currently talking to, and their shop if one is open.
	/// </summary>
	/// <remarks>
	/// Null whenever no conversation is happening, which is how the screen knows to go back to the map.
	/// </remarks>
	internal sealed class TalkDto
	{
		public string Name { get; set; }

		/// <summary>The NPC's own sprite, first frame, as an icon key.</summary>
		public string ArtKey { get; set; }

		/// <summary>What they're saying, if the game has a line for them right now.</summary>
		public string Dialogue { get; set; }

		/// <summary>True when a shop window is open on the PC.</summary>
		public bool ShopOpen { get; set; }

		/// <summary>Whether the config permits buying from the second screen.</summary>
		public bool CanBuy { get; set; }

		public List<ShopItemDto> Shop { get; set; }
	}

	internal sealed class ShopItemDto
	{
		/// <summary>Index into the open shop, which is what a buy command refers to.</summary>
		public int Slot { get; set; }

		public string Name { get; set; }
		public int Stack { get; set; }
		public int Rare { get; set; }
		public string IconKey { get; set; }

		/// <summary>Price in copper, and the same value spelled out in coins.</summary>
		public int Price { get; set; }
		public string PriceText { get; set; }

		/// <summary>Whether the player can currently afford it.</summary>
		public bool Affordable { get; set; }
	}

	/// <summary>World progression: which bosses and events this world has seen off.</summary>
	internal sealed class ProgressDto
	{
		public List<ProgressEntryDto> Bosses { get; set; }
		public List<ProgressEntryDto> Events { get; set; }

		public int BossesDone { get; set; }
		public int BossesTotal { get; set; }
		public bool HardMode { get; set; }
	}

	internal sealed class ProgressEntryDto
	{
		public string Name { get; set; }
		public bool Done { get; set; }

		/// <summary>Whether this only becomes reachable in hardmode, so the list can be split.</summary>
		public bool Hardmode { get; set; }
	}

	/// <summary>
	/// What the player can craft where they're standing.
	/// </summary>
	/// <remarks>
	/// Taken from the game's own <c>Main.availableRecipe</c> rather than recomputed, so it accounts for
	/// nearby crafting stations, water, honey and every modded condition without this mod knowing about
	/// any of them.
	/// </remarks>
	internal sealed class CraftListDto
	{
		public int Count { get; set; }
		public List<CraftDto> Recipes { get; set; }
	}

	internal sealed class CraftDto
	{
		public string Name { get; set; }
		public int Stack { get; set; }
		public int Rare { get; set; }
		public string IconKey { get; set; }
		public List<string> Ingredients { get; set; }
	}

	/// <summary>A command sent from the second screen's touch UI.</summary>
	internal sealed class ActionDto
	{
		public string Type { get; set; }
		public int Index { get; set; } = -1;
		public int To { get; set; } = -1;
		public string Mode { get; set; }
	}
}
