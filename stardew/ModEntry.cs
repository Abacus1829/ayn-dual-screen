using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using Microsoft.Xna.Framework;
using Microsoft.Xna.Framework.Graphics;
using Newtonsoft.Json;
using Newtonsoft.Json.Serialization;
using StardewModdingAPI;
using StardewModdingAPI.Events;
using StardewValley;
using StardewValley.Buildings;
using StardewValley.ItemTypeDefinitions;
using StardewValley.Menus;
using StardewValley.Network;
using StardewValley.Quests;
using StardewValley.TerrainFeatures;
using xTile.Layers;
using SObject = StardewValley.Object;

namespace AynDualScreen
{
    /// <summary>
    /// Publishes a live view of the save (clock, inventory, minimap) over HTTP so a second display can render it,
    /// and applies touch commands sent back from that display.
    /// </summary>
    /// <remarks>
    /// Threading: the web server answers requests on thread pool threads, which must never touch game state. So the
    /// game thread builds immutable JSON snapshots in <see cref="OnUpdateTicked"/> and publishes them to volatile
    /// fields, and incoming commands are queued and drained on the game thread.
    /// </remarks>
    public class ModEntry : Mod
    {
        /// <summary>The tile alphabet used by <see cref="MapDto.Rows"/>; kept in sync with TILE_COLORS in app.js.</summary>
        private const char TileVoid = '.';
        private const char TileGround = 'g';
        private const char TileWater = 'w';
        private const char TileBlocked = 'b';
        private const char TileDirt = 'd';
        private const char TileCrop = 'c';
        private const char TileTree = 't';
        private const char TileFloor = 'f';
        private const char TileGrass = 'r';
        private const char TileObject = 'o';
        private const char TileBuilding = 'B';

        private static readonly JsonSerializerSettings JsonSettings = new()
        {
            ContractResolver = new CamelCasePropertyNamesContractResolver(),
            NullValueHandling = NullValueHandling.Ignore
        };

        /// <summary>
        /// UI sprites cropped out of the game's own tilesheets so the second screen matches the in-game menus.
        /// </summary>
        /// <remarks>
        /// The rectangles mirror what vanilla itself draws: the menu box is the source <c>IClickableMenu.drawTextureBox</c>
        /// defaults to, the slot is standard tile 10 of the menu sheet, and the quality stars are the rects
        /// <c>Object.drawInMenu</c> uses. If one ever looks wrong, <c>/sheet/menu.png</c> and <c>/sheet/cursors.png</c>
        /// serve the full sheets so the coordinates can be re-checked against the real thing.
        /// </remarks>
        private static readonly Dictionary<string, (string Asset, Rectangle Source)> UiAssets = new()
        {
            ["menubox"] = ("Maps/MenuTiles", new Rectangle(0, 256, 60, 60)),
            ["slot"] = ("Maps/MenuTiles", new Rectangle(160, 0, 16, 16)),
            ["quality1"] = ("LooseSprites/Cursors", new Rectangle(338, 400, 8, 8)),
            ["quality2"] = ("LooseSprites/Cursors", new Rectangle(346, 400, 8, 8)),
            ["quality4"] = ("LooseSprites/Cursors", new Rectangle(346, 392, 8, 8))
        };

        /// <summary>Full tilesheets exposed for checking the crops above.</summary>
        private static readonly Dictionary<string, string> UiSheets = new()
        {
            ["menu"] = "Maps/MenuTiles",
            ["cursors"] = "LooseSprites/Cursors"
        };

        private ModConfig Config;
        private WebServer Server;
        private string WebRoot;

        private volatile string StateJson = "{\"ready\":false}";
        private volatile string MapJson = "{\"rev\":0}";

        /// <summary>
        /// The villager tracker and the bundle board, published on their own slow cadence.
        /// </summary>
        /// <remarks>
        /// Both walk every character or every bundle, and neither changes fast enough to belong in a
        /// 10Hz snapshot. The client polls them separately.
        /// </remarks>
        private volatile string VillagerJson = "[]";
        private volatile string CommunityJson = "{\"available\":false}";

        private int TicksSincePanels;

        private readonly ConcurrentQueue<ActionDto> PendingActions = new();
        private readonly ConcurrentDictionary<string, byte[]> IconCache = new();
        private readonly ConcurrentDictionary<string, byte[]> AssetCache = new();
        private bool UiAssetsReady;

        private int MapRev;
        private string MapLocationId = string.Empty;
        private int TicksSinceMap;
        private int TicksSinceState;
        private int StateTickInterval = 6;
        private long TickCount;

