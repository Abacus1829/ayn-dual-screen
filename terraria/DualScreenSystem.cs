using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using Microsoft.Xna.Framework;
using Microsoft.Xna.Framework.Graphics;
using Newtonsoft.Json;
using Newtonsoft.Json.Serialization;
using ReLogic.Content;
using Terraria;
using Terraria.GameContent;
using Terraria.ID;
using Terraria.Map;
using Terraria.ModLoader;
using Terraria.UI;

namespace AynDualScreen
{
	/// <summary>
	/// Publishes a live view of the player (clock, inventory, buffs, minimap) over HTTP so a second display can render
	/// it, and applies touch commands sent back from that display.
	/// </summary>
	/// <remarks>
	/// Threading: the web server answers requests on thread pool threads, which must never touch game state. So the
	/// game thread builds immutable JSON snapshots in <see cref="PostUpdateEverything"/> and publishes them to volatile
	/// fields, and incoming commands are queued and drained on the game thread. Breaking this rule is the classic way
	/// to corrupt a world, so keep new features on the same pattern.
	/// </remarks>
	public class DualScreenSystem : ModSystem
	{
		private static readonly JsonSerializerSettings JsonSettings = new()
		{
			ContractResolver = new CamelCasePropertyNamesContractResolver(),
			NullValueHandling = NullValueHandling.Ignore
		};

		/// <summary>How many inventory slots to publish: 0-9 hotbar, 10-49 main, 50-53 coins, 54-57 ammo.</summary>
		private const int InventorySlots = 58;

		/// <summary>Ceiling on the craftable list. A well-stocked workshop can offer several hundred.</summary>
		private const int MaxCraftable = 120;

		/// <summary>How many armour slots do something: 0-2 armour, 3-9 accessories. The rest are vanity.</summary>
		private const int EquipSlots = 10;

		/// <summary>Coin item types in ascending denomination, and what each is worth in copper.</summary>
		private static readonly (int Type, long Value)[] CoinValues =
		{
			(ItemID.CopperCoin, 1),
			(ItemID.SilverCoin, 100),
			(ItemID.GoldCoin, 10_000),
			(ItemID.PlatinumCoin, 1_000_000)
		};

		/// <summary>
		/// Inventory slot backgrounds, taken from the game's own textures so the second screen matches the real UI.
		/// </summary>
		/// <remarks>
		/// These are the same assets <c>ItemSlot.Draw</c> picks between for each slot context. If one ever looks wrong,
		/// the numbered variants are all reachable at <c>/asset/backN.png</c> so the right one can be found by eye.
		/// </remarks>
		private static readonly (string Key, Func<Asset<Texture2D>> Source)[] UiAssets =
		{
			("slot", () => TextureAssets.InventoryBack),
			("slot-selected", () => TextureAssets.InventoryBack14),
			("slot-cursor", () => TextureAssets.InventoryBack6),
			("slot-coin", () => TextureAssets.InventoryBack8),
			("slot-ammo", () => TextureAssets.InventoryBack9)
		};

		/// <summary>Every numbered slot background, so a wrong pick above can be corrected by looking at them all.</summary>
		private static readonly Func<Asset<Texture2D>>[] UiAssetVariants =
		{
			() => TextureAssets.InventoryBack, () => TextureAssets.InventoryBack2, () => TextureAssets.InventoryBack3,
			() => TextureAssets.InventoryBack4, () => TextureAssets.InventoryBack5, () => TextureAssets.InventoryBack6,
			() => TextureAssets.InventoryBack7, () => TextureAssets.InventoryBack8, () => TextureAssets.InventoryBack9,
			() => TextureAssets.InventoryBack10, () => TextureAssets.InventoryBack11, () => TextureAssets.InventoryBack12,
			() => TextureAssets.InventoryBack13, () => TextureAssets.InventoryBack14, () => TextureAssets.InventoryBack15,
			() => TextureAssets.InventoryBack16, () => TextureAssets.InventoryBack17, () => TextureAssets.InventoryBack18,
			() => TextureAssets.InventoryBack19
		};

		private WebServer Server;
		private bool UiAssetsReady;

		private volatile string StateJson = "{\"ready\":false}";
		private volatile string MinimapJson = "{\"rev\":0}";

		/// <summary>
		/// The checklist and the craftable list, published on their own slow cadence.
		/// </summary>
		/// <remarks>
		/// Both are far too big to ride along with a 10Hz snapshot — the craftable list alone can run to
		/// hundreds of recipes — and neither changes fast enough to need it. The client polls them
		/// separately, the same way it does the minimap.
		/// </remarks>
		private volatile string ProgressJson = "{}";
		private volatile string CraftJson = "{\"count\":0,\"recipes\":[]}";
		private volatile string TalkJson = "null";

		private int TicksSinceProgress;
		private int TicksSinceCraft;

		private readonly ConcurrentQueue<ActionDto> PendingActions = new();
		private readonly ConcurrentDictionary<string, byte[]> IconCache = new();

		/// <summary>The second-screen page, read out of the mod file at load and served from memory after that.</summary>
		private readonly ConcurrentDictionary<string, byte[]> WebFiles = new();

		/// <summary>"local" or "world". Written from a request thread, read on the game thread — a reference write, so atomic.</summary>
		private volatile string MapMode = "local";
		private string BuiltMapMode = string.Empty;

		private int MapRev;
		private int TicksSinceState;
		private int TicksSinceMap;
		private int StateTickInterval = 6;
		private int LastMapCenterX = int.MinValue;
		private int LastMapCenterY = int.MinValue;
		private long TickCount;

		private DualScreenConfig Config => ModContent.GetInstance<DualScreenConfig>();

