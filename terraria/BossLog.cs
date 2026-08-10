using System;
using System.Collections.Generic;
using Terraria;
using Terraria.ID;
using Terraria.ModLoader;
using Terraria.ModLoader.IO;

namespace AynDualScreen
{
	/// <summary>
	/// A record of which modded bosses this world has seen off.
	/// </summary>
	/// <remarks>
	/// Vanilla bosses each have a <c>downed…</c> flag the game keeps for us; modded ones have no such
	/// convention, so tracking a kill is the only way to know. Defeats are keyed by the boss's display
	/// name because one fight is often several NPC types — killing any part flagged as the boss counts,
	/// which is the same thing the player means by "I beat it".
	/// <para>
	/// Stored in the world file rather than the player's, since progression belongs to the world. A world
	/// played before this mod was installed starts empty and fills in from the next kill onwards; where
	/// Boss Checklist is present, <see cref="ModIntegration.ChecklistDowned"/> covers that gap.
	/// </para>
	/// </remarks>
	public class BossLog : ModSystem
	{
		/// <summary>Display names of modded bosses defeated in the loaded world.</summary>
		private static readonly HashSet<string> Defeated = new(StringComparer.Ordinal);

		/// <summary>Bumped on every change, so the snapshot builder can tell when the checklist is stale.</summary>
		public static int Revision { get; private set; }

		public static bool IsDefeated(string bossName)
		{
			lock (Defeated)
				return Defeated.Contains(bossName);
		}

		public static void Record(string bossName)
		{
			if (string.IsNullOrWhiteSpace(bossName))
				return;

			lock (Defeated)
			{
				if (!Defeated.Add(bossName))
					return;
			}

			Revision++;
		}

		public override void OnWorldLoad()
		{
			lock (Defeated)
				Defeated.Clear();

			Revision++;
		}

		public override void OnWorldUnload()
		{
			lock (Defeated)
				Defeated.Clear();

			Revision++;
		}

		public override void SaveWorldData(TagCompound tag)
		{
			lock (Defeated)
				tag["aynDefeatedBosses"] = new List<string>(Defeated);
		}

		public override void LoadWorldData(TagCompound tag)
		{
			lock (Defeated)
			{
				Defeated.Clear();

				if (tag.ContainsKey("aynDefeatedBosses"))
				{
					foreach (string name in tag.GetList<string>("aynDefeatedBosses"))
						Defeated.Add(name);
				}
			}

			Revision++;
		}
	}

	/// <summary>Notices modded bosses dying, so <see cref="BossLog"/> can remember them.</summary>
	/// <remarks>
	/// <c>OnKill</c> runs on the game thread as part of the NPC's death, which is exactly where reading
	/// game state is safe.
	/// </remarks>
	public class BossKillWatcher : GlobalNPC
	{
		public override void OnKill(NPC npc)
		{
			if (npc.type < NPCID.Count)
				return; // vanilla keeps its own flags, and they're more accurate than watching for a corpse

			if (!npc.boss && !NPCID.Sets.ShouldBeCountedAsBoss[npc.type])
				return;

			BossLog.Record(npc.FullName);
		}
	}
}
