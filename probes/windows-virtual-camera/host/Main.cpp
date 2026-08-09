// Probe host: creates a Media Foundation virtual camera backed by the probe
// media source, then stays alive so you can go and look at other applications.
//
// This program produces the raw material for two answers:
//
//   §1.1  — the EXACT friendly-name string Windows shows, including the
//           suffix the frame server appends and which we cannot suppress.
//           It is printed from a real device enumeration, not assumed.
//
//   §9.1  — whether an MF-only virtual camera is visible to Zoom, Discord,
//           Chrome, Edge, Teams, OBS, and the Windows Camera app. That is
//           checked by hand while this program is running, because only the
//           applications themselves can answer it.
//
// NOTE: MFCreateVirtualCamera is Windows 11 (build 22000) and later. On
// Windows 10 this program is expected to fail at that call, and that failure
// is a legitimate result to record — it is part of why §9.5 plans a
// DirectShow backend at all.

#include "../src/Common.h"

#include <mfvirtualcamera.h>
#include <mfreadwrite.h>

#include <cstdio>
#include <cstdlib>
#include <string>

namespace {

// Prints every video capture device the frame server currently knows about,
// with its friendly name exactly as Windows reports it.
HRESULT ListCameras(const wchar_t* heading) {
  wprintf(L"\n===== %s =====\n", heading);

  ComPtr<IMFAttributes> attributes;
  HRESULT hr = MFCreateAttributes(&attributes, 1);
  if (FAILED(hr)) {
    return hr;
  }
  hr = attributes->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE,
                           MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID);
  if (FAILED(hr)) {
    return hr;
  }

  IMFActivate** devices = nullptr;
  UINT32 count = 0;
  hr = MFEnumDeviceSources(attributes.Get(), &devices, &count);
  if (FAILED(hr)) {
    wprintf(L"  MFEnumDeviceSources failed: %s\n",
            meo::FormatHresult(hr).c_str());
    return hr;
  }

  if (count == 0) {
    wprintf(L"  (no video capture devices)\n");
  }

  for (UINT32 i = 0; i < count; ++i) {
    wchar_t* name = nullptr;
    UINT32 nameLength = 0;
    if (SUCCEEDED(devices[i]->GetAllocatedString(
            MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &name, &nameLength))) {
      wprintf(L"  [%u] \"%s\"\n", i, name);
      CoTaskMemFree(name);
    } else {
      wprintf(L"  [%u] (no friendly name)\n", i);
    }

    wchar_t* link = nullptr;
    UINT32 linkLength = 0;
    if (SUCCEEDED(devices[i]->GetAllocatedString(
            MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK, &link,
            &linkLength))) {
      wprintf(L"       %s\n", link);
      CoTaskMemFree(link);
    }
    devices[i]->Release();
  }
  CoTaskMemFree(devices);
  wprintf(L"\n");
  return S_OK;
}