		/*********
		** Lifecycle
		*********/
		/// <remarks>Started here rather than in <c>Load</c> so the saved config values are already on disk and read.</remarks>
		public override void PostSetupContent()
		{
			if (Main.dedServ)
				return; // a headless server has no player to mirror

			DualScreenConfig config = this.Config;
			this.StateTickInterval = Math.Max(1, 60 / Math.Clamp(config.UpdatesPerSecond, 1, 60));

			// must happen before the server starts answering, and while the mod file is still open
			this.CacheWebFiles();

			IPAddress address = config.AllowLanAccess ? IPAddress.Any : IPAddress.Loopback;

			try
			{
				this.Server = new WebServer(address, config.Port, this.HandleRequest, message => this.Mod.Logger.Debug(message));
				this.Server.Start();
			}
			catch (Exception ex)
			{
				this.Mod.Logger.Error($"Couldn't start the second-screen server on port {config.Port}: {ex.Message}");
				this.Mod.Logger.Error("Change the port in the Ayn Dual Screen mod config and reload.");
				this.Server = null;
				return;
			}

			this.AnnounceAddresses(config);
		}

		/// <summary>
		/// Print the addresses the second screen can actually be opened at.
		/// </summary>
		/// <remarks>
		/// Naming only localhost here was actively misleading: it's correct on this PC, but typing it into a
		/// phone or handheld points that device at itself, so the connection fails for a reason the log
		/// gave no hint about. When LAN access is on, the address another device needs is the one worth
		/// printing; when it's off, the point worth making is that no other device can connect at all.
		/// </remarks>
		private void AnnounceAddresses(DualScreenConfig config)
		{
			this.Mod.Logger.Info($"Second screen ready on this PC at http://localhost:{config.Port}/");

			if (!config.AllowLanAccess)
			{
				this.Mod.Logger.Info("Only this PC can connect. To use a phone, tablet or handheld as the second screen, turn on AllowLanAccess in the mod config and reload.");
				return;
			}

			(List<string> addresses, bool vpnActive) = LocalNetworkAddresses();
			if (addresses.Count == 0)
			{
				this.Mod.Logger.Warn("LAN access is on, but no network address was found. Is this PC connected to a network?");
			}
			else
			{
				this.Mod.Logger.Info($"From another device on your network: http://{addresses[0]}:{config.Port}/");
				for (int i = 1; i < addresses.Count; i++)
					this.Mod.Logger.Info($"   ...or, if that one doesn't work: http://{addresses[i]}:{config.Port}/");
			}

			if (vpnActive)
				this.Mod.Logger.Warn("A VPN adapter is active. VPNs routinely block or reroute local network traffic, so if the handheld can't connect, try disconnecting the VPN first.");

			this.Mod.Logger.Warn("LAN access is enabled: anyone on your network can rearrange and destroy your items. Only use this on a network you trust.");
		}

		/// <summary>Words that mark an adapter as virtual or a VPN tunnel rather than a real network connection.</summary>
		private static readonly string[] VirtualAdapterNames =
		{
			"vEthernet", "VirtualBox", "VMware", "WSL", "Hyper-V", "Loopback",
			"NordLynx", "NordVPN", "OpenVPN", "WireGuard", "Tailscale", "ZeroTier", "TAP-Windows"
		};

		/// <summary>This machine's usable IPv4 addresses, most likely first, and whether a VPN is in the way.</summary>
		/// <remarks>
		/// Link-local 169.254.x addresses are skipped because they mean DHCP failed. Virtual and VPN adapters
		/// sort last rather than being dropped: a Hyper-V, WSL or VPN address answers on this PC but is
		/// usually unreachable from the handheld, so leading with one would send someone down the wrong path
		/// — but on an unusual setup it might be the only one that works, so it's still worth offering.
		/// </remarks>
		private static (List<string> Addresses, bool VpnActive) LocalNetworkAddresses()
		{
			var found = new List<(int Rank, string Address)>();
			bool vpnActive = false;

			try
			{
				foreach (NetworkInterface nic in NetworkInterface.GetAllNetworkInterfaces())
				{
					if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback)
						continue;

					int rank = nic.NetworkInterfaceType switch
					{
						NetworkInterfaceType.Wireless80211 => 0,
						NetworkInterfaceType.Ethernet => 1,
						_ => 3
					};

					string name = $"{nic.Name} {nic.Description}";
					bool isVirtual = false;
					foreach (string marker in VirtualAdapterNames)
					{
						if (name.Contains(marker, StringComparison.OrdinalIgnoreCase))
						{
							isVirtual = true;
							break;
						}
					}

					// a tunnel adapter that isn't named like a NIC is almost always a VPN
					if (isVirtual || nic.NetworkInterfaceType == NetworkInterfaceType.Tunnel)
						rank = 4;

					foreach (UnicastIPAddressInformation info in nic.GetIPProperties().UnicastAddresses)
					{
						if (info.Address.AddressFamily != AddressFamily.InterNetwork)
							continue;

						string ip = info.Address.ToString();
						if (ip.StartsWith("169.254.", StringComparison.Ordinal))
							continue;

						if (rank == 4)
							vpnActive = true;

						found.Add((rank, ip));
					}
				}
			}
			catch (Exception)
			{
				// enumerating adapters is best-effort; the localhost line above is still useful without it
			}

			found.Sort((a, b) => a.Rank.CompareTo(b.Rank));

			var addresses = new List<string>(found.Count);
			foreach ((int _, string ip) in found)
				addresses.Add(ip);
			return (addresses, vpnActive);
		}

		public override void Unload()
		{
			this.Server?.Dispose();
			this.Server = null;
			this.IconCache.Clear();
			this.WebFiles.Clear();
			ModIntegration.Reset(); // the next load may be a different mod list entirely
		}

		public override void OnWorldUnload()
		{
			this.StateJson = "{\"ready\":false}";
			this.LastMapCenterX = int.MinValue;
			this.LastMapCenterY = int.MinValue;
		}

