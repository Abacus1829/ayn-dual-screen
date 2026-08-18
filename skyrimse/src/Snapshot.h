#pragma once

#include <string>

struct Config;

/// The one rule this whole project is built on:
///
///   **Game state is only ever touched on the game thread.**
///
/// Tick() runs there and nowhere else. It reads the player, builds a finished JSON string, and
/// publishes it under a mutex. Current() hands that finished string to whichever worker thread
/// asked for it and never looks at the game at all. Commands posted by the screen go into a queue
/// and are drained by Tick() on the next frame.
///
/// Reading the game from a request thread will appear to work on your machine and corrupt somebody
/// else's save. If a new feature needs data on the second screen, add it to the snapshot -- do not
/// reach for it from the server.
namespace Snapshot
{
	/// Game thread only. Rebuilds the snapshot if enough time has passed, then applies any commands
	/// the screen queued since the last frame.
	void Tick(const Config& config);

	/// Any thread. The most recent finished snapshot, or a "not ready" document before a save is
	/// loaded.
	std::string Current();

	/// Any thread. Parses one command object and queues it. Returns false if it is not a shape we
	/// recognise -- the permission check happens later, on the game thread.
	bool QueueCommand(const std::string& body);

	/// Called when a save is loaded or a new game starts, so a screen polling across the load never
	/// shows the previous character against the new one's name.
	void Reset();
}
