#if defined(_WIN32)

#include "VirtualCamera.h"

#include <mfapi.h>
#include <mferror.h>
#include <mfvirtualcamera.h>
#include <wrl/client.h>

#include <cstdio>
#include <string>

using Microsoft::WRL::ComPtr;

namespace {

void PrintFailure(const wchar_t* operation, HRESULT hr) {
  wchar_t message[512] = {};
  FormatMessageW(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                 nullptr, static_cast<DWORD>(hr), 0, message,
                 ARRAYSIZE(message), nullptr);
  wprintf(L"%s failed: 0x%08lX %s\n", operation,
          static_cast<unsigned long>(hr), message);
  if (hr == E_ACCESSDENIED) {
    wprintf(
        L"Windows camera privacy is blocking this. Open Settings > Privacy "
        L"& security > Camera and enable camera access for apps.\n");
  }
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
  if (argc != 2 || (std::wstring(argv[1]) != L"--install" &&
                    std::wstring(argv[1]) != L"--remove")) {
    wprintf(L"Usage: MeoVirtualCameraManager.exe --install|--remove\n");
    return 2;
  }
  const bool install = std::wstring(argv[1]) == L"--install";

  HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
  if (FAILED(hr)) {
    PrintFailure(L"CoInitializeEx", hr);
    return 1;
  }
  hr = MFStartup(MF_VERSION);
  if (FAILED(hr)) {
    PrintFailure(L"MFStartup", hr);
    CoUninitialize();
    return 1;
  }

  wchar_t clsid[64] = {};
  StringFromGUID2(CLSID_MeoVirtualCameraSource, clsid, ARRAYSIZE(clsid));
  ComPtr<IMFVirtualCamera> camera;
  hr = MFCreateVirtualCamera(
      MFVirtualCameraType_SoftwareCameraSource,
      MFVirtualCameraLifetime_System, MFVirtualCameraAccess_CurrentUser,
      meo::kVirtualCameraFriendlyName, clsid, nullptr, 0, &camera);
  if (SUCCEEDED(hr)) {
    hr = install ? camera->Start(nullptr) : camera->Remove();
  }

  if (FAILED(hr)) {
    PrintFailure(install ? L"Install virtual camera" : L"Remove virtual camera",
                 hr);
  } else {
    wprintf(install ? L"Meo Camera installed.\n" : L"Meo Camera removed.\n");
  }

  camera.Reset();
  MFShutdown();
  CoUninitialize();
  return FAILED(hr) ? 1 : 0;
}

#endif