		public override void PostUpdateEverything()
		{
			if (this.Server == null)
				return;

			Player player = Main.LocalPlayer;
			if (Main.gameMenu || player == null || !player.active)
			{
				this.StateJson = "{\"ready\":false}";
				return;
			}

			this.TickCount++;
			this.EnsureUiAssets();
			this.DrainActions();

			if (++this.TicksSinceState >= this.StateTickInterval)
			{
				this.TicksSinceState = 0;
				this.TryRebuild(this.RebuildState, "state");
			}

			this.TicksSinceMap++;
			if (this.ShouldRebuildMap(player))
			{
				this.TicksSinceMap = 0;
				this.TryRebuild(this.RebuildMinimap, "minimap");
			}

			// a boss flag flips maybe a dozen times in a playthrough; twice a second is generous
			if (++this.TicksSinceProgress >= 30)
			{
				this.TicksSinceProgress = 0;
				this.TryRebuild(this.RebuildProgress, "progress");
			}

			// the craftable list changes whenever the player moves or picks anything up, but scanning it
			// walks every available recipe, so it gets its own slower beat
			if (++this.TicksSinceCraft >= 30)
			{
				this.TicksSinceCraft = 0;
				this.TryRebuild(this.RebuildCraftable, "craftable");
			}
		}

		/// <summary>
		/// Decide whether the minimap image is stale.
		/// </summary>
		/// <remarks>
		/// Redrawing it is by far the most expensive thing here, so follow mode only redraws when the player has
		/// actually moved a few tiles (or twice a second regardless, to pick up newly explored ground), and world mode
		/// — which samples the entire world — only every few seconds.
		/// </remarks>
		private bool ShouldRebuildMap(Player player)
		{
			if (this.MapMode != this.BuiltMapMode)
				return true;

			if (this.MapMode == "world")
				return this.TicksSinceMap >= 180;

			int centerX = (int)(player.Center.X / 16f);
			int centerY = (int)(player.Center.Y / 16f);
			bool moved = Math.Abs(centerX - this.LastMapCenterX) >= 4 || Math.Abs(centerY - this.LastMapCenterY) >= 4;

			return moved || this.TicksSinceMap >= 30;
		}

		/// <summary>Run a snapshot builder, logging rather than crashing the game if it trips over an odd world state.</summary>
		private void TryRebuild(Action build, string label)
		{
			try
			{
				build();
			}
			catch (Exception ex)
			{
				this.Mod.Logger.Debug($"Failed to build {label} snapshot: {ex.Message}");
			}
		}

		/*********
		** Snapshots (game thread only)
		*********/
		private void RebuildState()
		{
			DualScreenConfig config = this.Config;
			Player player = Main.LocalPlayer;

			// Player.inventory is 59 long, but the last entry is the game's own scratch slot for the item on the
			// cursor, not somewhere the player can put anything. Publishing it put a phantom 59th slot on the screen.
			int slotCount = Math.Min(player.inventory.Length, InventorySlots);

			var inventory = new List<SlotDto>(slotCount);
			long coins = 0;
			int healingItems = 0;
			int manaItems = 0;

			for (int i = 0; i < slotCount; i++)
			{
				Item item = player.inventory[i];
				if (item == null || item.IsAir)
				{
					inventory.Add(new SlotDto { Index = i });
					continue;
				}

				// worth knowing at a glance how many potions are left without hunting the grid for them
				if (item.healLife > 0)
					healingItems += item.stack;
				if (item.healMana > 0)
					manaItems += item.stack;

				foreach ((int type, long value) in CoinValues)
				{
					if (item.type == type)
						coins += value * item.stack;
				}

				inventory.Add(new SlotDto
				{
					Index = i,
					Name = item.HoverName,
					Stack = item.stack,
					Rare = item.rare,
					IconKey = config.EnableItemIcons ? this.EnsureItemIcon(item) : null,
					Meta = DescribeItem(item),
					Healing = item.healLife > 0 || item.healMana > 0,
					Buffing = item.buffType > 0 && item.consumable
				});
			}

			var buffs = new List<BuffDto>();
			int potionCooldown = 0;

			for (int i = 0; i < Player.MaxBuffs; i++)
			{
				int type = player.buffType[i];
				if (type <= 0)
					continue;

				// Potion Sickness is the one buff worth pulling out of the strip: it decides whether the
				// Heal button will do anything at all
				if (type == BuffID.PotionSickness)
					potionCooldown = Math.Max(1, player.buffTime[i] / 60);

				// Station and proximity buffs (Star in a Bottle, Banner) aren't flagged as timerless but are
				// re-applied every tick with a fraction of a second left, so a plain divide showed a permanent "0".
				int seconds = Main.buffNoTimeDisplay[type] || player.buffTime[i] < 60
					? -1
					: player.buffTime[i] / 60;

				buffs.Add(new BuffDto
				{
					Type = type,
					Name = Lang.GetBuffName(type),
					Seconds = seconds,
					IconKey = config.EnableItemIcons ? this.EnsureBuffIcon(type) : null,

					// modded buffs go through exactly the same lookups: tModLoader grows Lang, Main.debuff
					// and TextureAssets.Buff to cover them, so nothing here needs to know they exist
					Debuff = type < Main.debuff.Length && Main.debuff[type],
					Description = DescribeBuff(type),
					Source = ModIntegration.BuffSource(type)
				});
			}

			float playerTileX = player.Center.X / 16f;
			float playerTileY = player.Center.Y / 16f;

			var state = new StateDto
			{
				Ready = true,
				Tick = this.TickCount,

				WorldName = Main.worldName,
				WorldWidth = Main.maxTilesX,
				WorldHeight = Main.maxTilesY,
				HardMode = Main.hardMode,
				Difficulty = DescribeDifficulty(),

				TimeMinutes = GetClockMinutes(),
				DayTime = Main.dayTime,
				MoonPhase = (int)Main.moonPhase,

				Events = DescribeEvents(),

				Life = player.statLife,
				LifeMax = player.statLifeMax2,
				Mana = player.statMana,
				ManaMax = player.statManaMax2,
				Defense = player.statDefense,
				Breath = player.breath,
				BreathMax = player.breathMax,

				X = playerTileX,
				Y = playerTileY,
				Direction = player.direction,
				Team = player.team,
				TeamColor = TeamHex(player.team),

				DepthFeet = (int)((playerTileY - Main.worldSurface) * 2),
				Layer = DescribeLayer(playerTileY),
				Biome = DescribeBiome(player),

				Coins = coins,

				SelectedSlot = player.selectedItem,
				HotbarSize = 10,
				MapRev = this.MapRev,

				HealingItems = healingItems,
				ManaItems = manaItems,
				PotionCooldown = potionCooldown,

				Can = new PermissionsDto
				{
					Trash = config.AllowTrash,
					Drop = config.AllowDrop,
					Edit = config.AllowInventoryEdit,
					QuickUse = config.AllowQuickUse
				},

				Inventory = inventory,
				Equipment = this.BuildEquipment(player, config),
				Buffs = buffs,
				Entities = BuildEntities(config),
				Boss = FindBoss()
			};

			this.StateJson = JsonConvert.SerializeObject(state, JsonSettings);
		}

