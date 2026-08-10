using System;
using System.Collections;
using System.Collections.Generic;
using Terraria;
using Terraria.ID;
using Terraria.ModLoader;

namespace AynDualScreen
{
	/// <summary>
	/// Everything this mod knows about the <em>other</em> mods in the load order.
	/// </summary>
	/// <remarks>
	/// Two jobs. The first is a catalogue of every modded boss in the load order, built once at load from
	/// the game's own content samples — no dependency on any particular mod, so a boss list works with
	/// whatever the player happens to have installed. The second is an optional bridge to Boss Checklist,
	/// which knows things we cannot work out for ourselves (a real progression order, and defeats from
	/// before this mod was installed).
	/// <para>
	/// The Boss Checklist bridge talks to another mod through <c>Mod.Call</c>, which is untyped by
	/// definition — the shape of what comes back is a convention, not a contract, and it can change when
	/// that mod updates. So every read here is defensive and any failure is swallowed: the catalogue below
	/// is the thing that has to work, and the bridge is only ever an improvement on top of it.
	/// </para>
	/// <para>Game data only; must be built on the game thread.</para>
	/// </remarks>
	internal static class ModIntegration
	{
		/// <summary>Mods that are always present and say nothing useful about the player's setup.</summary>
		private static readonly HashSet<string> Uninteresting = new(StringComparer.OrdinalIgnoreCase)
		{
			"ModLoader", "Terraria"
		};

		/// <summary>The modded boss catalogue, built once per load. Null until <see cref="Rebuild"/> runs.</summary>
		private static List<ModBossDto> Catalogue;

		/// <summary>The loaded mod list, built once per load.</summary>
		private static List<ModInfoDto> ModList;

		/// <summary>Whether the Boss Checklist bridge has already been tried and found not to work.</summary>
		private static bool ChecklistBridgeBroken;

		/// <summary>Forget everything cached from the previous load order.</summary>
		public static void Reset()
		{
			Catalogue = null;
			ModList = null;
			ChecklistBridgeBroken = false;
		}

		/*********
		** The mod list
		*********/
		/// <summary>Every loaded mod worth naming, alphabetically.</summary>
		public static List<ModInfoDto> LoadedMods()
		{
			if (ModList != null)
				return ModList;

			var mods = new List<ModInfoDto>();

			try
			{
				foreach (Mod mod in ModLoader.Mods)
				{
					if (Uninteresting.Contains(mod.Name))
						continue;

					mods.Add(new ModInfoDto
					{
						Name = mod.DisplayName,
						InternalName = mod.Name,
						Version = mod.Version?.ToString()
					});
				}
			}
			catch (Exception)
			{
				// a mod list is a nicety; never let it take the snapshot down with it
			}

			mods.Sort((a, b) => string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase));
			ModList = mods;
			return mods;
		}

		/*********
		** The modded boss catalogue
		*********/
		/// <summary>
		/// Every boss added by a mod, ordered as closely to progression as we can manage.
		/// </summary>
		/// <remarks>
		/// Built from <see cref="ContentSamples.NpcsByNetId"/>, which holds one fully-initialised instance of
		/// every NPC in the game including modded ones — so this finds bosses from mods that have never heard
		/// of this one, with nothing to keep in sync.
		/// <para>
		/// Bosses are deduplicated by display name because a single fight is routinely several NPC types
		/// (phases, segments, a second form), and the checklist wants one row per fight. Where Boss Checklist
		/// is installed its progression value decides the order; where it isn't, max health stands in, which
		/// is rough but lands most mods' bosses in roughly the right sequence.
		/// </para>
		/// </remarks>
		public static List<ModBossDto> ModdedBosses()
		{
			if (Catalogue != null)
				return Catalogue;

			Catalogue = Rebuild();
			return Catalogue;
		}

		private static List<ModBossDto> Rebuild()
		{
			var byName = new Dictionary<string, ModBossDto>(StringComparer.Ordinal);

			try
			{
				for (int type = NPCID.Count; type < NPCLoader.NPCCount; type++)
				{
					if (!ContentSamples.NpcsByNetId.TryGetValue(type, out NPC npc) || npc == null)
						continue;

					// ShouldBeCountedAsBoss covers the ones that are a boss fight without the flag — the
					// pillars are the vanilla example, and mods copy the pattern for their own multi-part fights
					bool isBoss = npc.boss || NPCID.Sets.ShouldBeCountedAsBoss[type];
					if (!isBoss)
						continue;

					string source = npc.ModNPC?.Mod?.DisplayName;
					if (string.IsNullOrEmpty(source))
						continue; // a vanilla type in the modded range shouldn't happen, but it isn't ours to list

					string name = npc.FullName;
					if (string.IsNullOrWhiteSpace(name))
						continue;

					if (byName.TryGetValue(name, out ModBossDto existing))
					{
						// same fight, another segment: keep the beefiest one, it's the one worth ranking by
						if (npc.lifeMax > existing.LifeMax)
						{
							existing.LifeMax = npc.lifeMax;
							existing.Type = type;
						}

						continue;
					}

					byName[name] = new ModBossDto
					{
						Name = name,
						Source = source,
						Type = type,
						LifeMax = npc.lifeMax,
						Progression = float.MaxValue
					};
				}
			}
			catch (Exception)
			{
				// a mod that misbehaves during content sampling shouldn't cost us the whole list
			}

			var bosses = new List<ModBossDto>(byName.Values);
			ApplyChecklistProgression(bosses);

			bosses.Sort((a, b) =>
			{
				if (Math.Abs(a.Progression - b.Progression) > 0.0001f)
					return a.Progression.CompareTo(b.Progression);

				int byLife = a.LifeMax.CompareTo(b.LifeMax);
				return byLife != 0 ? byLife : string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase);
			});

