// COM plumbing and registry registration for the probe media source.
//
// The registration half of this file is the entire subject of Probe 3
// (plan §18.3, §9.4). The same CLSID can be written into either hive:
//
//   regsvr32 MeoProbeSource.dll              -> HKLM, needs elevation (UAC)
//   regsvr32 /n /i:user MeoProbeSource.dll   -> HKCU, no elevation
//
// The question is whether the frame server, running as a different account,
// can activate a source it can only find under the *user's* HKCU. If HKCU
// works, Meo installs with no UAC prompt at all. If it does not, install
// costs one UAC prompt, which constraint C2 permits.

#include "Common.h"
#include "ProbeActivate.h"

#include <new>

namespace {

std::atomic<LONG> g_objectCount{0};
HMODULE g_module = nullptr;

class ProbeClassFactory : public IClassFactory {
 public:
  // IUnknown
  IFACEMETHODIMP QueryInterface(REFIID riid, void** object) override {
    if (object == nullptr) {
      return E_POINTER;
    }
    if (riid == IID_IUnknown || riid == IID_IClassFactory) {
      *object = static_cast<IClassFactory*>(this);
      AddRef();
      return S_OK;
    }
    *object = nullptr;
    return E_NOINTERFACE;
  }

  IFACEMETHODIMP_(ULONG) AddRef() override { return ++refCount_; }

  IFACEMETHODIMP_(ULONG) Release() override {
    const ULONG count = --refCount_;
    if (count == 0) {
      delete this;
    }
    return count;
  }

  // IClassFactory
  IFACEMETHODIMP CreateInstance(IUnknown* outer, REFIID riid,
                                void** object) override {
    if (object == nullptr) {
      return E_POINTER;
    }
    *object = nullptr;
    if (outer != nullptr) {
      return CLASS_E_NOAGGREGATION;
    }

    wchar_t requestedInterface[64] = {};
    StringFromGUID2(riid, requestedInterface, ARRAYSIZE(requestedInterface));
    meo::ProbeLog(L"ClassFactory::CreateInstance requested %s",
                  requestedInterface);

    Microsoft::WRL::ComPtr<meo::ProbeActivate> activate;
    const HRESULT hr =
        Microsoft::WRL::MakeAndInitialize<meo::ProbeActivate>(&activate);
    if (FAILED(hr)) {
      meo::ProbeLog(L"CreateInstance failed: %s",
                    meo::FormatHresult(hr).c_str());
      return hr;
    }
    return activate.CopyTo(riid, object);
  }

  IFACEMETHODIMP LockServer(BOOL lock) override {
    if (lock) {
      ++g_objectCount;
    } else {
      --g_objectCount;
    }
    return S_OK;
  }

 private:
  std::atomic<ULONG> refCount_{1};
};

std::wstring ClsidString() {
  wchar_t buffer[64] = {};
  StringFromGUID2(CLSID_MeoProbeSource, buffer, ARRAYSIZE(buffer));
  return buffer;
}

std::wstring ModulePath() {
  wchar_t path[MAX_PATH] = {};
  GetModuleFileNameW(g_module, path, ARRAYSIZE(path));
  return path;
}

HRESULT SetRegistryString(HKEY root, const std::wstring& subKey,
                          const wchar_t* valueName,
                          const std::wstring& value) {
  HKEY key = nullptr;
  LONG result =
      RegCreateKeyExW(root, subKey.c_str(), 0, nullptr, REG_OPTION_NON_VOLATILE,
                      KEY_WRITE, nullptr, &key, nullptr);
  if (result != ERROR_SUCCESS) {
    return HRESULT_FROM_WIN32(result);
  }
  result = RegSetValueExW(
      key, valueName, 0, REG_SZ,
      reinterpret_cast<const BYTE*>(value.c_str()),
      static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t)));
  RegCloseKey(key);
  return result == ERROR_SUCCESS ? S_OK : HRESULT_FROM_WIN32(result);
}

HRESULT RegisterInHive(HKEY root) {
  const std::wstring clsidKey = L"Software\\Classes\\CLSID\\" + ClsidString();

  HRESULT hr =
      SetRegistryString(root, clsidKey, nullptr, L"Meo Camera Probe Source");
  if (FAILED(hr)) {
    return hr;
  }
  hr = SetRegistryString(root, clsidKey + L"\\InprocServer32", nullptr,
                         ModulePath());
  if (FAILED(hr)) {
    return hr;
  }
  // "Both" lets the frame server activate the source on whichever apartment
  // it happens to be using. "Apartment" alone would force a marshalling hop
  // that some hosts do not set up.
  return SetRegistryString(root, clsidKey + L"\\InprocServer32",
                           L"ThreadingModel", L"Both");
}

HRESULT UnregisterFromHive(HKEY root) {
  const std::wstring clsidKey = L"Software\\Classes\\CLSID\\" + ClsidString();
  RegDeleteKeyExW(root, (clsidKey + L"\\InprocServer32").c_str(), 0, 0);
  const LONG result = RegDeleteKeyExW(root, clsidKey.c_str(), 0, 0);
  if (result != ERROR_SUCCESS && result != ERROR_FILE_NOT_FOUND) {
    return HRESULT_FROM_WIN32(result);
  }
  return S_OK;
}

}  // namespace

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID /*reserved*/) {
  if (reason == DLL_PROCESS_ATTACH) {
    g_module = module;
    DisableThreadLibraryCalls(module);
    const DWORD processId = GetCurrentProcessId();
    DWORD sessionId = 0;
    const BOOL foundSession = ProcessIdToSessionId(processId, &sessionId);
    meo::ProbeLog(L"DllMain process id: %lu", processId);
    meo::ProbeLog(L"DllMain session id: %lu%s", sessionId,
                  foundSession ? L"" : L" (ProcessIdToSessionId failed)");
  }
  return TRUE;
}

STDAPI DllGetClassObject(REFCLSID clsid, REFIID riid, void** object) {
  if (object == nullptr) {
    return E_POINTER;
  }
  *object = nullptr;
  if (clsid != CLSID_MeoProbeSource) {
    return CLASS_E_CLASSNOTAVAILABLE;
  }

  meo::ProbeLog(L"DllGetClassObject in process %lu", GetCurrentProcessId());

  auto* factory = new (std::nothrow) ProbeClassFactory();
  if (factory == nullptr) {
    return E_OUTOFMEMORY;
  }
  const HRESULT hr = factory->QueryInterface(riid, object);
  factory->Release();
  return hr;
}

STDAPI DllCanUnloadNow() {
  return g_objectCount.load() == 0 ? S_OK : S_FALSE;
}

// Machine-wide registration. regsvr32 must be run elevated for this to
// succeed; without elevation it fails with E_ACCESSDENIED, which is itself a
// valid Probe 3 result to record.
STDAPI DllRegisterServer() { return RegisterInHive(HKEY_LOCAL_MACHINE); }

STDAPI DllUnregisterServer() { return UnregisterFromHive(HKEY_LOCAL_MACHINE); }

// Per-user registration, reached via `regsvr32 /n /i:user`. Needs no
// elevation. Whether the frame server can *find* what this writes is the
// question Probe 3 exists to answer.
STDAPI DllInstall(BOOL install, LPCWSTR commandLine) {
  const bool perUser =
      commandLine != nullptr && _wcsicmp(commandLine, L"user") == 0;
  const HKEY root = perUser ? HKEY_CURRENT_USER : HKEY_LOCAL_MACHINE;
  return install ? RegisterInHive(root) : UnregisterFromHive(root);
}