		/// <summary>
		/// The equipped armour and accessories.
		/// </summary>
		/// <remarks>
		/// <c>Player.armor</c> is 20 long: 0-2 are the armour pieces, 3-9 the accessory slots, and 10-19
		/// the vanity copies of both. Only the first ten actually do anything, so the vanity half is
		/// skipped — showing it would double the panel and change nothing about the character's stats.
		/// </remarks>
		private List<EquipDto> BuildEquipment(Player player, DualScreenConfig config)
		{
			var equipment = new List<EquipDto>(EquipSlots);

			for (int i = 0; i < EquipSlots && i < player.armor.Length; i++)
			{
				string slot = i switch
				{
					0 => "Helmet",
					1 => "Chest",
					2 => "Legs",
					_ => "Accessory"
				};

				Item item = player.armor[i];
				if (item == null || item.IsAir)
				{
					equipment.Add(new EquipDto { Index = i, Slot = slot });
					continue;
				}

				equipment.Add(new EquipDto
				{
					Index = i,
					Slot = slot,
					Name = item.HoverName,
					Rare = item.rare,
					Defense = item.defense,
					IconKey = config.EnableItemIcons ? this.EnsureItemIcon(item) : null
				});
			}

			return equipment;
		}

		/// <summary>Minutes since midnight. Terraria's clock starts the day at 4:30 AM with <c>Main.time</c> at zero.</summary>
		private static float GetClockMinutes()
		{
			double seconds = Main.time + (Main.dayTime ? 0.0 : 54000.0);
			double hours = 4.5 + seconds / 3600.0;
			return (float)(hours % 24.0 * 60.0);
		}

		private static string DescribeDifficulty()
		{
			if (Main.getGoodWorld)
				return "For the Worthy";
			if (Main.masterMode)
				return "Master";
			if (Main.expertMode)
				return "Expert";
			return "Classic";
		}

		/// <summary>The weather and world events worth showing, most notable first.</summary>
		private static List<string> DescribeEvents()
		{
			var events = new List<string>();

			if (Main.bloodMoon)
				events.Add("Blood Moon");
			if (Main.eclipse)
				events.Add("Solar Eclipse");
			if (Main.pumpkinMoon)
				events.Add("Pumpkin Moon");
			if (Main.snowMoon)
				events.Add("Frost Moon");
			if (Main.slimeRain)
				events.Add("Slime Rain");
			if (Main.raining)
				events.Add(Main.IsItStorming ? "Thunderstorm" : "Rain");
			if (Main.windSpeedCurrent >= 0.4f || Main.windSpeedCurrent <= -0.4f)
				events.Add("Windy");

			if (events.Count == 0)
				events.Add(Main.dayTime ? "Clear" : "Night");

			return events;
		}

		private static string DescribeLayer(float tileY)
		{
			if (tileY < Main.worldSurface * 0.35)
				return "Space";
			if (tileY < Main.worldSurface)
				return "Surface";
			if (tileY < Main.rockLayer)
				return "Underground";
			if (tileY < Main.maxTilesY - 200)
				return "Cavern";
			return "Underworld";
		}

		/// <summary>The most specific biome the player is standing in. Order matters: the first match wins.</summary>
		private static string DescribeBiome(Player player)
		{
			if (player.ZoneDungeon)
				return "Dungeon";
			if (player.ZoneTowerSolar || player.ZoneTowerVortex || player.ZoneTowerNebula || player.ZoneTowerStardust)
				return "Celestial Pillar";
			if (player.ZoneLihzhardTemple)
				return "Jungle Temple";
			if (player.ZoneMeteor)
				return "Meteorite";
			if (player.ZoneGraveyard)
				return "Graveyard";
			if (player.ZoneGlowshroom)
				return "Glowing Mushroom";
			if (player.ZoneCrimson)
				return "Crimson";
			if (player.ZoneCorrupt)
				return "Corruption";
			if (player.ZoneHallow)
				return "Hallow";
			if (player.ZoneJungle)
				return "Jungle";
			if (player.ZoneSnow)
				return "Snow";
			if (player.ZoneDesert || player.ZoneUndergroundDesert)
				return "Desert";
			if (player.ZoneBeach)
				return "Ocean";
			if (player.ZoneGranite)
				return "Granite";
			if (player.ZoneMarble)
				return "Marble";
			if (player.ZoneSkyHeight)
				return "Space";
			if (player.ZoneUnderworldHeight)
				return "Underworld";
			return "Forest";
		}

		/// <summary>A one-line summary from whichever stats the item actually carries.</summary>
		private static string DescribeItem(Item item)
		{
			var bits = new List<string>();

			if (item.damage > 0)
				bits.Add($"{item.damage} damage");
			if (item.defense > 0)
				bits.Add($"{item.defense} defense");
			if (item.pick > 0)
				bits.Add($"{item.pick}% pick");
			if (item.axe > 0)
				bits.Add($"{item.axe * 5}% axe");
			if (item.hammer > 0)
				bits.Add($"{item.hammer}% hammer");
			if (item.healLife > 0)
				bits.Add($"heals {item.healLife}");
			if (item.healMana > 0)
				bits.Add($"{item.healMana} mana");
			if (item.mana > 0)
				bits.Add($"{item.mana} mana cost");
			if (item.createTile > -1 || item.createWall > -1)
				bits.Add("placeable");

			// escaped rather than written literally: these sources have no BOM, so a raw middot gets read as cp1252
			// and re-encoded, and the second screen ends up showing "Â·"
			return bits.Count > 0 ? string.Join(" · ", bits) : null;
		}

