# Meo Camera — Windows tree.
#
# CAMERA_BUILD_PLAN.md §9.3 decided the Windows app is one native process, so
# there is no Python here and no cross-language IPC. ADR 0005 makes Windows the
# first desktop platform.
#
# The frame bridge and the camera core build on POSIX as well as Windows. That
# is not a portability goal — the product is Windows-only until ADR 0004
# unblocks — it exists so the concurrency and slate logic can be tested on the
# development Mac (ADR 0006).

cmake_minimum_required(VERSION 3.20)
project(MeoCamera CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(MSVC)
  # /W4 rather than /Wall: /Wall enables per-object padding warnings that fire
  # on every Windows SDK header. Warnings are errors because §9.2's filter runs
  # inside the user's meeting app.
  add_compile_options(/W4 /WX /permissive- /EHsc)
  add_compile_definitions(UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
else()
  add_compile_options(-Wall -Wextra -Werror)
endif()

# ---------------------------------------------------------------------------
# MeoFrameBridge — the shared-memory hand-off (ADR 0006).
# ---------------------------------------------------------------------------

if(WIN32)
  set(BRIDGE_PLATFORM_SOURCE MeoFrameBridge/src/Mapping_win32.cpp)
else()
  set(BRIDGE_PLATFORM_SOURCE MeoFrameBridge/src/Mapping_posix.cpp)
endif()

add_library(MeoFrameBridge STATIC
  MeoFrameBridge/src/FrameBridge.cpp
  ${BRIDGE_PLATFORM_SOURCE}
)
target_include_directories(MeoFrameBridge
  PUBLIC MeoFrameBridge/include
  PRIVATE MeoFrameBridge/src
)
if(NOT WIN32)
  # shm_open lives in librt on Linux; it is in libc on macOS.
  if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_link_libraries(MeoFrameBridge PUBLIC rt)
  endif()
endif()

# ---------------------------------------------------------------------------
# MeoCameraCore — everything a virtual-camera backend does that is not COM.
#
# Kept separate from the backends so the Media Foundation source and, if ADR
# 0002 says one is needed, the DirectShow filter share one implementation of
# the live-frame-or-slate decision rather than drifting apart.
# ---------------------------------------------------------------------------

add_library(MeoCameraCore STATIC
  MeoVirtualCameraMF/src/Slate.cpp
  MeoVirtualCameraMF/src/FrameSource.cpp
)
target_include_directories(MeoCameraCore PUBLIC MeoVirtualCameraMF/src)
target_link_libraries(MeoCameraCore PUBLIC MeoFrameBridge)

# ---------------------------------------------------------------------------
# MeoVirtualCameraMF — the Windows frame-server COM source (§9.4).
# ---------------------------------------------------------------------------

if(WIN32)
  add_library(MeoVirtualCameraSource SHARED
    MeoVirtualCameraMF/src/VirtualCamera.cpp
    MeoVirtualCameraMF/src/MeoVirtualCameraSource.def
  )
  target_include_directories(MeoVirtualCameraSource PRIVATE
    MeoVirtualCameraMF/src
  )
  target_link_libraries(MeoVirtualCameraSource PRIVATE
    MeoCameraCore
    mfplat
    mf
    mfuuid
    mfsensorgroup
    ole32
    oleaut32
    runtimeobject
  )

  add_executable(MeoVirtualCameraManager
    MeoVirtualCameraMF/src/VirtualCameraManager.cpp
  )
  target_include_directories(MeoVirtualCameraManager PRIVATE
    MeoVirtualCameraMF/src
  )
  target_link_libraries(MeoVirtualCameraManager PRIVATE
    mfplat
    mf
    mfuuid
    mfsensorgroup
    ole32
    runtimeobject
  )

  add_executable(VirtualCameraSourceTests
    Tests/VirtualCameraSourceTests.cpp
  )
  target_include_directories(VirtualCameraSourceTests PRIVATE
    MeoVirtualCameraMF/src
  )
  target_link_libraries(VirtualCameraSourceTests PRIVATE
    mfplat
    mf
    mfuuid
    mfreadwrite
    ole32
  )
  add_dependencies(VirtualCameraSourceTests MeoVirtualCameraSource)
  add_test(NAME VirtualCameraSource COMMAND VirtualCameraSourceTests)
endif()

# ---------------------------------------------------------------------------
# Tests. These run on Windows and on the development Mac.
# ---------------------------------------------------------------------------

enable_testing()

foreach(test_name FrameBridge Slate FrameSource)
  add_executable(${test_name}Tests Tests/${test_name}Tests.cpp)
  target_link_libraries(${test_name}Tests PRIVATE MeoCameraCore)
  # Tests reach into Mapping.h directly to reach states a correct writer never
  # produces — a crashed host mid-write, a fuzzed header.
  target_include_directories(${test_name}Tests PRIVATE MeoFrameBridge/src)
  add_test(NAME ${test_name} COMMAND ${test_name}Tests)
endforeach()

# ---------------------------------------------------------------------------
# Not yet written, listed so the shape of the tree is not a surprise:
#
#   MeoCameraHost/       the receiver, decode, and native UI (§9.3).
#   MeoVirtualCameraDS/  the DirectShow filter, IF ADR 0002 says it is needed.
#   MeoInstaller/        install, repair, uninstall (§9.6). ADR 0003 measured
#                        that HKLM COM registration needs one install-time UAC.
# ---------------------------------------------------------------------------
