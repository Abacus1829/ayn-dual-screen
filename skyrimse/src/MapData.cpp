#include "PCH.h"

#include "MapData.h"

#include "Config.h"
#include "Json.h"

#include <chrono>
#include <cmath>

// Where the map comes from.
//
// Skyrim's world map is a rendered 3D scene, not a texture this mod could serve -- there is no
// "map.dds" to extract, and pretending otherwise was the first thing tried here. So the screen
// draws its own map instead: the worldspace's extents give it a coordinate space, the markers give
// it landmarks, and your position and bearing go on top. That is a chart of where things are
// rather than a picture of Skyrim, and the page says so rather than passing one off as the other.
//
// A picture of the terrain is possible in principle -- Skyrim's LOD textures are in the archives --
// and it is written down in the README as the next real piece of work on this tab.

namespace
{
	/// Every marker in the worldspace we last looked at, so a fast-travel command can be checked
	/// against the same set the screen was shown rather than re-walking the world on a tap.
	///
	/// Game thread only, like everything else here.
	std::vector<RE::TESObjectREFR*> g_markers;

	RE::ExtraMapMarker* MarkerDataOf(RE::TESObjectREFR* ref)
	{
		return ref ? ref->extraList.GetByType<RE::ExtraMapMarker>() : nullptr;
	}

	bool IsVisible(RE::ExtraMapMarker* marker)
	{
		return marker && marker->mapData &&
			marker->mapData->flags.all(RE::MapMarkerData::Flag::kVisible);
	}

	bool CanTravelTo(RE::ExtraMapMarker* marker)
	{
		return marker && marker->mapData &&
			marker->mapData->flags.all(RE::MapMarkerData::Flag::kCanTravelTo);
	}

	/// The worldspace the player's position is expressed in. Indoors there is none: an interior
	/// cell has its own local coordinates that mean nothing on a map of Skyrim, which is why the
	/// screen shows the cell name and holds the last outdoor position rather than drawing you at
	/// the origin.
	RE::TESWorldSpace* CurrentWorldSpace(RE::PlayerCharacter* player)
	{
		auto* cell = player->GetParentCell();
		if (!cell || cell->IsInteriorCell())
			return nullptr;
		return cell->GetRuntimeData().worldSpace;
	}

	/// The worldspace the marker list was built for, and how long ago.
	///
	/// Walking the persistent cell is by far the most expensive thing this plugin does: Tamriel's
	/// holds tens of thousands of references, and the first version of this file walked all of them
	/// on every snapshot, ten times a second, on the game thread. That was the single biggest
	/// contributor to the hang.
	///
	/// Markers do not move. The only thing that changes is a flag when you discover one, so this
	/// rebuilds when you change worldspace, and otherwise every few seconds to pick up discoveries.
	RE::TESWorldSpace* g_markersFor = nullptr;
	std::chrono::steady_clock::time_point g_markersBuilt{};
	constexpr auto kMarkerRefresh = std::chrono::seconds(5);

	void CollectMarkers(RE::TESWorldSpace* world, int limit)
	{
		g_markers.clear();
		if (!world || !world->persistentCell)
			return;

		// Map markers are persistent references parked in the worldspace's persistent cell. That
		// is the only place they live -- there is no marker list to read -- so this walks the cell
		// and keeps the references that carry marker data.
		auto* cell = world->persistentCell;
		RE::BSSpinLockGuard guard(cell->GetRuntimeData().spinLock);

		for (const auto& pointer : cell->GetRuntimeData().references)
		{
			if (static_cast<int>(g_markers.size()) >= limit)
				break;

			auto* ref = pointer.get();
			auto* marker = MarkerDataOf(ref);
			if (!marker || !IsVisible(marker))
				continue;                       // undiscovered, or not a marker at all

			g_markers.push_back(ref);
		}
	}
}

void MapData::Write(Json& j, RE::PlayerCharacter* player, const Config& config)
{
	auto* world = CurrentWorldSpace(player);
	auto* cell = player->GetParentCell();

	j.BeginObject("map");
	j.Str("world", world && world->GetName() ? world->GetName() : "");
	if (world)
		j.Form("worldId", world->GetFormID());
	else
		j.Str("worldId", "");
	j.Str("cell", cell && cell->GetName() ? cell->GetName() : "");
	j.Bool("interior", world == nullptr);

	const auto position = player->GetPosition();
	j.Num("x", position.x);
	j.Num("y", position.y);

	// The game measures the player's heading in radians, counter-clockwise from east. A compass
	// bearing is degrees clockwise from north, which is what the screen prints next to the arrow,
	// so the conversion happens here rather than in three places in the page.
	const double radians = player->GetAngleZ();
	double bearing = radians * 180.0 / 3.14159265358979323846;
	bearing = std::fmod(bearing, 360.0);
	if (bearing < 0.0)
		bearing += 360.0;
	j.Num("angle", bearing);

	if (world)
	{
		// The worldspace's extents, converted to the same units as the player's position, so the
		// page can place everything with one linear mapping.
		//
		// The game stores them as the north-west and south-east CELL coordinates, not as world
		// units -- a cell being 4096 units square is the conversion, and it is the one constant in
		// this file that comes from the engine rather than from data.
		constexpr double kCellSize = 4096.0;
		const auto& bounds = world->worldMapData;

		j.BeginObject("worldBounds");
		j.Num("minX", bounds.nwCellX * kCellSize);
		j.Num("minY", bounds.seCellY * kCellSize);
		j.Num("maxX", bounds.seCellX * kCellSize);
		j.Num("maxY", bounds.nwCellY * kCellSize);
		j.EndObject();

		const auto now = std::chrono::steady_clock::now();
		if (world != g_markersFor || now - g_markersBuilt >= kMarkerRefresh)
		{
			CollectMarkers(world, config.maxMapMarkers);
			g_markersFor = world;
			g_markersBuilt = now;
		}
	}
	else
	{
		// Indoors. The list is kept rather than cleared: the markers of the worldspace outside are
		// still the right ones to show, and throwing them away would mean paying for the walk again
		// on every door.
	}

	j.BeginArray("markers");
	for (auto* ref : g_markers)
	{
		auto* marker = MarkerDataOf(ref);
		if (!marker || !marker->mapData)
			continue;

		const auto& data = *marker->mapData;
		const char* name = data.locationName.GetFullName();
		if (!name || !*name)
			continue;                           // an unnamed marker is nothing to draw

		const auto at = ref->GetPosition();

		j.BeginObject();
		j.Form("id", ref->GetFormID());
		j.Str("name", name);
		j.Int("type", static_cast<int>(data.type.get()));
		j.Num("x", at.x);
		j.Num("y", at.y);
		j.Bool("visited", CanTravelTo(marker));
		j.Bool("canFastTravel", CanTravelTo(marker));
		j.EndObject();
	}
	j.EndArray();

	j.EndObject();
}

RE::TESObjectREFR* MapData::FindTravellableMarker(std::uint32_t formId)
{
	for (auto* ref : g_markers)
	{
		if (!ref || ref->GetFormID() != formId)
			continue;

		// Checked again here, not trusted from the snapshot. Between the screen drawing a marker
		// and somebody tapping it, the flags can have changed -- and the screen's copy of them was
		// never the thing that mattered anyway.
		auto* marker = MarkerDataOf(ref);
		if (!IsVisible(marker) || !CanTravelTo(marker))
			return nullptr;

		return ref;
	}

	return nullptr;
}