		/// <summary>
		/// A PvP team's colour as "#rrggbb".
		/// </summary>
		/// <remarks>
		/// Read out of <c>Main.teamColor</c> rather than hard-coded, so the arrows on the second screen are
		/// the same colours the game paints team names and hearts with — including if a future update
		/// retunes them. Falls back to white if the index is ever outside the table.
		/// </remarks>
		private static string TeamHex(int team)
		{
			try
			{
				Color[] table = Main.teamColor;
				if (table != null && team >= 0 && team < table.Length)
				{
					Color colour = table[team];
					return string.Format(CultureInfo.InvariantCulture, "#{0:x2}{1:x2}{2:x2}", colour.R, colour.G, colour.B);
				}
			}
			catch
			{
				// a missing or resized table is not worth losing the whole snapshot over
			}

			return "#ffffff";
		}

		/// <summary>Everything moving that's worth a dot on the minimap, filtered and capped by the config.</summary>
		private static List<EntityDto> BuildEntities(DualScreenConfig config)
		{
			var entities = new List<EntityDto>();

			for (int i = 0; i < Main.maxNPCs; i++)
			{
				NPC npc = Main.npc[i];
				if (npc == null || !npc.active || npc.hide)
					continue;

				string kind;
				if (npc.townNPC)
					kind = "town";
				else if (npc.boss)
					kind = "boss";
				else if (npc.friendly)
					kind = "friendly";
				else
					kind = "enemy";

				// bosses ignore the filters: hiding the thing trying to kill you would be a strange default
				if (!config.ShowTownNpcs && kind == "town")
					continue;
				if (!config.ShowEnemies && kind == "enemy")
					continue;

				entities.Add(new EntityDto
				{
					Kind = kind,
					// not FullName: that's "<given> the <type>", which reads as "Nurse the Nurse" whenever a town
					// NPC's given name matches its type
					Name = npc.GivenOrTypeName,
					X = npc.Center.X / 16f,
					Y = npc.Center.Y / 16f,
					// -1 rather than the default 0, which the page would otherwise read as "on no team"
					Team = -1
				});
			}

			if (Main.netMode != NetmodeID.SinglePlayer)
			{
				for (int i = 0; i < Main.maxPlayers; i++)
				{
					Player other = Main.player[i];
					if (other == null || !other.active || i == Main.myPlayer || other.dead)
						continue;

					entities.Add(new EntityDto
					{
						Kind = "player",
						Name = other.name,
						X = other.Center.X / 16f,
						Y = other.Center.Y / 16f,
						Direction = other.direction,
						Team = other.team,
						TeamColor = TeamHex(other.team)
					});
				}
			}

			// an invasion can put hundreds of NPCs in the world; keep the nearest, drop the rest
			int cap = Math.Clamp(config.MaxMapEntities, 10, 500);
			if (entities.Count > cap)
			{
				Vector2 here = Main.LocalPlayer.Center / 16f;
				entities.Sort((a, b) =>
				{
					float left = (a.X - here.X) * (a.X - here.X) + (a.Y - here.Y) * (a.Y - here.Y);
					float right = (b.X - here.X) * (b.X - here.X) + (b.Y - here.Y) * (b.Y - here.Y);
					return left.CompareTo(right);
				});
				entities.RemoveRange(cap, entities.Count - cap);
			}

			return entities;
		}

		/// <summary>
		/// What a buff actually does, in the game's own words.
		/// </summary>
		/// <remarks>
		/// Descriptions are written for a tooltip and are frequently multi-line; the second screen shows
		/// them on one line, so the breaks become separators. Modded buffs register their description with
		/// the same localisation system, so they come back here for free.
		/// </remarks>
		private static string DescribeBuff(int type)
		{
			try
			{
				string text = Lang.GetBuffDescription(type);
				if (string.IsNullOrWhiteSpace(text))
					return null;

				return text.Replace("\r", string.Empty).Replace("\n", " · ").Trim();
			}
			catch (Exception)
			{
				return null; // a mod with a missing localisation entry shouldn't cost us the buff strip
			}
		}

		/// <summary>The most substantial boss currently alive, so the screen can show a health bar for it.</summary>
		private static BossDto FindBoss()
		{
			NPC best = null;
			for (int i = 0; i < Main.maxNPCs; i++)
			{
				NPC npc = Main.npc[i];
				if (npc == null || !npc.active || !npc.boss)
					continue;
				if (best == null || npc.lifeMax > best.lifeMax)
					best = npc;
			}

			if (best == null)
				return null;

			return new BossDto
			{
				Name = best.FullName,
				Life = best.life,
				LifeMax = best.lifeMax,
				Source = ModIntegration.NpcSource(best)
			};
		}

		/*********
		** Checklist and crafting
		*********/
		private void RebuildProgress()
		{
			this.ProgressJson = JsonConvert.SerializeObject(Progress.Build(), JsonSettings);
		}

		/// <summary>
		/// Publish what the player can craft where they're standing.
		/// </summary>
		/// <remarks>
		/// <c>Main.availableRecipe</c> is the game's own answer to that question, already filtered by
		/// nearby stations, liquids and every modded condition — recomputing it here would be both slower
		/// and wrong for other mods' recipes.
		/// </remarks>
		private void RebuildCraftable()
		{
			DualScreenConfig config = this.Config;
			var recipes = new List<CraftDto>();
			int total = Main.numAvailableRecipes;

			for (int i = 0; i < total && recipes.Count < MaxCraftable; i++)
			{
				Recipe recipe = Main.recipe[Main.availableRecipe[i]];
				if (recipe?.createItem == null || recipe.createItem.IsAir)
					continue;

				var ingredients = new List<string>();
				foreach (Item ingredient in recipe.requiredItem)
				{
					if (ingredient == null || ingredient.type <= 0)
						continue;
					ingredients.Add(ingredient.stack > 1
						? $"{ingredient.stack}x {ingredient.Name}"
						: ingredient.Name);
				}

				recipes.Add(new CraftDto
				{
					Name = recipe.createItem.Name,
					Stack = recipe.createItem.stack,
					Rare = recipe.createItem.rare,
					IconKey = config.EnableItemIcons ? this.EnsureItemIcon(recipe.createItem) : null,
					Ingredients = ingredients
				});
			}

			this.TalkJson = JsonConvert.SerializeObject(
				Conversation.Build(Main.LocalPlayer, config, this.EnsureItemIcon, this.EnsureNpcArt),
				JsonSettings) ?? "null";

			this.CraftJson = JsonConvert.SerializeObject(
				new CraftListDto { Count = total, Recipes = recipes },
				JsonSettings
			);
		}