        public override void Entry(IModHelper helper)
        {
            this.Config = helper.ReadConfig<ModConfig>();
            this.WebRoot = Path.Combine(helper.DirectoryPath, "web");
            this.StateTickInterval = Math.Max(1, 60 / Math.Clamp(this.Config.UpdatesPerSecond, 1, 60));

            helper.Events.GameLoop.GameLaunched += this.OnGameLaunched;
            helper.Events.GameLoop.UpdateTicked += this.OnUpdateTicked;
            helper.Events.GameLoop.ReturnedToTitle += this.OnReturnedToTitle;
        }

        public override object GetApi()
        {
            return null;
        }

        /*********
        ** Lifecycle
        *********/
        private void OnGameLaunched(object sender, GameLaunchedEventArgs e)
        {
            IPAddress address = this.Config.AllowLanAccess ? IPAddress.Any : IPAddress.Loopback;

            try
            {
                this.Server = new WebServer(address, this.Config.Port, this.HandleRequest, message => this.Monitor.Log(message, LogLevel.Trace));
                this.Server.Start();
            }
            catch (Exception ex)
            {
                this.Monitor.Log($"Couldn't start the second-screen server on port {this.Config.Port}: {ex.Message}", LogLevel.Error);
                this.Monitor.Log("Change \"Port\" in the mod's config.json and restart the game.", LogLevel.Error);
                this.Server = null;
                return;
            }

            this.AnnounceAddresses();
        }

