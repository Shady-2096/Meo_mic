#include "../src/Common.h"

#include <mfreadwrite.h>

#include <cstdio>
#include <cwchar>

int wmain(int argc, wchar_t** argv) {
  const bool direct = argc == 2 && wcscmp(argv[1], L"--direct") == 0;
  if (argc > 2 || (argc == 2 && !direct)) {
    wprintf(L"Usage: MeoProbeReader.exe [--direct]\n");
    return 1;
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

  ComPtr<IMFActivate> meoCamera;
  if (direct) {
    hr = CoCreateInstance(CLSID_MeoProbeSource, nullptr,
                          CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&meoCamera));
  } else {
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
          if (wcsncmp(name, meo::kFriendlyName,
                      ARRAYSIZE(meo::kFriendlyName) - 1) == 0) {
            meoCamera = devices[i];
          }
          CoTaskMemFree(name);
        }
        devices[i]->Release();
      }
      CoTaskMemFree(devices);
    }
  }

  if (FAILED(hr) || !meoCamera) {
    wprintf(L"Meo camera activation lookup failed: %s\n",
            meo::FormatHresult(FAILED(hr) ? hr : MF_E_NOT_FOUND).c_str());
    MFShutdown();
    CoUninitialize();
    return 1;
  }

  ComPtr<IMFMediaSource> source;
  hr = meoCamera->ActivateObject(IID_PPV_ARGS(&source));
  if (FAILED(hr)) {
    wprintf(L"ActivateObject failed: %s\n", meo::FormatHresult(hr).c_str());
    MFShutdown();
    CoUninitialize();
    return 1;
  }

  ComPtr<IMFSourceReader> reader;
  hr = MFCreateSourceReaderFromMediaSource(source.Get(), nullptr, &reader);
  if (FAILED(hr)) {
    wprintf(L"MFCreateSourceReaderFromMediaSource failed: %s\n",
            meo::FormatHresult(hr).c_str());
    source->Shutdown();
    source.Reset();
    MFShutdown();
    CoUninitialize();
    return 1;
  }

  DWORD streamIndex = 0;
  DWORD flags = 0;
  LONGLONG timestamp = 0;
  ComPtr<IMFSample> sample;
  for (unsigned attempt = 0; attempt < 8 && !sample; ++attempt) {
    flags = 0;
    hr = reader->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0,
                            &streamIndex, &flags, &timestamp, &sample);
    if (FAILED(hr)) {
      break;
    }
    if (!sample) {
      wprintf(L"ReadSample event without a frame: flags=0x%08lX\n", flags);
    }
  }
  if (SUCCEEDED(hr) && sample) {
    DWORD bytes = 0;
    hr = sample->GetTotalLength(&bytes);
    if (SUCCEEDED(hr)) {
      wprintf(L"Read one frame: stream=%lu bytes=%lu timestamp=%lld flags=0x%08lX\n",
              streamIndex, bytes, timestamp, flags);
    }
  }
  if (FAILED(hr) || !sample) {
    wprintf(L"ReadSample failed: %s flags=0x%08lX\n",
            meo::FormatHresult(FAILED(hr) ? hr : MF_E_NO_SAMPLE_TIMESTAMP)
                .c_str(),
            flags);
  }

  reader.Reset();
  source->Shutdown();
  source.Reset();
  meoCamera->ShutdownObject();
  meoCamera.Reset();
  MFShutdown();
  CoUninitialize();
  return SUCCEEDED(hr) && sample ? 0 : 1;
}