		/*********
		** Minimap
		*********/
		/// <summary>
		/// Render the explored map to a PNG.
		/// </summary>
		/// <remarks>
		/// A Terraria world is far too large to ship as a tile grid the way the Stardew version does — a small world is
		/// five million tiles. Instead this reads <c>Main.Map</c>, the game's own per-tile explored colour, into a
		/// window around the player (or a downsampled view of the whole world) and sends it as an image.
		/// </remarks>
		private void RebuildMinimap()
		{
			DualScreenConfig config = this.Config;
			Player player = Main.LocalPlayer;
			string mode = this.MapMode == "world" ? "world" : "local";

			int step, originX, originY, width, height;

			if (mode == "world")
			{
				// keep the widest edge near 900px so the payload stays small and the client can still scale it up
				step = Math.Max(1, (int)Math.Ceiling(Main.maxTilesX / 900.0));
				originX = 0;
				originY = 0;
				width = Main.maxTilesX / step;
				height = Main.maxTilesY / step;
			}
			else
			{
				step = 1;
				width = Math.Clamp(config.MinimapTilesWide, 40, 600);
				height = Math.Clamp(config.MinimapTilesHigh, 40, 600);
				width = Math.Min(width, Main.maxTilesX);
				height = Math.Min(height, Main.maxTilesY);

				int centerX = (int)(player.Center.X / 16f);
				int centerY = (int)(player.Center.Y / 16f);
				originX = Math.Clamp(centerX - width / 2, 0, Main.maxTilesX - width);
				originY = Math.Clamp(centerY - height / 2, 0, Main.maxTilesY - height);

				this.LastMapCenterX = centerX;
				this.LastMapCenterY = centerY;
			}

			var rgba = new byte[width * height * 4];
			int offset = 0;

			for (int y = 0; y < height; y++)
			{
				int worldY = originY + y * step;
				for (int x = 0; x < width; x++, offset += 4)
				{
					int worldX = originX + x * step;
					if (worldX < 0 || worldX >= Main.maxTilesX || worldY < 0 || worldY >= Main.maxTilesY)
						continue; // leaves it transparent

					MapTile tile = Main.Map[worldX, worldY];
					if (tile.Light <= 0)
						continue; // unexplored

					Color color = MapHelper.GetMapTileXnaColor(ref tile);
					rgba[offset] = color.R;
					rgba[offset + 1] = color.G;
					rgba[offset + 2] = color.B;
					rgba[offset + 3] = 255;
				}
			}

			byte[] png = Png.Encode(rgba, width, height);
			if (png.Length == 0)
				return;

			this.MapRev++;
			this.BuiltMapMode = mode;
			this.MinimapJson = JsonConvert.SerializeObject(
				new MinimapDto
				{
					Rev = this.MapRev,
					Mode = mode,
					OriginX = originX,
					OriginY = originY,
					Width = width,
					Height = height,
					Step = step,
					Png = "data:image/png;base64," + Convert.ToBase64String(png)
				},
				JsonSettings
			);
		}

		/*********
		** Icons
		*********/
		/// <summary>Crop an item's sprite out of its tilesheet and cache it as a PNG, returning the key to fetch it with.</summary>
		/// <remarks>Must run on the game thread: it reads back from a GPU texture.</remarks>
		private string EnsureItemIcon(Item item)
		{
			string key = "i" + item.type;
			if (this.IconCache.ContainsKey(key))
				return this.IconCache[key].Length > 0 ? key : null;

			try
			{
				Main.instance.LoadItem(item.type);
				Texture2D sheet = TextureAssets.Item[item.type]?.Value;
				if (sheet == null)
					return null;

				// animated items share one tall sheet; take the frame the game would draw right now
				Rectangle source = Main.itemAnimations[item.type] != null
					? Main.itemAnimations[item.type].GetFrame(sheet)
					: sheet.Bounds;

				this.IconCache[key] = CropToPng(sheet, source);
				return key;
			}
			catch (Exception ex)
			{
				this.Mod.Logger.Debug($"Couldn't build an icon for item {item.type}: {ex.Message}");
				this.IconCache[key] = Array.Empty<byte>(); // don't retry every tick
				return null;
			}
		}

		/// <summary>As <see cref="EnsureItemIcon"/>, for an NPC's sprite — the first walking frame.</summary>
		private string EnsureNpcArt(NPC npc)
		{
			string key = "n" + npc.type;
			if (this.IconCache.ContainsKey(key))
				return this.IconCache[key].Length > 0 ? key : null;

			try
			{
				Main.instance.LoadNPC(npc.type);
				Texture2D sheet = TextureAssets.Npc[npc.type]?.Value;
				if (sheet == null)
					return null;

				this.IconCache[key] = CropToPng(sheet, Conversation.FirstFrame(sheet, npc.type));
				return key;
			}
			catch (Exception ex)
			{
				this.Mod.Logger.Debug($"Couldn't build art for NPC {npc.type}: {ex.Message}");
				this.IconCache[key] = Array.Empty<byte>();
				return null;
			}
		}

		/// <summary>As <see cref="EnsureItemIcon"/>, for a buff's 32x32 icon.</summary>
		private string EnsureBuffIcon(int type)
		{
			string key = "b" + type;
			if (this.IconCache.ContainsKey(key))
				return this.IconCache[key].Length > 0 ? key : null;

			try
			{
				// unlike item sprites there's no lazy loader for buffs; asking for the value loads it if it isn't already
				Texture2D sheet = TextureAssets.Buff[type]?.Value;
				if (sheet == null)
					return null;

				this.IconCache[key] = CropToPng(sheet, sheet.Bounds);
				return key;
			}
			catch (Exception ex)
			{
				this.Mod.Logger.Debug($"Couldn't build an icon for buff {type}: {ex.Message}");
				this.IconCache[key] = Array.Empty<byte>();
				return null;
			}
		}

