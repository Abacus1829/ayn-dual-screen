using System;
using System.Collections.Generic;
using Microsoft.Xna.Framework;
using Microsoft.Xna.Framework.Graphics;
using Terraria;
using Terraria.GameContent;

namespace AynDualScreen
{
	/// <summary>
	/// What's on screen while the player is talking to a town NPC.
	/// </summary>
	/// <remarks>
	/// The second screen is a good place for this: on the PC the shop window covers the world, whereas
	/// down here it can sit beside it. Everything is read from the live conversation state, so closing
	/// the dialogue on the PC clears it here too.
	/// <para>Game data only; must be called on the game thread.</para>
	/// </remarks>
	internal static class Conversation
	{
		/// <summary>Build the current conversation, or null when nobody is being talked to.</summary>
		/// <param name="iconFor">Callback that caches a sprite and returns its key.</param>
		public static TalkDto Build(Player player, DualScreenConfig config, Func<Item, string> iconFor, Func<NPC, string> artFor)
		{
			int index = player.talkNPC;
			if (index < 0 || index >= Main.maxNPCs)
				return null;

			NPC npc = Main.npc[index];
			if (npc == null || !npc.active)
				return null;

			var dto = new TalkDto
			{
				Name = npc.GivenOrTypeName,
				ArtKey = config.EnableItemIcons ? artFor(npc) : null,
				Dialogue = Clean(Main.npcChatText),
				CanBuy = config.AllowShopping
			};

			// Main.npcShop is 0 when no shop window is open; the array is only valid above that
			if (Main.npcShop > 0)
			{
				dto.ShopOpen = true;
				dto.Shop = ReadShop(player, config, iconFor);
			}

			return dto;
		}

		private static List<ShopItemDto> ReadShop(Player player, DualScreenConfig config, Func<Item, string> iconFor)
		{
			var items = new List<ShopItemDto>();

			Item[] stock;
			try
			{
				stock = Main.instance.shop[Main.npcShop].item;
			}
			catch (Exception)
			{
				return items;
			}

			long purse = CountCoins(player);

			for (int i = 0; i < stock.Length; i++)
			{
				Item item = stock[i];
				if (item == null || item.IsAir || item.type <= 0)
					continue;

				int price = item.GetStoreValue();

				items.Add(new ShopItemDto
				{
					Slot = i,
					Name = item.Name,
					Stack = item.stack,
					Rare = item.rare,
					IconKey = config.EnableItemIcons ? iconFor(item) : null,
					Price = price,
					PriceText = DescribeCoins(price),
					Affordable = purse >= price
				});
			}

			return items;
		}

		/// <summary>Everything in the player's purse, in copper.</summary>
		public static long CountCoins(Player player)
		{
			long total = 0;

			foreach (Item item in player.inventory)
			{
				if (item == null || item.IsAir)
					continue;

				total += item.type switch
				{
					Terraria.ID.ItemID.CopperCoin => item.stack,
					Terraria.ID.ItemID.SilverCoin => 100L * item.stack,
					Terraria.ID.ItemID.GoldCoin => 10_000L * item.stack,
					Terraria.ID.ItemID.PlatinumCoin => 1_000_000L * item.stack,
					_ => 0
				};
			}

			return total;
		}

		/// <summary>Copper into the platinum/gold/silver/copper wording the game uses.</summary>
		public static string DescribeCoins(long copper)
		{
			if (copper <= 0)
				return "free";

			long platinum = copper / 1_000_000;
			long gold = copper / 10_000 % 100;
			long silver = copper / 100 % 100;
			long remainder = copper % 100;

			var parts = new List<string>();
			if (platinum > 0)
				parts.Add($"{platinum}p");
			if (gold > 0)
				parts.Add($"{gold}g");
			if (silver > 0)
				parts.Add($"{silver}s");
			if (remainder > 0 && platinum == 0 && gold == 0)
				parts.Add($"{remainder}c");

			return string.Join(" ", parts);
		}

		/// <summary>Strip the game's inline colour and item tags out of a chat line.</summary>
		private static string Clean(string text)
		{
			if (string.IsNullOrWhiteSpace(text))
				return null;

			// tags look like [c/ffffff:words] or [i:17]; keep the words, drop the markup
			var builder = new System.Text.StringBuilder(text.Length);
			for (int i = 0; i < text.Length; i++)
			{
				if (text[i] != '[')
				{
					builder.Append(text[i]);
					continue;
				}

				int close = text.IndexOf(']', i);
				if (close < 0)
					break;

				string tag = text.Substring(i + 1, close - i - 1);
				int colon = tag.IndexOf(':');
				if (colon >= 0 && tag.StartsWith("c/", StringComparison.Ordinal))
					builder.Append(tag.Substring(colon + 1));

				i = close;
			}

			return builder.ToString().Trim();
		}

		/// <summary>The first frame of an NPC's walking sprite, to use as character art.</summary>
		/// <remarks>Must run on the game thread: it reads back from a GPU texture.</remarks>
		public static Rectangle FirstFrame(Texture2D sheet, int type)
		{
			int frames = Math.Max(1, Main.npcFrameCount[type]);
			return new Rectangle(0, 0, sheet.Width, sheet.Height / frames);
		}
	}
}
