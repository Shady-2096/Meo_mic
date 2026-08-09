#include "ProbeActivate.h"

namespace meo {

HRESULT ProbeActivate::RuntimeClassInitialize() {
  return MFCreateAttributes(&attributes_, 4);
}

IFACEMETHODIMP ProbeActivate::ActivateObject(REFIID riid, void** object) {
  if (object == nullptr) {
    return E_POINTER;
  }
  *object = nullptr;

  wchar_t requestedInterface[64] = {};
  StringFromGUID2(riid, requestedInterface, ARRAYSIZE(requestedInterface));
  ProbeLog(L"ProbeActivate::ActivateObject requested %s",
           requestedInterface);

  std::lock_guard<std::mutex> guard(lock_);
  if (!source_) {
    const HRESULT hr =
        Microsoft::WRL::MakeAndInitialize<ProbeSource>(&source_);
    if (FAILED(hr)) {
      return hr;
    }
  }
  const HRESULT hr = source_.CopyTo(riid, object);
  ProbeLog(L"ProbeActivate::ActivateObject returned %s",
           FormatHresult(hr).c_str());
  return hr;
}

IFACEMETHODIMP ProbeActivate::ShutdownObject() {
  Microsoft::WRL::ComPtr<ProbeSource> source;
  {
    std::lock_guard<std::mutex> guard(lock_);
    source = source_;
    source_.Reset();
  }
  if (source) {
    const HRESULT hr = source->Shutdown();
    return hr == MF_E_SHUTDOWN ? S_OK : hr;
  }
  return S_OK;
}

IFACEMETHODIMP ProbeActivate::DetachObject() {
  std::lock_guard<std::mutex> guard(lock_);
  source_.Reset();
  return S_OK;
}

#define FORWARD_ATTRIBUTES(method, ...) \
  return attributes_ ? attributes_->method(__VA_ARGS__) : E_UNEXPECTED

IFACEMETHODIMP ProbeActivate::GetItem(REFGUID key, PROPVARIANT* value) {
  FORWARD_ATTRIBUTES(GetItem, key, value);
}
IFACEMETHODIMP ProbeActivate::GetItemType(REFGUID key,
                                          MF_ATTRIBUTE_TYPE* type) {
  FORWARD_ATTRIBUTES(GetItemType, key, type);
}
IFACEMETHODIMP ProbeActivate::CompareItem(REFGUID key, REFPROPVARIANT value,
                                         BOOL* result) {
  FORWARD_ATTRIBUTES(CompareItem, key, value, result);
}
IFACEMETHODIMP ProbeActivate::Compare(IMFAttributes* theirs,
                                     MF_ATTRIBUTES_MATCH_TYPE type,
                                     BOOL* result) {
  FORWARD_ATTRIBUTES(Compare, theirs, type, result);
}
IFACEMETHODIMP ProbeActivate::GetUINT32(REFGUID key, UINT32* value) {
  FORWARD_ATTRIBUTES(GetUINT32, key, value);
}
IFACEMETHODIMP ProbeActivate::GetUINT64(REFGUID key, UINT64* value) {
  FORWARD_ATTRIBUTES(GetUINT64, key, value);
}
IFACEMETHODIMP ProbeActivate::GetDouble(REFGUID key, double* value) {
  FORWARD_ATTRIBUTES(GetDouble, key, value);
}
IFACEMETHODIMP ProbeActivate::GetGUID(REFGUID key, GUID* value) {
  FORWARD_ATTRIBUTES(GetGUID, key, value);
}
IFACEMETHODIMP ProbeActivate::GetStringLength(REFGUID key, UINT32* length) {
  FORWARD_ATTRIBUTES(GetStringLength, key, length);
}
IFACEMETHODIMP ProbeActivate::GetString(REFGUID key, LPWSTR value, UINT32 size,
                                       UINT32* length) {
  FORWARD_ATTRIBUTES(GetString, key, value, size, length);
}
IFACEMETHODIMP ProbeActivate::GetAllocatedString(REFGUID key, LPWSTR* value,
                                                UINT32* length) {
  FORWARD_ATTRIBUTES(GetAllocatedString, key, value, length);
}
IFACEMETHODIMP ProbeActivate::GetBlobSize(REFGUID key, UINT32* size) {
  FORWARD_ATTRIBUTES(GetBlobSize, key, size);
}
IFACEMETHODIMP ProbeActivate::GetBlob(REFGUID key, UINT8* value, UINT32 size,
                                     UINT32* blobSize) {
  FORWARD_ATTRIBUTES(GetBlob, key, value, size, blobSize);
}
IFACEMETHODIMP ProbeActivate::GetAllocatedBlob(REFGUID key, UINT8** value,
                                              UINT32* size) {
  FORWARD_ATTRIBUTES(GetAllocatedBlob, key, value, size);
}
IFACEMETHODIMP ProbeActivate::GetUnknown(REFGUID key, REFIID riid,
                                        LPVOID* object) {
  FORWARD_ATTRIBUTES(GetUnknown, key, riid, object);
}
IFACEMETHODIMP ProbeActivate::SetItem(REFGUID key, REFPROPVARIANT value) {
  FORWARD_ATTRIBUTES(SetItem, key, value);
}
IFACEMETHODIMP ProbeActivate::DeleteItem(REFGUID key) {
  FORWARD_ATTRIBUTES(DeleteItem, key);
}
IFACEMETHODIMP ProbeActivate::DeleteAllItems() {
  FORWARD_ATTRIBUTES(DeleteAllItems);
}
IFACEMETHODIMP ProbeActivate::SetUINT32(REFGUID key, UINT32 value) {
  FORWARD_ATTRIBUTES(SetUINT32, key, value);
}
IFACEMETHODIMP ProbeActivate::SetUINT64(REFGUID key, UINT64 value) {
  FORWARD_ATTRIBUTES(SetUINT64, key, value);
}
IFACEMETHODIMP ProbeActivate::SetDouble(REFGUID key, double value) {
  FORWARD_ATTRIBUTES(SetDouble, key, value);
}
IFACEMETHODIMP ProbeActivate::SetGUID(REFGUID key, REFGUID value) {
  FORWARD_ATTRIBUTES(SetGUID, key, value);
}
IFACEMETHODIMP ProbeActivate::SetString(REFGUID key, LPCWSTR value) {
  FORWARD_ATTRIBUTES(SetString, key, value);
}
IFACEMETHODIMP ProbeActivate::SetBlob(REFGUID key, const UINT8* value,
                                     UINT32 size) {
  FORWARD_ATTRIBUTES(SetBlob, key, value, size);
}
IFACEMETHODIMP ProbeActivate::SetUnknown(REFGUID key, IUnknown* object) {
  FORWARD_ATTRIBUTES(SetUnknown, key, object);
}
IFACEMETHODIMP ProbeActivate::LockStore() {
  FORWARD_ATTRIBUTES(LockStore);
}
IFACEMETHODIMP ProbeActivate::UnlockStore() {
  FORWARD_ATTRIBUTES(UnlockStore);
}
IFACEMETHODIMP ProbeActivate::GetCount(UINT32* count) {
  FORWARD_ATTRIBUTES(GetCount, count);
}
IFACEMETHODIMP ProbeActivate::GetItemByIndex(UINT32 index, GUID* key,
                                            PROPVARIANT* value) {
  FORWARD_ATTRIBUTES(GetItemByIndex, index, key, value);
}
IFACEMETHODIMP ProbeActivate::CopyAllItems(IMFAttributes* destination) {
  FORWARD_ATTRIBUTES(CopyAllItems, destination);
}

#undef FORWARD_ATTRIBUTES

}  // namespace meo
