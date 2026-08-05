using Terraria.ModLoader;

namespace AynDualScreen
{
	/// <summary>
	/// A second-screen HUD for Terraria: the mod publishes a live view of the player over HTTP and a browser on any
	/// other display renders it as a 3DS-style bottom screen.
	/// </summary>
	/// <remarks>All the work lives in <see cref="DualScreenSystem"/>; this type only exists because tModLoader needs it.</remarks>
	public class AynDualScreen : Mod
	{
	}
}
