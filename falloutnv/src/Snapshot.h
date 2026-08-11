#pragma once

// The one rule worth remembering: game state may ONLY be touched on the game thread.
//
// The web server answers on worker threads, so it never reads the world. The game thread builds a
// finished JSON string in BuildSnapshot() and publishes it here under a mutex; the server hands
// out whatever the last published string was. Commands go the other way through the same gate:
// the screen queues them, the game thread drains them.
//
// Breaking this rule is the classic way to corrupt a save, so keep new features on the pattern.

#include "Config.h"

#include <mutex>
#include <string>
#include <vector>

namespace Snapshot
{
	/// Called from kMessage_MainGameLoop. Rebuilds the JSON if enough time has passed.
	void Tick(const Config& config);

	/// Called from a server thread. Returns the last published snapshot, or a "not ready" one.
	std::string Current();

	/// Called from a server thread. Parses one command object and queues it for the game thread.
	/// Returns false only if the body wasn't usable at all.
	bool QueueCommand(const std::string& jsonBody);

	/// Called from the game thread, before the snapshot is rebuilt.
	void DrainCommands(const Config& config);

	/// Dropped on load, so a stale save's data can't leak into a new one.
	void Reset();
}
