#pragma once

#if defined(_WIN32)

#include <windows.h>

// {50C1DBA3-D9BE-4CE8-AC44-15D7999783A5}
inline constexpr GUID CLSID_MeoVirtualCameraSource = {
    0x50c1dba3,
    0xd9be,
    0x4ce8,
    {0xac, 0x44, 0x15, 0xd7, 0x99, 0x97, 0x83, 0xa5}};

namespace meo {

inline constexpr wchar_t kVirtualCameraFriendlyName[] = L"Meo Camera";
inline constexpr UINT32 kVirtualCameraWidth = 1280;
inline constexpr UINT32 kVirtualCameraHeight = 720;
inline constexpr UINT32 kVirtualCameraFrameRate = 30;
inline constexpr DWORD kVirtualCameraFrameBytes =
    kVirtualCameraWidth * kVirtualCameraHeight * 3 / 2;
inline constexpr LONGLONG kVirtualCameraFrameDuration =
    10'000'000LL / kVirtualCameraFrameRate;

}  // namespace meo

#endif