		/// <summary>Crop the slot backgrounds the second screen's CSS uses out of the game's textures, once per session.</summary>
		private void EnsureUiAssets()
		{
			if (this.UiAssetsReady)
				return;
			this.UiAssetsReady = true;

			foreach ((string key, Func<Asset<Texture2D>> source) in UiAssets)
				this.CacheUiAsset("ui:" + key, source);

			for (int i = 0; i < UiAssetVariants.Length; i++)
				this.CacheUiAsset($"ui:back{i + 1}", UiAssetVariants[i]);
		}

		private void CacheUiAsset(string key, Func<Asset<Texture2D>> source)
		{
			try
			{
				Texture2D texture = source()?.Value;
				if (texture != null)
					this.IconCache[key] = CropToPng(texture, texture.Bounds);
			}
			catch (Exception ex)
			{
				this.Mod.Logger.Warn($"Couldn't load UI sprite '{key}': {ex.Message}");
			}
		}

		/// <summary>Copy a region of a tilesheet into a standalone PNG.</summary>
		/// <remarks>Must run on the game thread: it reads back from a GPU texture.</remarks>
		private static byte[] CropToPng(Texture2D sheet, Rectangle source)
		{
			source = Rectangle.Intersect(source, sheet.Bounds);
			if (source.Width <= 0 || source.Height <= 0)
				return Array.Empty<byte>();

			var pixels = new Color[source.Width * source.Height];
			sheet.GetData(0, source, pixels, 0, pixels.Length);

			var rgba = new byte[pixels.Length * 4];
			for (int i = 0; i < pixels.Length; i++)
			{
				Color pixel = pixels[i];

				// XNA surfaces are premultiplied; undo it so the browser composites the sprite the way the game does
				rgba[i * 4] = pixel.A == 0 ? (byte)0 : (byte)Math.Min(255, pixel.R * 255 / pixel.A);
				rgba[i * 4 + 1] = pixel.A == 0 ? (byte)0 : (byte)Math.Min(255, pixel.G * 255 / pixel.A);
				rgba[i * 4 + 2] = pixel.A == 0 ? (byte)0 : (byte)Math.Min(255, pixel.B * 255 / pixel.A);
				rgba[i * 4 + 3] = pixel.A;
			}

			return Png.Encode(rgba, source.Width, source.Height);
		}

		/*********
		** Commands from the touch screen
		*********/
		private void DrainActions()
		{
			int budget = 16;
			while (budget-- > 0 && this.PendingActions.TryDequeue(out ActionDto action))
			{
				try
				{
					this.ApplyAction(action);
				}
				catch (Exception ex)
				{
					this.Mod.Logger.Warn($"Couldn't apply '{action.Type}' from the second screen: {ex.Message}");
				}
			}
		}

		private void ApplyAction(ActionDto action)
		{
			Player player = Main.LocalPlayer;
			Item[] items = player.inventory;
			DualScreenConfig config = this.Config;

			// With LAN access on by default, anything on the network can post here — so each class of
			// action is gated separately, letting the screen be made look-only without giving it up.
			if (!config.AllowInventoryEdit && (action.Type == "swap" || action.Type == "sort"))
				return;
			if (!config.AllowQuickUse && (action.Type == "heal" || action.Type == "mana" || action.Type == "buff" || action.Type == "mount"))
				return;

			switch (action.Type)
			{
				case "select":
					// only the hotbar can be held; deeper slots have to be swapped up first
					if (action.Index >= 0 && action.Index < 10)
						player.selectedItem = action.Index;
					break;

				case "swap":
					if (InRange(items, action.Index) && InRange(items, action.To) && action.Index != action.To)
					{
						(items[action.Index], items[action.To]) = (items[action.To], items[action.Index]);
						SyncSlot(player, action.Index);
						SyncSlot(player, action.To);
					}
					break;

				case "drop":
					if (config.AllowDrop && InRange(items, action.Index) && !items[action.Index].IsAir)
					{
						// cloned rather than respawned by type, so prefixes and modded item data survive the trip
						player.QuickSpawnItem(player.GetSource_DropAsItem(), items[action.Index], items[action.Index].stack);
						items[action.Index] = new Item();
						SyncSlot(player, action.Index);
					}
					break;

				case "trash":
					if (config.AllowTrash && InRange(items, action.Index) && !items[action.Index].IsAir)
					{
						items[action.Index] = new Item();
						SyncSlot(player, action.Index);
					}
					break;

				case "sort":
					ItemSorting.SortInventory();
					break;

				// the vanilla quick-use methods, which pick the right item themselves and play the right animation
				case "heal":
					player.QuickHeal();
					break;

				case "mana":
					player.QuickMana();
					break;

				case "buff":
					player.QuickBuff();
					break;

				case "mount":
					player.QuickMount();
					break;

				case "buy":
					this.Buy(player, config, action.Index);
					break;

				case "mapmode":
					this.MapMode = action.Mode == "world" ? "world" : "local";
					break;

				default:
					this.Mod.Logger.Debug($"Ignoring unknown action '{action.Type}'.");
					break;
			}
		}

		/// <summary>
		/// Buy one slot from the shop the player has open.
		/// </summary>
		/// <remarks>
		/// Deliberately conservative: it only works while a shop is genuinely open on the PC, it re-reads
		/// the price at the moment of purchase rather than trusting anything the screen sent, and it
		/// refuses when the coins aren't there. The screen sends a slot, never a price.
		/// </remarks>
		private void Buy(Player player, DualScreenConfig config, int slot)
		{
			if (!config.AllowShopping || Main.npcShop <= 0)
				return;

			Item[] stock;
			try
			{
				stock = Main.instance.shop[Main.npcShop].item;
			}
			catch (Exception)
			{
				return;
			}

			if (slot < 0 || slot >= stock.Length)
				return;

			Item offer = stock[slot];
			if (offer == null || offer.IsAir || offer.type <= 0)
				return;

			int price = offer.GetStoreValue();
			if (Conversation.CountCoins(player) < price)
				return;

			if (!player.BuyItem(price))
				return;

			Item bought = offer.Clone();
			bought.stack = offer.stack > 0 ? offer.stack : 1;
			bought.position = player.Center;

			Item leftover = player.GetItem(Main.myPlayer, bought, GetItemSettings.InventoryEntityToPlayerInventorySettings);
			if (leftover != null && !leftover.IsAir)
				player.QuickSpawnItem(player.GetSource_DropAsItem(), leftover, leftover.stack);

			this.Mod.Logger.Debug($"Second screen bought {bought.Name} for {price} copper.");
		}

