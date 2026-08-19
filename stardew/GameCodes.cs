using System;
using System.Globalization;
using StardewModdingAPI;
using StardewValley;

namespace AynDualScreen
{
    /// <summary>
    /// Game codes: the optional half of the mod that can change the game rather than describe it.
    /// </summary>
    /// <remarks>
    /// Kept entirely apart from the dashboard. The second screen's normal endpoints — <c>/state</c>,
    /// <c>/action</c>, the sprites, the map — are untouched by anything here, so a player who leaves
    /// <see cref="ModConfig.EnableGameCodes"/> off has exactly the mod they had before: a local
    /// dashboard that reads the game and rearranges the inventory, and nothing that can alter a save
    /// in a way they did not ask for.
    ///
    /// Off by default, deliberately. A feature that can hand you a million gold should be something
    /// you switched on, not something that arrived with an update.
    ///
    /// Everything runs on the game thread through the mod's existing action queue. Stardew is not
    /// thread-safe and the web server answers on its own thread; writing to the farmer from there
    /// would corrupt a save eventually rather than immediately, which is the worst kind of bug.
    /// </remarks>
    internal static class GameCodes
    {
        /// <summary>The catalogue served at <c>/codes</c>, or an empty list when the feature is off.</summary>
        /// <remarks>
        /// Built fresh each time rather than cached: whether a code is available depends on whether a
        /// save is loaded, and a cached "unavailable" would outlive the title screen.
        /// </remarks>
        public static string CatalogueJson(ModConfig config)
        {
            if (!config.EnableGameCodes)
                return "{\"enabled\":false,\"codes\":[]}";

            // The one condition every code here shares. Said once, on each code, so the app can grey
            // them out with a reason rather than failing when they are pressed.
            string blocked = Context.IsWorldReady ? "" : "Load a save first";

            string codes = string.Join(",", new[]
            {
                Code("heal", "Restore Health", "Back to full health.", "player", "♥", "NONE", blocked, secret: "HEALME"),
                Code("energy", "Restore Energy", "Back to full stamina.", "player", "⚡", "NONE", blocked),
                Code("money_add", "Add Gold", "Adds to what you are carrying.", "inventory", "◆", "NUMBER", blocked,
                     min: 1, max: 100000, secret: "MONEY"),
                Code("money_set", "Set Gold", "Sets the exact amount.", "inventory", "◇", "NUMBER", blocked,
                     min: 0, max: 10000000, confirm: true),
                Code("time_set", "Set Time", "Moves the clock, within the day.", "world", "◴", "NUMBER", blocked,
                     min: 600, max: 2600, confirm: true),
                Code("weather_set", "Set Tomorrow's Weather", "Decides what tomorrow brings.", "world", "☁", "PRESET", blocked,
                     choices: new[] { "sunny", "rain", "storm", "snow", "wind" }),
            });

            return "{\"enabled\":true,\"codes\":[" + codes + "]}";
        }

        /// <summary>One catalogue entry. Hand-built rather than serialised: it is a fixed shape and this avoids a DTO per field.</summary>
        private static string Code(
            string id, string name, string description, string category, string icon, string input,
            string blocked, int min = 0, int max = 0, string[] choices = null, bool confirm = false,
            string secret = "")
        {
            string list = choices == null
                ? ""
                : ",\"choices\":[\"" + string.Join("\",\"", choices) + "\"]";

            return "{"
                + "\"id\":\"" + id + "\","
                + "\"name\":\"" + name + "\","
                + "\"description\":\"" + description + "\","
                + "\"category\":\"" + category + "\","
                + "\"icon\":\"" + icon + "\","
                + "\"input\":\"" + input + "\","
                + "\"command\":\"" + id + "\","
                + "\"min\":" + min + ","
                + "\"max\":" + max + ","
                + "\"confirm\":" + (confirm ? "true" : "false") + ","
                + "\"secret\":\"" + secret + "\","
                + "\"blocked\":\"" + blocked + "\""
                + list
                + "}";
        }

        /// <summary>
        /// Run one. Called on the game thread, from the mod's existing action pump.
        /// </summary>
        /// <returns>Null when it worked, or the reason it did not.</returns>
        /// <remarks>
        /// Both switches are re-checked here even though the endpoint checked them already. The queue
        /// can outlive the request by a frame or two, and a code that was in flight when the player
        /// switched the feature off should not still land.
        /// </remarks>
        public static string Apply(ModConfig config, IMonitor monitor, string command, string value)
        {
            if (!config.EnableGameCodes)
                return "Game codes are switched off in this mod's config";

            if (!Context.IsWorldReady)
                return "No save is loaded";

            Farmer who = Game1.player;
            if (who == null)
                return "No player";

            switch (command)
            {
                case "heal":
                    who.health = who.maxHealth;
                    return null;

                case "energy":
                    who.stamina = who.MaxStamina;
                    return null;

                case "money_add":
                {
                    if (!TryAmount(value, out int amount))
                        return "That is not a number";

                    // Clamped rather than rejected: the app already limits the field, and a player who
                    // reaches this with something absurd wants gold, not a lecture.
                    amount = Math.Max(1, Math.Min(amount, 100000));
                    who.Money += amount;
                    monitor.Log($"Game code: added {amount}g", LogLevel.Info);
                    return null;
                }

                case "money_set":
                {
                    if (!TryAmount(value, out int exact))
                        return "That is not a number";

                    who.Money = Math.Max(0, Math.Min(exact, 10000000));
                    return null;
                }

                case "time_set":
                {
                    if (!TryAmount(value, out int time))
                        return "That is not a number";

                    // Stardew's clock is 600-2600 in ten-minute steps. Anything else confuses the
                    // game's own scheduling rather than simply looking odd.
                    time = Math.Max(600, Math.Min(time, 2600));
                    time -= time % 10;

                    Game1.timeOfDay = time;
                    return null;
                }

                case "weather_set":
                    return SetWeather(value);

                default:
                    return "Unknown code";
            }
        }

        /// <summary>Tomorrow's weather, not today's: changing today's mid-day leaves the world half-lit.</summary>
        private static string SetWeather(string value)
        {
            switch ((value ?? "").ToLowerInvariant())
            {
                case "sunny": Game1.weatherForTomorrow = Game1.weather_sunny; return null;
                case "rain": Game1.weatherForTomorrow = Game1.weather_rain; return null;
                case "storm": Game1.weatherForTomorrow = Game1.weather_lightning; return null;
                case "snow": Game1.weatherForTomorrow = Game1.weather_snow; return null;
                case "wind": Game1.weatherForTomorrow = Game1.weather_debris; return null;
                default: return "Unknown weather";
            }
        }

        private static bool TryAmount(string value, out int amount) =>
            int.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out amount);
    }
}