        /// <summary>
        /// Print the addresses the second screen can actually be opened at.
        /// </summary>
        /// <remarks>
        /// Naming only localhost here was actively misleading: it's correct on this PC, but typing it into a
        /// phone or handheld points that device at itself, so the connection fails for a reason the console
        /// gave no hint about. When LAN access is on, the address another device needs is the one worth
        /// printing; when it's off, the point worth making is that no other device can connect at all.
        /// </remarks>
        private void AnnounceAddresses()
        {
            this.Monitor.Log($"Second screen ready on this PC at http://localhost:{this.Config.Port}/", LogLevel.Info);

            if (!this.Config.AllowLanAccess)
            {
                this.Monitor.Log("Only this PC can connect. To use a phone, tablet or handheld as the second screen, set \"AllowLanAccess\": true in this mod's config.json and restart the game.", LogLevel.Info);
                return;
            }

            (List<string> addresses, bool vpnActive) = LocalNetworkAddresses();
            if (addresses.Count == 0)
            {
                this.Monitor.Log("LAN access is on, but no network address was found. Is this PC connected to a network?", LogLevel.Warn);
            }
            else
            {
                this.Monitor.Log($"From another device on your network: http://{addresses[0]}:{this.Config.Port}/", LogLevel.Info);
                for (int i = 1; i < addresses.Count; i++)
                    this.Monitor.Log($"   ...or, if that one doesn't work: http://{addresses[i]}:{this.Config.Port}/", LogLevel.Info);
            }

            if (vpnActive)
                this.Monitor.Log("A VPN adapter is active. VPNs routinely block or reroute local network traffic, so if the handheld can't connect, try disconnecting the VPN first.", LogLevel.Warn);

            this.Monitor.Log("LAN access is enabled: anyone on your network can move and destroy your items. Only use this on a network you trust.", LogLevel.Warn);
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

        private void OnReturnedToTitle(object sender, ReturnedToTitleEventArgs e)
        {
            this.StateJson = "{\"ready\":false}";
            this.MapLocationId = string.Empty;
            this.IconCache.Clear();
        }

        private void OnUpdateTicked(object sender, UpdateTickedEventArgs e)
        {
            if (this.Server == null)
                return;

            if (!Context.IsWorldReady || Game1.currentLocation == null || Game1.player == null)
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

            // villagers walk and bundles get filled at human speed; twice a second is plenty
            if (++this.TicksSincePanels >= 30)
            {
                this.TicksSincePanels = 0;
                this.TryRebuild(this.RebuildPanels, "panels");
            }

            // the map grid is expensive, so only rebuild it on a location change or every ~5 seconds (crops grow, chests move)
            this.TicksSinceMap++;
            if (Game1.currentLocation.NameOrUniqueName != this.MapLocationId || this.TicksSinceMap >= 300)
            {
                this.TicksSinceMap = 0;
                this.TryRebuild(this.RebuildMap, "map");
            }
        }

        /// <summary>Run a snapshot builder, logging rather than crashing the game if it trips over an odd location.</summary>
        private void TryRebuild(Action build, string label)
        {
            try
            {
                build();
            }
            catch (Exception ex)
            {
                this.Monitor.Log($"Failed to build {label} snapshot: {ex.Message}", LogLevel.Debug);
            }
        }

        /*********
        ** Snapshots (game thread only)
        *********/
        private void RebuildState()
        {
            Farmer player = Game1.player;
            GameLocation location = Game1.currentLocation;

            var inventory = new List<SlotDto>(player.Items.Count);
            for (int i = 0; i < player.Items.Count; i++)
            {
                Item item = player.Items[i];
                if (item == null)
                {
                    inventory.Add(new SlotDto { Index = i });
                    continue;
                }

                inventory.Add(new SlotDto
                {
                    Index = i,
                    Name = item.DisplayName,
                    Stack = item.Stack,
                    Quality = (item as SObject)?.Quality ?? 0,
                    Category = item.getCategoryName(),
                    IconKey = this.Config.EnableItemIcons ? this.EnsureIcon(item) : null,
                    Edible = item is SObject edible && edible.Edibility > -300
                });
            }

            var state = new StateDto
            {
                Ready = true,
                Tick = this.TickCount,

                LocationId = location.NameOrUniqueName,
                LocationName = location.DisplayName ?? location.Name,
                MapRev = this.MapRev,

                TimeOfDay = Game1.timeOfDay,
                DayOfMonth = Game1.dayOfMonth,
                DayOfWeek = Game1.shortDayNameFromDayOfSeason(Game1.dayOfMonth),
                Season = Game1.season.ToString(),
                Year = Game1.year,
                Weather = DescribeWeather(location),

                Money = player.Money,
                Stamina = player.Stamina,
                MaxStamina = player.MaxStamina,
                Health = player.health,
                MaxHealth = player.maxHealth,

                X = player.Position.X / Game1.tileSize,
                Y = player.Position.Y / Game1.tileSize,
                Facing = player.FacingDirection,

                SelectedSlot = player.CurrentToolIndex,
                HotbarSize = 12,
                WeatherTomorrow = DescribeWeatherTomorrow(),
                DailyLuck = player.DailyLuck,
                Birthdays = Panels.BuildBirthdays(),
                Festival = Panels.BuildFestival(),
                CartToday = Panels.CartToday(),
                Can = new PermissionsDto
                {
                    Trash = this.Config.AllowTrash,
                    Drop = this.Config.AllowDrop,
                    Edit = this.Config.AllowInventoryEdit,
                    Eat = this.Config.AllowEat
                },
                Inventory = inventory,
                Entities = this.BuildEntities(location),
                Quests = this.BuildQuests(player),
                Skills = new SkillsDto
                {
                    Farming = player.FarmingLevel,
                    Mining = player.MiningLevel,
                    Foraging = player.ForagingLevel,
                    Fishing = player.FishingLevel,
                    Combat = player.CombatLevel
                }
            };

            this.StateJson = JsonConvert.SerializeObject(state, JsonSettings);
        }

        private void RebuildPanels()
        {
            this.VillagerJson = JsonConvert.SerializeObject(
                Panels.BuildVillagers(Game1.player, this.Config.MaxVillagers), JsonSettings);
            this.CommunityJson = JsonConvert.SerializeObject(Panels.BuildCommunity(), JsonSettings);
        }

        private static string DescribeWeather(GameLocation location)
        {
            if (Game1.IsSnowingHere(location))
                return "snow";
            if (Game1.IsLightningHere(location))
                return "storm";
            if (Game1.IsGreenRainingHere(location))
                return "greenrain";
            if (Game1.IsRainingHere(location))
                return "rain";
            if (Game1.IsDebrisWeatherHere(location))
                return "wind";
            return "sun";
        }

        /// <summary>Tomorrow's forecast for the valley.</summary>
        /// <remarks>
        /// Read through <c>netWorldState</c> rather than a global: since 1.6 the weather is per-location
        /// context, and "Default" is the one the farm and town share — which is what a forecast means to
        /// anyone planning their next day.
        /// </remarks>
        private static string DescribeWeatherTomorrow()
        {
            try
            {
                LocationWeather weather = Game1.netWorldState.Value.GetWeatherForLocation("Default");
                return weather?.WeatherForTomorrow switch
                {
                    null => "sun",
                    "Sun" => "sun",
                    "Rain" => "rain",
                    "Storm" => "storm",
                    "Snow" => "snow",
                    "Wind" => "wind",
                    "Festival" => "festival",
                    "GreenRain" => "greenrain",
                    string other => other.ToLowerInvariant()
                };
            }
            catch
            {
                return null; // a forecast is a nicety; never let it take the snapshot down
            }
        }

        /// <summary>The active journal entries, most urgent first.</summary>
        private List<QuestDto> BuildQuests(Farmer player)
        {
            var quests = new List<QuestDto>();

            foreach (Quest quest in player.questLog)
            {
                if (quest == null)
                    continue;

                quests.Add(new QuestDto
                {
                    Name = quest.GetName(),
                    Objective = quest.currentObjective,
                    DaysLeft = quest.daysLeft.Value > 0 ? quest.daysLeft.Value : -1,
                    Complete = quest.completed.Value
                });
            }

            // a quest about to expire is the one worth seeing; untimed ones can wait
            quests.Sort((a, b) =>
            {
                int left = a.DaysLeft < 0 ? int.MaxValue : a.DaysLeft;
                int right = b.DaysLeft < 0 ? int.MaxValue : b.DaysLeft;
                return left.CompareTo(right);
            });

            int cap = Math.Max(0, this.Config.MaxQuests);
            if (quests.Count > cap)
                quests.RemoveRange(cap, quests.Count - cap);

            return quests;
        }

        private List<EntityDto> BuildEntities(GameLocation location)
        {
            var entities = new List<EntityDto>();

            foreach (NPC npc in location.characters)
            {
                if (npc == null)
                    continue;
                if (!this.Config.ShowMonsters && npc.IsMonster)
                    continue;
                if (!this.Config.ShowNpcs && !npc.IsMonster)
                    continue;

                entities.Add(new EntityDto
                {
                    Kind = npc.IsMonster ? "monster" : "npc",
                    Name = npc.displayName ?? npc.Name,
                    X = npc.Position.X / Game1.tileSize,
                    Y = npc.Position.Y / Game1.tileSize
                });
            }

            foreach (FarmAnimal animal in location.animals.Values)
            {
                if (!this.Config.ShowAnimals)
                    break;

                entities.Add(new EntityDto
                {
                    Kind = "animal",
                    Name = animal.displayName ?? animal.Name,
                    X = animal.Position.X / Game1.tileSize,
                    Y = animal.Position.Y / Game1.tileSize
                });
            }

            foreach (Farmer farmer in location.farmers)
            {
                if (farmer == Game1.player)
                    continue;

                entities.Add(new EntityDto
                {
                    Kind = "farmer",
                    Name = farmer.Name,
                    X = farmer.Position.X / Game1.tileSize,
                    Y = farmer.Position.Y / Game1.tileSize
                });
            }

            return entities;
        }

        private void RebuildMap()
        {
            GameLocation location = Game1.currentLocation;
            Layer back = location.Map?.GetLayer("Back");
            if (back == null)
                return;

            Layer buildingsLayer = location.Map.GetLayer("Buildings");
            int width = back.LayerWidth;
            int height = back.LayerHeight;

            // build as char arrays so overlays can be painted in place, then freeze to strings
            var grid = new char[height][];
            for (int y = 0; y < height; y++)
            {
                var row = new char[width];
                for (int x = 0; x < width; x++)
                {
                    if (back.Tiles[x, y] == null)
                    {
                        row[x] = TileVoid;
                        continue;
                    }

                    if (location.isWaterTile(x, y))
                        row[x] = TileWater;
                    else if (buildingsLayer?.Tiles[x, y] != null)
                        row[x] = TileBlocked;
                    else
                        row[x] = TileGround;
                }
                grid[y] = row;
            }

            // overlays: iterating the feature dictionaries is far cheaper than probing every tile
            foreach (KeyValuePair<Vector2, TerrainFeature> pair in location.terrainFeatures.Pairs)
            {
                char code = pair.Value switch
                {
                    HoeDirt dirt => dirt.crop != null ? TileCrop : TileDirt,
                    Tree => TileTree,
                    FruitTree => TileTree,
                    Bush => TileTree,
                    Flooring => TileFloor,
                    Grass => TileGrass,
                    _ => TileGround
                };
                Paint(grid, (int)pair.Key.X, (int)pair.Key.Y, code);
            }

            foreach (KeyValuePair<Vector2, SObject> pair in location.objects.Pairs)
                Paint(grid, (int)pair.Key.X, (int)pair.Key.Y, TileObject);

            foreach (Building building in location.buildings)
            {
                for (int y = 0; y < building.tilesHigh.Value; y++)
                {
                    for (int x = 0; x < building.tilesWide.Value; x++)
                        Paint(grid, building.tileX.Value + x, building.tileY.Value + y, TileBuilding);
                }
            }

            var rows = new List<string>(height);
            for (int y = 0; y < height; y++)
                rows.Add(new string(grid[y]));

            var warps = new List<WarpDto>();
            foreach (Warp warp in location.warps)
                warps.Add(new WarpDto { X = warp.X, Y = warp.Y, Target = warp.TargetName });

            this.MapRev++;
            this.MapLocationId = location.NameOrUniqueName;
            this.MapJson = JsonConvert.SerializeObject(
                new MapDto
                {
                    Rev = this.MapRev,
                    LocationId = location.NameOrUniqueName,
                    LocationName = location.DisplayName ?? location.Name,
                    Width = width,
                    Height = height,
                    Rows = rows,
                    Warps = warps
                },
                JsonSettings
            );
        }

        private static void Paint(char[][] grid, int x, int y, char code)
        {
            if (y >= 0 && y < grid.Length && x >= 0 && x < grid[y].Length)
                grid[y][x] = code;
        }

        /*********
        ** Item icons
        *********/
        /// <summary>Crop an item's sprite out of its tilesheet and cache it as a PNG, returning the key to fetch it with.</summary>
        /// <remarks>Must run on the game thread: it reads from and allocates GPU textures.</remarks>
        private string EnsureIcon(Item item)
        {
            string id = item.QualifiedItemId;
            if (string.IsNullOrEmpty(id))
                return null;

            string key = EncodeKey(id);
            if (this.IconCache.ContainsKey(key))
                return key;

            try
            {
                ParsedItemData data = ItemRegistry.GetDataOrErrorItem(id);
                Texture2D sheet = data.GetTexture();
                Rectangle source = data.GetSourceRect();
                if (sheet == null || source.Width <= 0 || source.Height <= 0)
                    return null;

                this.IconCache[key] = CropToPng(sheet, source);
                return key;
            }
            catch (Exception ex)
            {
                this.Monitor.Log($"Couldn't build an icon for {id}: {ex.Message}", LogLevel.Trace);
                this.IconCache[key] = Array.Empty<byte>(); // don't retry every tick
                return null;
            }
        }

        /// <summary>Copy a region of a tilesheet into a standalone PNG.</summary>
        /// <remarks>Must run on the game thread: it reads from and allocates GPU textures.</remarks>
        private static byte[] CropToPng(Texture2D sheet, Rectangle source)
        {
            var pixels = new Color[source.Width * source.Height];
            sheet.GetData(0, source, pixels, 0, pixels.Length);

            using var sprite = new Texture2D(Game1.graphics.GraphicsDevice, source.Width, source.Height);
            sprite.SetData(pixels);

            using var stream = new MemoryStream();
            sprite.SaveAsPng(stream, source.Width, source.Height);
            return stream.ToArray();
        }

        /// <summary>Crop the menu sprites the second screen's CSS uses out of the game's tilesheets, once per session.</summary>
        private void EnsureUiAssets()
        {
            if (this.UiAssetsReady)
                return;
            this.UiAssetsReady = true;

            foreach (KeyValuePair<string, (string Asset, Rectangle Source)> pair in UiAssets)
            {
                try
                {
                    Texture2D sheet = this.Helper.GameContent.Load<Texture2D>(pair.Value.Asset);
                    this.AssetCache[pair.Key] = CropToPng(sheet, pair.Value.Source);
                }
                catch (Exception ex)
                {
                    this.Monitor.Log($"Couldn't load UI sprite '{pair.Key}' from {pair.Value.Asset}: {ex.Message}", LogLevel.Warn);
                }
            }

            foreach (KeyValuePair<string, string> pair in UiSheets)
            {
                try
                {
                    Texture2D sheet = this.Helper.GameContent.Load<Texture2D>(pair.Value);
                    this.AssetCache["sheet:" + pair.Key] = CropToPng(sheet, sheet.Bounds);
                }
                catch (Exception ex)
                {
                    this.Monitor.Log($"Couldn't dump sheet '{pair.Key}': {ex.Message}", LogLevel.Trace);
                }
            }
        }

        private static string EncodeKey(string value)
        {
            return Convert.ToBase64String(Encoding.UTF8.GetBytes(value))
                .Replace('+', '-')
                .Replace('/', '_')
                .TrimEnd('=');
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
                    this.Monitor.Log($"Couldn't apply '{action.Type}' from the second screen: {ex.Message}", LogLevel.Warn);
                }
            }
        }

