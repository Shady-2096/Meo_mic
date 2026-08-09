#pragma once

#include "Common.h"
#include "ProbeSource.h"

namespace meo {

// The registered COM CLSID creates an IMFActivate. The Windows frame server
// calls ActivateObject on it to obtain the actual media source.
class ProbeActivate
    : public Microsoft::WRL::RuntimeClass<
          Microsoft::WRL::RuntimeClassFlags<Microsoft::WRL::ClassicCom>,
          Microsoft::WRL::ChainInterfaces<IMFActivate, IMFAttributes>> {
 public:
  HRESULT RuntimeClassInitialize();

  // IMFActivate
  IFACEMETHODIMP ActivateObject(REFIID riid, void** object) override;
  IFACEMETHODIMP ShutdownObject() override;
  IFACEMETHODIMP DetachObject() override;

  // IMFAttributes (inherited by IMFActivate)
  IFACEMETHODIMP GetItem(REFGUID key, PROPVARIANT* value) override;
  IFACEMETHODIMP GetItemType(REFGUID key, MF_ATTRIBUTE_TYPE* type) override;
  IFACEMETHODIMP CompareItem(REFGUID key, REFPROPVARIANT value,
                            BOOL* result) override;
  IFACEMETHODIMP Compare(IMFAttributes* theirs, MF_ATTRIBUTES_MATCH_TYPE type,
                        BOOL* result) override;
  IFACEMETHODIMP GetUINT32(REFGUID key, UINT32* value) override;
  IFACEMETHODIMP GetUINT64(REFGUID key, UINT64* value) override;
  IFACEMETHODIMP GetDouble(REFGUID key, double* value) override;
  IFACEMETHODIMP GetGUID(REFGUID key, GUID* value) override;
  IFACEMETHODIMP GetStringLength(REFGUID key, UINT32* length) override;
  IFACEMETHODIMP GetString(REFGUID key, LPWSTR value, UINT32 size,
                          UINT32* length) override;
  IFACEMETHODIMP GetAllocatedString(REFGUID key, LPWSTR* value,
                                   UINT32* length) override;
  IFACEMETHODIMP GetBlobSize(REFGUID key, UINT32* size) override;
  IFACEMETHODIMP GetBlob(REFGUID key, UINT8* value, UINT32 size,
                        UINT32* blobSize) override;
  IFACEMETHODIMP GetAllocatedBlob(REFGUID key, UINT8** value,
                                 UINT32* size) override;
  IFACEMETHODIMP GetUnknown(REFGUID key, REFIID riid, LPVOID* object) override;
  IFACEMETHODIMP SetItem(REFGUID key, REFPROPVARIANT value) override;
  IFACEMETHODIMP DeleteItem(REFGUID key) override;
  IFACEMETHODIMP DeleteAllItems() override;
  IFACEMETHODIMP SetUINT32(REFGUID key, UINT32 value) override;
  IFACEMETHODIMP SetUINT64(REFGUID key, UINT64 value) override;
  IFACEMETHODIMP SetDouble(REFGUID key, double value) override;
  IFACEMETHODIMP SetGUID(REFGUID key, REFGUID value) override;
  IFACEMETHODIMP SetString(REFGUID key, LPCWSTR value) override;
  IFACEMETHODIMP SetBlob(REFGUID key, const UINT8* value,
                        UINT32 size) override;
  IFACEMETHODIMP SetUnknown(REFGUID key, IUnknown* object) override;
  IFACEMETHODIMP LockStore() override;
  IFACEMETHODIMP UnlockStore() override;
  IFACEMETHODIMP GetCount(UINT32* count) override;
  IFACEMETHODIMP GetItemByIndex(UINT32 index, GUID* key,
                               PROPVARIANT* value) override;
  IFACEMETHODIMP CopyAllItems(IMFAttributes* destination) override;

 private:
  ComPtr<IMFAttributes> attributes_;
  Microsoft::WRL::ComPtr<ProbeSource> source_;
  std::mutex lock_;
};

}  // namespace meo
