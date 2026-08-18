#pragma once

// CommonLibSSE-NG is a large set of headers and every translation unit here needs most of it, so
// it goes in a precompiled header. Nothing project-specific belongs in this file -- a change here
// rebuilds everything.

#include <RE/Skyrim.h>
#include <SKSE/SKSE.h>

#include <atomic>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

using namespace std::literals;