        private void ApplyAction(ActionDto action)
        {
            Farmer player = Game1.player;
            IList<Item> items = player.Items;

            switch (action.Type)
            {
                case "select":
                    // only the hotbar can be the held item; deeper slots have to be swapped up first
                    if (action.Index >= 0 && action.Index < 12 && action.Index < items.Count)
                        player.CurrentToolIndex = action.Index;
                    break;

                case "swap":
                    if (this.Config.AllowInventoryEdit && InRange(items, action.Index) && InRange(items, action.To) && action.Index != action.To)
                    {
                        (items[action.Index], items[action.To]) = (items[action.To], items[action.Index]);
                    }
                    break;

                case "drop":
                    if (this.Config.AllowDrop && InRange(items, action.Index) && items[action.Index] != null)
                    {
                        Item item = items[action.Index];
                        items[action.Index] = null;
                        Game1.createItemDebris(item, player.Position, player.FacingDirection);
                    }
                    break;

                case "trash":
                    if (this.Config.AllowTrash && InRange(items, action.Index))
                        items[action.Index] = null;
                    break;

                case "eat":
                    // restricted to the hotbar: the vanilla eat animation consumes the *held* item when it finishes,
                    // so eating from a deeper slot would leave the stack behind
                    if (this.Config.AllowEat
                        && action.Index >= 0 && action.Index < 12 && InRange(items, action.Index)
                        && items[action.Index] is SObject food && food.Edibility > -300
                        && !player.isEating && !Game1.eventUp)
                    {
                        player.CurrentToolIndex = action.Index;
                        player.eatObject(food);
                    }
                    break;

                case "sort":
                    if (this.Config.AllowInventoryEdit)
                        ItemGrabMenu.organizeItemsInList(player.Items);
                    break;

                default:
                    this.Monitor.Log($"Ignoring unknown action '{action.Type}'.", LogLevel.Trace);
                    break;
            }
        }

