using System;
using System.Collections.Generic;
using System.Linq;
using StardewValley;
using StardewValley.Locations;

namespace AynDualScreen
{
    /// <summary>
    /// The villager tracker and the community centre board.
    /// </summary>
    /// <remarks>
    /// Both read the live save rather than any table of our own, so they stay correct across game
    /// updates and content mods. Everything here touches game state and must run on the game thread.
    /// </remarks>
    internal static class Panels
    {
        /// <summary>Friendship points per heart, as <c>Utility.GetMaximumHeartsForCharacter</c> counts them.</summary>
        private const int PointsPerHeart = 250;

        /// <summary>The community centre rooms, in the order the board shows them.</summary>
        private static readonly string[] RoomOrder =
        {
            "Pantry", "Crafts Room", "Fish Tank", "Boiler Room", "Vault", "Bulletin Board", "Abandoned Joja Mart"
        };

        /*********
        ** Villagers
        *********/
        /// <summary>Where every villager is right now, and how you're getting on with them.</summary>
        public static List<VillagerDto> BuildVillagers(Farmer player, int max)
        {
            var villagers = new List<VillagerDto>();

            try
            {
                foreach (NPC npc in Utility.getAllCharacters())
                {
                    if (npc == null || !npc.IsVillager || npc.IsMonster)
                        continue;

                    player.friendshipData.TryGetValue(npc.Name, out Friendship friendship);
                    int points = friendship?.Points ?? 0;

                    villagers.Add(new VillagerDto
                    {
                        Name = npc.displayName ?? npc.Name,
                        Location = npc.currentLocation?.DisplayName ?? npc.currentLocation?.Name,
                        X = npc.Tile.X,
                        Y = npc.Tile.Y,
                        Hearts = points / PointsPerHeart,
                        MaxHearts = IsCloseTo(friendship) ? 14 : 10,
                        Birthday = IsBirthday(npc),
                        Talked = friendship?.TalkedToToday ?? false,
                        Here = npc.currentLocation != null && Game1.currentLocation != null
                               && npc.currentLocation.NameOrUniqueName == Game1.currentLocation.NameOrUniqueName
                    });
                }
            }
            catch (Exception)
            {
                // a content mod with an unusual character shouldn't take the panel down
            }

            // whoever is standing next to you matters most, then today's birthday, then the closest friends
            villagers.Sort((a, b) =>
            {
                if (a.Here != b.Here)
                    return a.Here ? -1 : 1;
                if (a.Birthday != b.Birthday)
                    return a.Birthday ? -1 : 1;
                return b.Hearts.CompareTo(a.Hearts);
            });

            if (max > 0 && villagers.Count > max)
                villagers.RemoveRange(max, villagers.Count - max);

            return villagers;
        }


        /// <summary>Dating or married raises the heart ceiling from ten to fourteen.</summary>
        private static bool IsCloseTo(Friendship friendship)
        {
            if (friendship == null)
                return false;

            return friendship.IsDating() || friendship.IsMarried() || friendship.IsEngaged();
        }

        private static bool IsBirthday(NPC npc)
        {
            try
            {
                return npc.Birthday_Season == Game1.currentSeason && npc.Birthday_Day == Game1.dayOfMonth;
            }
            catch (Exception)
            {
                return false;
            }
        }

        /*********
        ** Community centre
        *********/
        /// <summary>
        /// The bundle board.
        /// </summary>
        /// <remarks>
        /// Returns <c>Available = false</c> rather than null data when there's no centre to report on —
        /// it hasn't been found yet, or the Joja route was taken — so the screen can say which it is
        /// instead of showing an empty board.
        /// </remarks>
        public static CommunityDto BuildCommunity()
        {
            var dto = new CommunityDto { Rooms = new List<CommunityRoomDto>() };

            CommunityCenter centre;
            try
            {
                centre = Game1.getLocationFromName("CommunityCenter") as CommunityCenter;
            }
            catch (Exception)
            {
                return dto;
            }

            if (centre == null)
                return dto;

            dto.Available = true;

            // bundle data is keyed "Room/Index"; the value's first field is the bundle's name
            var byRoom = new Dictionary<string, List<(int Index, string Name)>>();
            try
            {
                foreach (KeyValuePair<string, string> pair in Game1.netWorldState.Value.BundleData)
                {
                    string[] key = pair.Key.Split('/');
                    if (key.Length < 2 || !int.TryParse(key[1], out int index))
                        continue;

                    string room = key[0];
                    string name = pair.Value.Split('/').FirstOrDefault() ?? $"Bundle {index}";

                    if (!byRoom.TryGetValue(room, out List<(int, string)> list))
                        byRoom[room] = list = new List<(int, string)>();
                    list.Add((index, name));
                }
            }
            catch (Exception)
            {
                return dto;
            }

            foreach (string room in RoomOrder)
            {
                if (!byRoom.TryGetValue(room, out List<(int Index, string Name)> bundles))
                    continue;

                var entry = new CommunityRoomDto { Name = room, Total = bundles.Count, Remaining = new List<string>() };

                foreach ((int index, string name) in bundles)
                {
                    bool done;
                    try
                    {
                        done = centre.isBundleComplete(index);
                    }
                    catch (Exception)
                    {
                        continue;
                    }

                    if (done)
                        entry.Done++;
                    else
                        entry.Remaining.Add(name);
                }

                entry.Complete = entry.Done >= entry.Total && entry.Total > 0;
                dto.Rooms.Add(entry);

                dto.BundlesDone += entry.Done;
                dto.BundlesTotal += entry.Total;
            }

            dto.Complete = dto.BundlesTotal > 0 && dto.BundlesDone >= dto.BundlesTotal;
            return dto;
        }

        /*********
        ** Daily dashboard
        *********/
        /// <summary>Whose birthday it is today.</summary>
        public static List<string> BuildBirthdays()
        {
            var names = new List<string>();

            try
            {
                foreach (NPC npc in Utility.getAllCharacters())
                {
                    if (npc != null && npc.IsVillager && IsBirthday(npc))
                        names.Add(npc.displayName ?? npc.Name);
                }
            }
            catch (Exception)
            {
                // best effort
            }

            return names;
        }

        /// <summary>Today's festival, if there is one.</summary>
        public static string BuildFestival()
        {
            try
            {
                if (Utility.isFestivalDay())
                    return Utility.getSeasonNameFromNumber(Utility.getSeasonNumber(Game1.currentSeason)) is not null
                        ? Game1.CurrentEvent?.FestivalName ?? "Festival today"
                        : "Festival today";
            }
            catch (Exception)
            {
                // not worth failing the snapshot over
            }

            return null;
        }

        /// <summary>The Travelling Cart parks in Cindersap Forest on Fridays and Sundays.</summary>
        public static bool CartToday()
        {
            int day = Game1.dayOfMonth % 7;
            return day == 5 || day == 0;
        }
    }
}
