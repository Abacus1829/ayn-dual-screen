using System.ComponentModel;
using Terraria.ModLoader.Config;

namespace AynDualScreen
{
	/// <summary>
	/// Client-side settings, edited from the in-game mod config screen.
	/// </summary>
	/// <remarks>
	/// <see cref="Port"/> and <see cref="AllowLanAccess"/> decide how the listener socket is opened, which only happens
	/// once when the mod loads, so both are marked <see cref="ReloadRequiredAttribute"/>. Everything else is read live.
	/// </remarks>
	public class DualScreenConfig : ModConfig
	{
		public override ConfigScope Mode => ConfigScope.ClientSide;

		[DefaultValue(27301)]
		[ReloadRequired]
		public int Port = 27301;

		/// <summary>Whether to listen on every interface rather than loopback only.</summary>
		/// <remarks>
		/// On by default, because a handheld as the second screen is the point of the mod and loopback-only
		/// makes that impossible. The trade-off is real and worth knowing: anyone on the same network can
		/// open the page and rearrange or destroy your items. Set this to false on a network you don't
		/// trust, or set <see cref="AllowTrash"/> to false to at least take away the destructive button.
		/// </remarks>
		[DefaultValue(true)]
		[ReloadRequired]
		public bool AllowLanAccess = true;

		[DefaultValue(10)]
		public int UpdatesPerSecond = 10;

		[DefaultValue(true)]
		public bool EnableItemIcons = true;

		[DefaultValue(true)]
		public bool AllowTrash = true;

		/// <summary>Whether the screen may throw items on the ground.</summary>
		[DefaultValue(true)]
		public bool AllowDrop = true;

		/// <summary>Whether the screen may rearrange the inventory: dragging between slots, and Sort.</summary>
		[DefaultValue(true)]
		public bool AllowInventoryEdit = true;

		/// <summary>Whether the screen may trigger the vanilla quick-use keys: Heal, Mana, Buff and Mount.</summary>
		[DefaultValue(true)]
		public bool AllowQuickUse = true;

		/// <summary>
		/// Whether the second screen may buy from an open shop.
		/// </summary>
		/// <remarks>
		/// Off by default, and deliberately separate from the other permissions: LAN access is on by
		/// default, and buying spends real coins. Showing the shop is always allowed; only the Buy button
		/// is gated.
		/// </remarks>
		[DefaultValue(false)]
		public bool AllowShopping = false;

		/// <summary>
		/// Whether the checklist also lists bosses added by other mods.
		/// </summary>
		/// <remarks>
		/// The list is read from the load order itself, so it works with any mod without either side
		/// knowing about the other. Turn it off for a checklist of the vanilla progression only — worth
		/// doing with a very large content mod, where the modded list can run to dozens of entries.
		/// </remarks>
		[DefaultValue(true)]
		public bool ShowModdedBosses = true;

		/// <summary>Whether the checklist tab lists the mods currently loaded.</summary>
		[DefaultValue(true)]
		public bool ShowModList = true;

		/// <summary>Whether hostile NPCs appear on the minimap.</summary>
		[DefaultValue(true)]
		public bool ShowEnemies = true;

		/// <summary>Whether town NPCs appear on the minimap.</summary>
		[DefaultValue(true)]
		public bool ShowTownNpcs = true;

		/// <summary>
		/// A ceiling on how many map dots are sent per snapshot.
		/// </summary>
		/// <remarks>
		/// An event or a big invasion can put hundreds of NPCs in the world at once, and every one of them
		/// costs JSON on every snapshot. Nearest are kept, so the ones that matter survive the cut.
		/// </remarks>
		[DefaultValue(80)]
		public int MaxMapEntities = 80;

		/// <summary>Width of the follow-mode minimap window, in world tiles.</summary>
		[DefaultValue(220)]
		public int MinimapTilesWide = 220;

		/// <summary>Height of the follow-mode minimap window, in world tiles.</summary>
		[DefaultValue(150)]
		public int MinimapTilesHigh = 150;

		/// <summary>A folder to serve the second-screen page from instead of the packed copy, for editing the UI without rebuilding.</summary>
		[DefaultValue("")]
		public string WebRootOverride = "";
	}
}
