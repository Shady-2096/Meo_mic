cmake_minimum_required(VERSION 3.21)
project(MeoWindowsVirtualCameraProbe CXX)

# Throwaway feasibility code for CAMERA_BUILD_PLAN.md 18.1 and 18.3.
# Not part of any shippable artifact.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT WIN32)
  message(FATAL_ERROR
    "These probes are Windows-only. They exist to measure Windows frame "
    "server behaviour and cannot be built or answered anywhere else.")
endif()

if(NOT MSVC)
  message(FATAL_ERROR
    "Build with MSVC. The probe uses WRL and the Media Foundation virtual "
    "camera headers, which ship with the Windows SDK and Visual Studio.")
endif()

add_compile_definitions(UNICODE _UNICODE)

# The media source, loaded by the Windows frame server out of process.
add_library(MeoProbeSource SHARED
  src/Common.cpp
  src/ProbeActivate.cpp
  src/ProbeSource.cpp
  src/ProbeStream.cpp
  src/DllMain.cpp
  src/MeoProbeSource.def
)

target_link_libraries(MeoProbeSource PRIVATE
  mfplat
  mf
  mfuuid
  mfreadwrite
  ole32
  oleaut32
  runtimeobject
)

set_target_properties(MeoProbeSource PROPERTIES
  OUTPUT_NAME "MeoProbeSource"
)

# The host that asks Windows to publish the virtual camera.
add_executable(MeoProbeHost
  host/Main.cpp
  src/Common.cpp
)

add_executable(MeoProbeReader
  host/CaptureOneFrame.cpp
  src/Common.cpp
)

target_link_libraries(MeoProbeHost PRIVATE
  mfplat
  mf
  mfuuid
  mfreadwrite
  mfsensorgroup   # MFCreateVirtualCamera lives here
  ole32
  oleaut32
  runtimeobject
)

target_link_libraries(MeoProbeReader PRIVATE
  mfplat
  mf
  mfuuid
  mfreadwrite
  mfsensorgroup   # MFCreateVirtualCamera lives here
  ole32
  oleaut32
  runtimeobject
)

# Keep both next to each other; the registration scripts assume it.
set_target_properties(MeoProbeSource MeoProbeHost MeoProbeReader PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY_DEBUG   "${CMAKE_BINARY_DIR}/out"
  RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/out"
  LIBRARY_OUTPUT_DIRECTORY_DEBUG   "${CMAKE_BINARY_DIR}/out"
  LIBRARY_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/out"
)