        private static bool InRange(IList<Item> items, int index)
        {
            return index >= 0 && index < items.Count;
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

            if (path == "/map")
                return HttpResponse.Json(this.MapJson);

            if (path == "/villagers")
                return HttpResponse.Json(this.VillagerJson);

            if (path == "/community")
                return HttpResponse.Json(this.CommunityJson);

            if (path.StartsWith("/asset/", StringComparison.Ordinal))
                return this.ServeCachedPng(this.AssetCache, TrimPng(path.Substring("/asset/".Length)));

            if (path.StartsWith("/sheet/", StringComparison.Ordinal))
                return this.ServeCachedPng(this.AssetCache, "sheet:" + TrimPng(path.Substring("/sheet/".Length)));

            if (path.StartsWith("/icon/", StringComparison.Ordinal))
            {
                return this.ServeCachedPng(this.IconCache, TrimPng(path.Substring("/icon/".Length)));
            }

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

        private static string TrimPng(string key)
        {
            return key.EndsWith(".png", StringComparison.Ordinal)
                ? key.Substring(0, key.Length - 4)
                : key;
        }

        /// <summary>Serve a sprite that was already rendered on the game thread. Sprites never change, so let the browser keep them.</summary>
        private HttpResponse ServeCachedPng(ConcurrentDictionary<string, byte[]> cache, string key)
        {
            if (!cache.TryGetValue(key, out byte[] png) || png.Length == 0)
                return HttpResponse.NotFound();

            HttpResponse response = HttpResponse.Bytes(png, "image/png");
            response.CacheControl = "public, max-age=86400";
            return response;
        }

        private HttpResponse ServeStaticFile(string path)
        {
            if (path == "/")
                path = "/index.html";

            // reject anything that could climb out of the web root
            if (path.Contains("..") || path.Contains(':'))
                return HttpResponse.NotFound();

            string relative = path.TrimStart('/').Replace('/', Path.DirectorySeparatorChar);
            string fullPath = Path.GetFullPath(Path.Combine(this.WebRoot, relative));
            if (!fullPath.StartsWith(this.WebRoot, StringComparison.OrdinalIgnoreCase) || !File.Exists(fullPath))
                return HttpResponse.NotFound();

            return HttpResponse.Bytes(File.ReadAllBytes(fullPath), GuessContentType(fullPath));
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