		private static bool InRange(Item[] items, int index)
		{
			return index >= 0 && index < items.Length && items[index] != null;
		}

		/// <summary>Tell the server about a slot we just changed. A no-op in single player.</summary>
		private static void SyncSlot(Player player, int slot)
		{
			if (Main.netMode == NetmodeID.MultiplayerClient)
				NetMessage.SendData(MessageID.SyncEquipment, -1, -1, null, player.whoAmI, slot, player.inventory[slot].prefix);
		}

		/*********
		** HTTP routing (thread pool)
		*********/
		private HttpResponse HandleRequest(HttpRequest request)
		{
			if (request.Method == "OPTIONS")
				return HttpResponse.Text(string.Empty);

			string path = request.Path;

			if (path == "/state")
				return HttpResponse.Json(this.StateJson);

			if (path == "/minimap")
				return HttpResponse.Json(this.MinimapJson);

			if (path == "/progress")
				return HttpResponse.Json(this.ProgressJson);

			if (path == "/craftable")
				return HttpResponse.Json(this.CraftJson);

			if (path == "/talk")
				return HttpResponse.Json(this.TalkJson);

			if (path.StartsWith("/icon/", StringComparison.Ordinal))
				return this.ServeCachedPng(TrimPng(path.Substring("/icon/".Length)));

			if (path.StartsWith("/asset/", StringComparison.Ordinal))
				return this.ServeCachedPng("ui:" + TrimPng(path.Substring("/asset/".Length)));

			if (path == "/action" && request.Method == "POST")
			{
				ActionDto action = JsonConvert.DeserializeObject<ActionDto>(request.Body);
				if (action?.Type == null)
					return new HttpResponse { Status = 400, Body = Encoding.UTF8.GetBytes("{\"ok\":false}"), ContentType = "application/json" };

				this.PendingActions.Enqueue(action);
				return HttpResponse.Json("{\"ok\":true}");
			}

			return this.ServeStaticFile(path);
		}

		/// <summary>Serve a sprite that was already rendered on the game thread. Sprites never change, so let the browser keep them.</summary>
		private HttpResponse ServeCachedPng(string key)
		{
			if (!this.IconCache.TryGetValue(key, out byte[] png) || png.Length == 0)
				return HttpResponse.NotFound();

			HttpResponse response = HttpResponse.Bytes(png, "image/png");
			response.CacheControl = "public, max-age=86400";
			return response;
		}

		private static string TrimPng(string key)
		{
			return key.EndsWith(".png", StringComparison.Ordinal)
				? key.Substring(0, key.Length - 4)
				: key;
		}

		/// <summary>
		/// Serve the second-screen page, from the packed mod file — or from a folder on disk if one is configured,
		/// so the UI can be edited without rebuilding the mod.
		/// </summary>
		private HttpResponse ServeStaticFile(string path)
		{
			if (path == "/")
				path = "/index.html";

			// reject anything that could climb out of the web root
			if (path.Contains("..") || path.Contains(':'))
				return HttpResponse.NotFound();

			string relative = path.TrimStart('/');
			string contentType = GuessContentType(relative);

			string overridePath = this.Config.WebRootOverride;
			if (!string.IsNullOrWhiteSpace(overridePath))
			{
				string root = Path.GetFullPath(overridePath);
				string full = Path.GetFullPath(Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar)));
				if (full.StartsWith(root, StringComparison.OrdinalIgnoreCase) && File.Exists(full))
					return HttpResponse.Bytes(File.ReadAllBytes(full), contentType);
			}

			return this.WebFiles.TryGetValue("web/" + relative, out byte[] bytes)
				? HttpResponse.Bytes(bytes, contentType)
				: HttpResponse.NotFound();
		}

		/// <summary>
		/// Read the whole second-screen page into memory while the mod file is still open.
		/// </summary>
		/// <remarks>
		/// This used to read out of the .tmod on each request, which is only legal while tModLoader has the
		/// archive open — it isn't, once loading has finished, and the first request then failed with
		/// "File not open" and served nothing at all. Publishing to the Workshop is what exposed it, because
		/// that closes and reopens the file underneath a running game.
		/// <para>
		/// Caching also removes an archive decompress from every request, and it is a few hundred KB. The
		/// <c>WebRootOverride</c> path still reads from disk per request, which is the point of it: editing
		/// the UI without rebuilding needs the file re-read every time.
		/// </para>
		/// </remarks>
		private void CacheWebFiles()
		{
			foreach (string name in this.Mod.GetFileNames())
			{
				if (!name.StartsWith("web/", StringComparison.Ordinal))
					continue;

				try
				{
					this.WebFiles[name] = this.Mod.GetFileBytes(name);
				}
				catch (Exception ex)
				{
					this.Mod.Logger.Warn($"Couldn't read {name} out of the mod file: {ex.Message}");
				}
			}

			if (!this.WebFiles.ContainsKey("web/index.html"))
				this.Mod.Logger.Error("The second-screen page is missing from the mod file. The server will start but serve nothing.");
		}

		private static string GuessContentType(string path)
		{
			return Path.GetExtension(path).ToLowerInvariant() switch
			{
				".html" => "text/html; charset=utf-8",
				".js" => "text/javascript; charset=utf-8",
				".css" => "text/css; charset=utf-8",
				".json" => "application/json; charset=utf-8",
				".png" => "image/png",
				".svg" => "image/svg+xml",
				_ => "application/octet-stream"
			};
		}
	}
}