void PrintUsage() {
  wprintf(
      L"\nUsage: MeoProbeHost.exe [--lifetime session|system]\n"
      L"\n"
      L"  --lifetime session  (default) the camera exists only while this\n"
      L"                      program runs, and leaves nothing behind.\n"
      L"  --lifetime system   the camera survives restarts, which is what\n"
      L"                      the product wants (plan 9.4) but which needs\n"
      L"                      an explicit --remove run to clean up.\n"
      L"\n"
      L"  --remove            remove a previously created system-lifetime\n"
      L"                      camera and exit.\n"
      L"  --hold-seconds N    keep a session camera alive for N seconds\n"
      L"                      instead of waiting for console input.\n\n");
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
  MFVirtualCameraLifetime lifetime = MFVirtualCameraLifetime_Session;
  bool removeOnly = false;
  DWORD holdSeconds = 0;

  for (int i = 1; i < argc; ++i) {
    const std::wstring arg = argv[i];
    if (arg == L"--lifetime" && i + 1 < argc) {
      const std::wstring value = argv[++i];
      if (value == L"system") {
        lifetime = MFVirtualCameraLifetime_System;
      } else if (value != L"session") {
        wprintf(L"Unknown lifetime \"%s\".\n", value.c_str());
        PrintUsage();
        return 1;
      }
    } else if (arg == L"--remove") {
      removeOnly = true;
    } else if (arg == L"--hold-seconds" && i + 1 < argc) {
      wchar_t* end = nullptr;
      const unsigned long value = wcstoul(argv[++i], &end, 10);
      if (end == argv[i] || *end != L'\0' || value == 0 || value > 3600) {
        wprintf(L"--hold-seconds must be between 1 and 3600.\n");
        return 1;
      }
      holdSeconds = static_cast<DWORD>(value);
    } else {
      PrintUsage();
      return arg == L"--help" || arg == L"-h" ? 0 : 1;
    }
  }

  HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
  if (FAILED(hr)) {
    wprintf(L"CoInitializeEx failed: %s\n", meo::FormatHresult(hr).c_str());
    return 1;
  }
  hr = MFStartup(MF_VERSION);
  if (FAILED(hr)) {
    wprintf(L"MFStartup failed: %s\n", meo::FormatHresult(hr).c_str());
    CoUninitialize();
    return 1;
  }

  wchar_t clsid[64] = {};
  StringFromGUID2(CLSID_MeoProbeSource, clsid, ARRAYSIZE(clsid));

  wprintf(L"Meo Windows virtual-camera probe\n");
  wprintf(L"Source CLSID: %s\n", clsid);
  wprintf(L"Requested friendly name: \"%s\"\n", meo::kFriendlyName);

  ListCameras(L"Cameras BEFORE creating the virtual camera");

  ComPtr<IMFVirtualCamera> camera;
  hr = MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource, lifetime,
                             MFVirtualCameraAccess_CurrentUser,
                             meo::kFriendlyName, clsid, nullptr, 0, &camera);
  if (FAILED(hr)) {
    wprintf(L"\nMFCreateVirtualCamera FAILED: %s\n",
            meo::FormatHresult(hr).c_str());
    if (hr == E_ACCESSDENIED) {
      wprintf(
          L"\n  E_ACCESSDENIED usually means the Windows camera privacy\n"
          L"  setting is denying access. Settings > Privacy & security >\n"
          L"  Camera > \"Let apps access your camera\". Plan 9.4 requires\n"
          L"  this to surface as an actionable message, so record it.\n");
    } else if (hr == E_NOTIMPL || hr == HRESULT_FROM_WIN32(ERROR_PROC_NOT_FOUND)) {
      wprintf(
          L"\n  This looks like a Windows 10 machine. MFCreateVirtualCamera\n"
          L"  is Windows 11 only. Record the OS build and move on; it is a\n"
          L"  real result, not a broken build.\n");
    }
    MFShutdown();
    CoUninitialize();
    return 1;
  }

  if (removeOnly) {
    hr = camera->Remove();
    wprintf(L"\nRemove() returned %s\n", meo::FormatHresult(hr).c_str());
    camera.Reset();
    MFShutdown();
    CoUninitialize();
    return SUCCEEDED(hr) ? 0 : 1;
  }

  hr = camera->Start(nullptr);
  if (FAILED(hr)) {
    wprintf(L"\nIMFVirtualCamera::Start FAILED: %s\n",
            meo::FormatHresult(hr).c_str());
    wprintf(
        L"\n  The camera was created but could not start. The usual cause\n"
        L"  is that the frame server could not activate the source CLSID,\n"
        L"  which is the Probe 3 (registration scope) failure. Check\n"
        L"  whether the DLL is registered in the hive you expect.\n");
    camera->Remove();
    camera.Reset();
    fflush(stdout);
    MFShutdown();
    CoUninitialize();
    return 1;
  }

  wprintf(L"\nVirtual camera STARTED.\n");

  ListCameras(L"Cameras AFTER creating the virtual camera");

  wprintf(
      L"----------------------------------------------------------------\n"
      L"The camera is live. Leave this window open and now go check each\n"
      L"application in RESULTS-TEMPLATE.md. Write down, for each one:\n"
      L"\n"
      L"  1. does the Meo camera appear in its device list at all?\n"
      L"  2. the EXACT name it shows (copy it character for character)\n"
      L"  3. does selecting it show moving colour bars with a sweeping\n"
      L"     white line, or a black/frozen frame?\n"
      L"\n"
      L"Compare the name against the \"AFTER\" list above: any difference\n"
      L"is the suffix the plan warns about in 1.1.\n"
      L"----------------------------------------------------------------\n"
      L"\nPress Enter to stop and remove the camera.\n");

  if (holdSeconds > 0) {
    wprintf(L"Holding the camera for %lu seconds.\n", holdSeconds);
    fflush(stdout);
    Sleep(holdSeconds * 1000);
  } else {
    (void)getwchar();
  }

  hr = camera->Stop();
  wprintf(L"Stop() returned %s\n", meo::FormatHresult(hr).c_str());
  hr = camera->Remove();
  wprintf(L"Remove() returned %s\n", meo::FormatHresult(hr).c_str());

  ListCameras(L"Cameras AFTER removing the virtual camera");

  camera.Reset();
  MFShutdown();
  CoUninitialize();
  return 0;
}
