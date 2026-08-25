using System;
using System.Collections.Generic;
using StardewValley;

namespace AynDualScreen
{
    /// <summary>
    /// The season calendar: four weeks of seven days, with whose birthday it is and what is happening.
    /// </summary>
    /// <remarks>
    /// The shape is fixed at 28 days because a Stardew season is always 28 days laid out as four rows
    /// of seven. Sending it as a fixed grid rather than as "the days that have something on them"
    /// means the client draws a calendar rather than working one out, and an empty Tuesday is an empty
    /// square rather than a gap the layout has to reason about.
    ///
    /// Touches game state; must run on the game thread.
    /// </remarks>
    internal static class CalendarPanel
    {
        public const int DaysPerSeason = 28;

        /// <summary>Build the current season's calendar.</summary>
        /// <param name="portraitFor">Resolves a villager to a cached portrait key, or null when icons are off.</param>
        public static CalendarDto Build(Func<NPC, string> portraitFor)
        {
            var calendar = new CalendarDto
            {
                Season = Game1.currentSeason,
                Year = Game1.year,
                Today = Game1.dayOfMonth,
                Days = new List<CalendarDayDto>(DaysPerSeason)
            };

            // Birthdays first, in one pass over the villagers rather than one pass per day. Twenty-eight
            // walks of the whole cast is the sort of thing that is fine until somebody installs Stardew
            // Valley Expanded.
            var birthdaysByDay = new Dictionary<int, List<NPC>>();
            try
            {
                foreach (NPC npc in Utility.getAllCharacters())
                {
                    if (npc == null || !npc.IsVillager)
                        continue;

                    if (!string.Equals(npc.Birthday_Season, Game1.currentSeason, StringComparison.OrdinalIgnoreCase))
                        continue;

                    int day = npc.Birthday_Day;
                    if (day < 1 || day > DaysPerSeason)
                        continue;

                    if (!birthdaysByDay.TryGetValue(day, out List<NPC> list))
                        birthdaysByDay[day] = list = new List<NPC>();

                    list.Add(npc);
                }
            }
            catch (Exception)
            {
                // A calendar without birthdays still answers "what day is the festival", so carry on.
            }

            for (int day = 1; day <= DaysPerSeason; day++)
            {
                var entry = new CalendarDayDto
                {
                    Day = day,
                    Today = day == Game1.dayOfMonth,
                    Past = day < Game1.dayOfMonth,
                    Cart = IsCartDay(day),
                    Festival = FestivalOn(day),
                    Birthdays = new List<string>(),
                    Portraits = new List<string>()
                };

                if (birthdaysByDay.TryGetValue(day, out List<NPC> npcs))
                {
                    foreach (NPC npc in npcs)
                    {
                        entry.Birthdays.Add(npc.displayName ?? npc.Name);
                        entry.Portraits.Add(portraitFor(npc));
                    }
                }

                calendar.Days.Add(entry);
            }

            return calendar;
        }

        /// <summary>The Travelling Cart visits Cindersap Forest on Fridays and Sundays.</summary>
        /// <remarks>
        /// Day 1 of a season is a Monday, so the day of the month modulo 7 gives the weekday directly:
        /// 5 is Friday and 0 is Sunday. This is the same rule <see cref="Panels.CartToday"/> uses; it
        /// is repeated for an arbitrary day rather than only for today.
        /// </remarks>
        private static bool IsCartDay(int day)
        {
            int weekday = day % 7;
            return weekday == 5 || weekday == 0;
        }

        /// <summary>The festival on a given day of this season, or null.</summary>
        /// <remarks>
        /// Read from the game's own festival data rather than from a hard-coded table, so the Night
        /// Market, a mod's festival, and any future addition all appear without this knowing them.
        /// </remarks>
        private static string FestivalOn(int day)
        {
            try
            {
                string key = $"{Game1.currentSeason}{day}";
                if (Game1.temporaryContent == null)
                    return null;

                Dictionary<string, string> festivals =
                    Game1.temporaryContent.Load<Dictionary<string, string>>("Data\\Festivals\\" + key);

                if (festivals != null && festivals.TryGetValue("name", out string name) && !string.IsNullOrWhiteSpace(name))
                    return name;
            }
            catch (Exception)
            {
                // No festival file for that day is the normal case and arrives as an exception.
            }

            return null;
        }
    }
}
