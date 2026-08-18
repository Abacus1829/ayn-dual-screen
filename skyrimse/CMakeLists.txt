cmake_minimum_required(VERSION 3.21)

# Ayn Dual Screen — Skyrim Special Edition
#
# An SKSE64 plugin. Unlike the Stardew, Terraria and Minecraft mods in this repository this is a
# native DLL, for the same reason the Fallout: New Vegas one is: Skyrim has no managed modding
# framework, and the script extender loads native code. Papyrus cannot open a socket, so there is
# no version of this mod that isn't C++.
#
# It builds against CommonLibSSE-NG, which produces ONE DLL that runs on SE 1.5.97, AE 1.6.x and
# VR. The alternative — a build per runtime — is three DLLs to keep in step and three ways for a
# release to be half-broken.

project(
    AynDualScreen
    VERSION 0.1.0
    DESCRIPTION "Second-screen companion for Skyrim Special Edition"
    LANGUAGES CXX
)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    # SkyrimSE.exe is x64. A 32-bit DLL is one the game physically cannot load, and the failure
    # comes out as a silent no-op rather than an error, so refuse it here instead.
    message(FATAL_ERROR "Skyrim SE is 64-bit. Configure with the x64 toolset (see build.ps1).")
endif()

# CommonLibSSE-NG, built from source rather than taken from a vcpkg port.
#
# This is not a preference, it is a requirement. The vcpkg registries publish CommonLibSSE-NG at
# 3.7.0, which is the May 2023 upstream — and upstream itself stopped in 2024, before Skyrim
# 1.6.1130 and 1.6.1170 existed. Built against that, this plugin loaded and then hung the game
# inside the first read of the player: an address resolved for a runtime the library had never
# seen is a jump into arbitrary code, which hangs rather than failing cleanly.
#
# alandtse/CommonLibSSE-NG is the maintained fork the community moved to, and it knows the current
# runtimes. fetch-deps.ps1 clones it into extern/; nothing is vendored here.
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)

# Skyrim SE and AE. NOT VR.
#
# Two reasons, and the second one is the interesting one.
#
# The plain reason: VR support needs the OpenVR SDK's headers, which would be another dependency
# to fetch and build for a runtime this project has never been tested against.
#
# The better reason: CommonLibSSE can only expose the player's runtime-data block -- PlayerSkills,
# addedSpells, addedPerks, selectedPower -- when the build targets runtimes that agree on where
# those members sit. Ask for all three and it correctly refuses to let you read any of them, which
# is what cost this project the XP bar, the per-skill progress bars, and honest shout-word
# tracking. Dropping VR, which nothing here was ever tested on, buys all of that back.
#
# Turn ENABLE_SKYRIM_VR back on and those readers have to go again. That is the trade, written
# down so it is a decision rather than a surprise.
set(ENABLE_SKYRIM_SE ON  CACHE BOOL "" FORCE)
set(ENABLE_SKYRIM_AE ON  CACHE BOOL "" FORCE)
set(ENABLE_SKYRIM_VR OFF CACHE BOOL "" FORCE)

add_subdirectory(extern/CommonLibSSE-NG)

# add_commonlibsse_plugin() lives in the library's own CMake module, which a find_package() would
# have brought in for us. Taking it by add_subdirectory means including it by hand.
include(extern/CommonLibSSE-NG/cmake/CommonLibSSE.cmake)

add_commonlibsse_plugin(
    ${PROJECT_NAME}
    AUTHOR Abacus
    NAME AynDualScreen
    SOURCES
        src/main.cpp
        src/Actions.cpp
        src/Config.cpp
        src/Describe.cpp
        src/MapData.cpp
        src/Snapshot.cpp
        src/WebServer.cpp
)

target_include_directories(${PROJECT_NAME} PRIVATE src)

target_precompile_headers(${PROJECT_NAME} PRIVATE src/PCH.h)

target_link_libraries(${PROJECT_NAME} PRIVATE ws2_32 iphlpapi)

if(MSVC)
    target_compile_options(
        ${PROJECT_NAME}
        PRIVATE
            /W4
            /permissive-
            /Zc:preprocessor
            /utf-8
            # A warning in this plugin is a wrong read of somebody's save, so they are not
            # advisory. The two below are disabled because CommonLibSSE's own headers trip them.
            /wd4200   # zero-sized array in struct
            /wd4324   # structure padded due to alignment specifier
    )
endif()

# The finished layout, so `cmake --install` drops a folder you can copy straight into Data.
install(TARGETS ${PROJECT_NAME} RUNTIME DESTINATION "SKSE/Plugins")
install(DIRECTORY web/ DESTINATION "SKSE/Plugins/AynDualScreen/web")
