#if defined(_WIN32)

#include "VirtualCamera.h"

#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <wrl/client.h>

#include <cstdio>
#include <cstring>
#include <cwchar>

using Microsoft::WRL::ComPtr;

namespace {

using DllGetClassObjectFn = HRESULT(STDAPICALLTYPE*)(REFCLSID, REFIID, void**);

int Fail(const char* operation, HRESULT hr) {
  std::printf("FAIL: %s returned 0x%08lX\n", operation,
              static_cast<unsigned long>(hr));
  return 1;
}

}  // namespace

int main(int argc, char** argv) {
  const bool frameServer = argc == 2 &&
                           std::strcmp(argv[1], "--frame-server") == 0;
  if (argc > 2 || (argc == 2 && !frameServer)) {
    std::printf("Usage: VirtualCameraSourceTests.exe [--frame-server]\n");
    return 2;
  }
  if (frameServer) {
    HANDLE watchdog = CreateThread(
        nullptr, 0,
        [](LPVOID) -> DWORD {
          Sleep(10'000);
          ExitProcess(124);
        },
        nullptr, 0, nullptr);
    if (watchdog != nullptr) CloseHandle(watchdog);
  }
  HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
  if (FAILED(hr)) return Fail("CoInitializeEx", hr);
  hr = MFStartup(MF_VERSION);
  if (FAILED(hr)) {
    CoUninitialize();
    return Fail("MFStartup", hr);
  }

  HMODULE module = nullptr;
  ComPtr<IClassFactory> factory;
  ComPtr<IMFActivate> activate;
  if (frameServer) {
    ComPtr<IMFAttributes> attributes;
    hr = MFCreateAttributes(&attributes, 1);
    if (SUCCEEDED(hr)) {
      hr = attributes->SetGUID(
          MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE,
          MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID);
    }
    IMFActivate** devices = nullptr;
    UINT32 count = 0;
    if (SUCCEEDED(hr)) {
      hr = MFEnumDeviceSources(attributes.Get(), &devices, &count);
    }
    if (SUCCEEDED(hr)) {
      for (UINT32 i = 0; i < count; ++i) {
        wchar_t* name = nullptr;
        UINT32 length = 0;
        if (SUCCEEDED(devices[i]->GetAllocatedString(
                MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &name, &length))) {
          if (std::wcsncmp(name, meo::kVirtualCameraFriendlyName,
                           ARRAYSIZE(meo::kVirtualCameraFriendlyName) - 1) ==
              0) {
            activate = devices[i];
          }
          CoTaskMemFree(name);
        }
        devices[i]->Release();
      }
      CoTaskMemFree(devices);
      if (!activate) hr = MF_E_NOT_FOUND;
    }
  } else {
    module = LoadLibraryW(L"MeoVirtualCameraSource.dll");
    if (module == nullptr) {
      hr = HRESULT_FROM_WIN32(GetLastError());
    }
    auto getClassObject = module == nullptr
        ? nullptr
        : reinterpret_cast<DllGetClassObjectFn>(
              GetProcAddress(module, "DllGetClassObject"));
    if (SUCCEEDED(hr) && getClassObject == nullptr) {
      hr = HRESULT_FROM_WIN32(GetLastError());
    }
    if (SUCCEEDED(hr)) {
      hr = getClassObject(CLSID_MeoVirtualCameraSource,
                          IID_PPV_ARGS(&factory));
    }
    if (SUCCEEDED(hr)) {
      hr = factory->CreateInstance(nullptr, IID_PPV_ARGS(&activate));
    }
  }
  ComPtr<IMFMediaSource> source;
  if (SUCCEEDED(hr)) hr = activate->ActivateObject(IID_PPV_ARGS(&source));
  if (frameServer) {
    std::printf("frame-server activation: 0x%08lX\n",
                static_cast<unsigned long>(hr));
    std::fflush(stdout);
  }
  if (SUCCEEDED(hr) && !frameServer) {
    // The frame server may shut down the activation wrapper as soon as it has
    // retained the returned source. That must not invalidate the source.
    hr = activate->ShutdownObject();
  }
  ComPtr<IMFSourceReader> reader;
  if (SUCCEEDED(hr)) {
    hr = MFCreateSourceReaderFromMediaSource(source.Get(), nullptr, &reader);
  }
  if (frameServer) {
    std::printf("source-reader creation: 0x%08lX\n",
                static_cast<unsigned long>(hr));
    std::fflush(stdout);
  }
  if (SUCCEEDED(hr) && frameServer) {
    ComPtr<IMFMediaType> requestedType;
    hr = MFCreateMediaType(&requestedType);
    if (SUCCEEDED(hr)) {
      hr = requestedType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    }
    if (SUCCEEDED(hr)) {
      hr = requestedType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
    }
    if (SUCCEEDED(hr)) {
      hr = MFSetAttributeSize(requestedType.Get(), MF_MT_FRAME_SIZE,
                              meo::kVirtualCameraWidth,
                              meo::kVirtualCameraHeight);
    }
    if (SUCCEEDED(hr)) {
      hr = MFSetAttributeRatio(requestedType.Get(), MF_MT_FRAME_RATE,
                               meo::kVirtualCameraFrameRate, 1);
    }
    if (SUCCEEDED(hr)) {
      hr = reader->SetCurrentMediaType(
          static_cast<DWORD>(MF_SOURCE_READER_FIRST_VIDEO_STREAM), nullptr,
          requestedType.Get());
    }
    std::printf("media-type selection: 0x%08lX\n",
                static_cast<unsigned long>(hr));
    std::fflush(stdout);
  }

  DWORD streamIndex = 0;
  DWORD flags = 0;
  LONGLONG timestamp = 0;
  ComPtr<IMFSample> sample;
  for (unsigned attempt = 0; SUCCEEDED(hr) && !sample && attempt < 8;
       ++attempt) {
    if (frameServer && attempt == 0) {
      std::printf("requesting first sample\n");
      std::fflush(stdout);
    }
    hr = reader->ReadSample(
        static_cast<DWORD>(MF_SOURCE_READER_FIRST_VIDEO_STREAM), 0,
        &streamIndex, &flags, &timestamp, &sample);
  }
  DWORD bytes = 0;
  if (SUCCEEDED(hr) && sample) hr = sample->GetTotalLength(&bytes);
  if (SUCCEEDED(hr) && (!sample || bytes != meo::kVirtualCameraFrameBytes)) {
    hr = E_FAIL;
  }
  LONGLONG sampleTime = 0;
  if (SUCCEEDED(hr)) hr = sample->GetSampleTime(&sampleTime);
  if (SUCCEEDED(hr)) {
    const LONGLONG now = MFGetSystemTime();
    constexpr LONGLONG kClockTolerance = 5LL * 10'000'000LL;
    if (sampleTime <= 0 || sampleTime > now + kClockTolerance ||
        now - sampleTime > kClockTolerance) {
      hr = E_FAIL;
    }
  }

  // Cross-process samples must be released while COM is still initialized.
  // Keeping the Frame Server proxy alive until function-scope teardown makes
  // its final Release run after CoUninitialize and can fault in the proxy.
  sample.Reset();
  reader.Reset();
  if (source) source->Shutdown();
  source.Reset();
  if (activate) activate->ShutdownObject();
  activate.Reset();
  factory.Reset();
  if (module != nullptr) FreeLibrary(module);
  MFShutdown();
  CoUninitialize();

  if (FAILED(hr)) return Fail("read complete NV12 slate", hr);
  std::printf("%s returned one %lu-byte NV12 frame without a host\n",
              frameServer ? "Windows frame server" : "COM source", bytes);
  return 0;
}

#endif