			return bosses;
		}

		/*********
		** Boss Checklist bridge (optional, best-effort)
		*********/
		/// <summary>
		/// Ask Boss Checklist where each boss sits in progression, if it's installed.
		/// </summary>
		/// <remarks>
		/// Entirely optional. Without it the list is still complete, just ordered by health instead.
		/// </remarks>
		private static void ApplyChecklistProgression(List<ModBossDto> bosses)
		{
			IDictionary info = ChecklistInfo();
			if (info == null)
				return;

			try
			{
				var byName = new Dictionary<string, ModBossDto>(StringComparer.OrdinalIgnoreCase);
				foreach (ModBossDto boss in bosses)
					byName[boss.Name] = boss;

				foreach (DictionaryEntry pair in info)
				{
					if (pair.Value is not IDictionary entry)
						continue;

					string name = ReadString(entry, "displayName") ?? ReadString(entry, "name");
					if (name == null || !byName.TryGetValue(name, out ModBossDto boss))
						continue;

					if (ReadValue(entry, "progression") is float progression)
						boss.Progression = progression;
				}
			}
			catch (Exception)
			{
				ChecklistBridgeBroken = true;
			}
		}

		/// <summary>
		/// What Boss Checklist believes about every boss it knows, by display name.
		/// </summary>
		/// <remarks>
		/// Worth asking because it knows about defeats from before this mod was installed, which our own
		/// tracking cannot. Null when it isn't installed, which is the normal case — and read in one pass
		/// per checklist rebuild rather than once per boss, since each pass walks its whole table.
		/// </remarks>
		public static Dictionary<string, bool> ChecklistDowned()
		{
			IDictionary info = ChecklistInfo();
			if (info == null)
				return null;

			var downed = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);

			try
			{
				foreach (DictionaryEntry pair in info)
				{
					if (pair.Value is not IDictionary entry)
						continue;

					string name = ReadString(entry, "displayName") ?? ReadString(entry, "name");
					if (name == null)
						continue;

					switch (ReadValue(entry, "downed"))
					{
						case Func<bool> check:
							downed[name] = check();
							break;
						case bool flag:
							downed[name] = flag;
							break;
					}
				}
			}
			catch (Exception)
			{
				ChecklistBridgeBroken = true;
				return null;
			}

			return downed;
		}

		/// <summary>Boss Checklist's own boss table, or null if it isn't installed or won't answer.</summary>
		private static IDictionary ChecklistInfo()
		{
			if (ChecklistBridgeBroken)
				return null;

			try
			{
				if (!ModLoader.TryGetMod("BossChecklist", out Mod checklist))
					return null;

				Mod self = ModContent.GetInstance<DualScreenSystem>()?.Mod;
				return checklist.Call("GetBossInfoDict", self, "1.0") as IDictionary;
			}
			catch (Exception)
			{
				// the call convention is a convention, not a contract; if it moved, stop asking
				ChecklistBridgeBroken = true;
				return null;
			}
		}

		private static object ReadValue(IDictionary entry, string key)
		{
			return entry.Contains(key) ? entry[key] : null;
		}

		private static string ReadString(IDictionary entry, string key)
		{
			return ReadValue(entry, key) as string;
		}

		/*********
		** Buffs
		*********/
		/// <summary>Which mod added a buff, or null for a vanilla one.</summary>
		public static string BuffSource(int type)
		{
			if (type < BuffID.Count)
				return null;

			try
			{
				return BuffLoader.GetBuff(type)?.Mod?.DisplayName;
			}
			catch (Exception)
			{
				return null;
			}
		}

		/// <summary>Which mod added an NPC, or null for a vanilla one.</summary>
		public static string NpcSource(NPC npc)
		{
			try
			{
				return npc?.ModNPC?.Mod?.DisplayName;
			}
			catch (Exception)
			{
				return null;
			}
		}

		/// <summary>Which mod added an item, or null for a vanilla one.</summary>
		public static string ItemSource(Item item)
		{
			try
			{
				return item?.ModItem?.Mod?.DisplayName;
			}
			catch (Exception)
			{
				return null;
			}
		}
	}
}
