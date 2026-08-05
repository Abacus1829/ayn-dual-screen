using System;
using System.Collections.Generic;
using Terraria;
using Terraria.GameContent.Events;

namespace AynDualScreen
{
	/// <summary>
	/// The boss and event checklist, read from the world's own "downed" flags.
	/// </summary>
	/// <remarks>
	/// Every entry is a live read of a static the game already keeps, so this is always right for the
	/// loaded world with nothing to track ourselves. Order follows the usual progression rather than
	/// the order the flags happen to be declared in — the point of a checklist is to show what's next.
	/// <para>Game data only; must be called on the game thread.</para>
	/// </remarks>
	internal static class Progress
	{
		/// <summary>Bosses in progression order, with the flag that says whether they're down.</summary>
		private static readonly (string Name, Func<bool> Done, bool Hardmode)[] Bosses =
		{
			("King Slime", () => NPC.downedSlimeKing, false),
			("Eye of Cthulhu", () => NPC.downedBoss1, false),
			("Eater of Worlds / Brain of Cthulhu", () => NPC.downedBoss2, false),
			("Queen Bee", () => NPC.downedQueenBee, false),
			("Skeletron", () => NPC.downedBoss3, false),
			("Deerclops", () => NPC.downedDeerclops, false),
			("Wall of Flesh", () => Main.hardMode, false),

			("Queen Slime", () => NPC.downedQueenSlime, true),
			("The Destroyer", () => NPC.downedMechBoss1, true),
			("The Twins", () => NPC.downedMechBoss2, true),
			("Skeletron Prime", () => NPC.downedMechBoss3, true),
			("Plantera", () => NPC.downedPlantBoss, true),
			("Golem", () => NPC.downedGolemBoss, true),
			("Duke Fishron", () => NPC.downedFishron, true),
			("Empress of Light", () => NPC.downedEmpressOfLight, true),
			("Lunatic Cultist", () => NPC.downedAncientCultist, true),
			("Moon Lord", () => NPC.downedMoonlord, true)
		};

		/// <summary>Invasions and the moon events, which sit outside the boss ladder.</summary>
		private static readonly (string Name, Func<bool> Done, bool Hardmode)[] Events =
		{
			("Goblin Army", () => NPC.downedGoblins, false),
			("Old One's Army", () => DD2Event.DownedInvasionAnyDifficulty, false),
			("Frost Legion", () => NPC.downedFrost, false),
			("Pirate Invasion", () => NPC.downedPirates, true),
			("Solar Eclipse", () => NPC.downedHalloweenTree || Main.hardMode && NPC.downedPlantBoss, true),
			("Pumpking", () => NPC.downedHalloweenKing, true),
			("Ice Queen", () => NPC.downedChristmasIceQueen, true),
			("Martian Madness", () => NPC.downedMartians, true),
			("Solar Pillar", () => NPC.downedTowerSolar, true),
			("Vortex Pillar", () => NPC.downedTowerVortex, true),
			("Nebula Pillar", () => NPC.downedTowerNebula, true),
			("Stardust Pillar", () => NPC.downedTowerStardust, true)
		};

		public static ProgressDto Build()
		{
			var bosses = Read(Bosses);
			var events = Read(Events);

			int done = 0;
			foreach (ProgressEntryDto entry in bosses)
			{
				if (entry.Done)
					done++;
			}

			return new ProgressDto
			{
				Bosses = bosses,
				Events = events,
				BossesDone = done,
				BossesTotal = bosses.Count,
				HardMode = Main.hardMode
			};
		}

		private static List<ProgressEntryDto> Read((string Name, Func<bool> Done, bool Hardmode)[] source)
		{
			var entries = new List<ProgressEntryDto>(source.Length);

			foreach ((string name, Func<bool> done, bool hardmode) in source)
			{
				bool value;
				try
				{
					value = done();
				}
				catch (Exception)
				{
					continue; // a flag that isn't in this tModLoader version simply doesn't get listed
				}

				entries.Add(new ProgressEntryDto { Name = name, Done = value, Hardmode = hardmode });
			}

			return entries;
		}
	}
}
