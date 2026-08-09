#pragma once

#include "Common.h"
#include "ProbeStream.h"

namespace meo {

// The media source the Windows frame server activates by CLSID.
//
// The frame server runs in its own process under its own account. That is the
// entire subject of Probe 3: this object is created by CoCreateInstance from
// *that* process, so whether it can be found at all depends on which registry
// hive the DLL registered its CLSID into.
class ProbeSource
    : public Microsoft::WRL::RuntimeClass<
          Microsoft::WRL::RuntimeClassFlags<Microsoft::WRL::ClassicCom>,
          Microsoft::WRL::ChainInterfaces<IMFMediaSourceEx, IMFMediaSource,
                                          IMFMediaEventGenerator>,
          IMFGetService, IKsControl,
          IMFSampleAllocatorControl> {
 public:
  ProbeSource();

  HRESULT RuntimeClassInitialize();

  // IUnknown, logged only so the probe records which frame-server contract
  // is missing if activation fails.
  IFACEMETHODIMP QueryInterface(REFIID riid, void** object) override;

  // IMFMediaEventGenerator
  IFACEMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override;
  IFACEMETHODIMP BeginGetEvent(IMFAsyncCallback* callback,
                               IUnknown* state) override;
  IFACEMETHODIMP EndGetEvent(IMFAsyncResult* result,
                             IMFMediaEvent** event) override;
  IFACEMETHODIMP QueueEvent(MediaEventType type, REFGUID extendedType,
                            HRESULT status, const PROPVARIANT* value) override;

  // IMFMediaSource
  IFACEMETHODIMP GetCharacteristics(DWORD* characteristics) override;
  IFACEMETHODIMP CreatePresentationDescriptor(
      IMFPresentationDescriptor** descriptor) override;
  IFACEMETHODIMP Start(IMFPresentationDescriptor* descriptor,
                       const GUID* timeFormat,
                       const PROPVARIANT* startPosition) override;
  IFACEMETHODIMP Stop() override;
  IFACEMETHODIMP Pause() override;
  IFACEMETHODIMP Shutdown() override;

  // IMFMediaSourceEx
  IFACEMETHODIMP GetSourceAttributes(IMFAttributes** attributes) override;
  IFACEMETHODIMP GetStreamAttributes(DWORD streamIdentifier,
                                     IMFAttributes** attributes) override;
  IFACEMETHODIMP SetD3DManager(IUnknown* manager) override;

  // IMFGetService
  IFACEMETHODIMP GetService(REFGUID service, REFIID riid,
                            LPVOID* object) override;

  // IKsControl
  IFACEMETHODIMP
  KsProperty(PKSPROPERTY property, ULONG propertyLength, void* propertyData,
             ULONG dataLength, ULONG* bytesReturned) override;
  IFACEMETHODIMP
  KsMethod(PKSMETHOD method, ULONG methodLength, void* methodData,
           ULONG dataLength, ULONG* bytesReturned) override;
  IFACEMETHODIMP
  KsEvent(PKSEVENT event, ULONG eventLength, void* eventData, ULONG dataLength,
          ULONG* bytesReturned) override;

  // IMFSampleAllocatorControl
  IFACEMETHODIMP SetDefaultAllocator(DWORD outputStreamIdentifier,
                                    IUnknown* allocator) override;
  IFACEMETHODIMP GetAllocatorUsage(DWORD outputStreamIdentifier,
                                  DWORD* inputStreamIdentifier,
                                  MFSampleAllocatorUsage* usage) override;

 private:
  static constexpr DWORD kStreamIdentifier = 0;

  std::mutex lock_;
  ComPtr<IMFMediaEventQueue> eventQueue_;
  ComPtr<IMFAttributes> attributes_;
  ComPtr<IMFPresentationDescriptor> presentationDescriptor_;
  Microsoft::WRL::ComPtr<ProbeStream> stream_;
  bool shutdown_ = false;
  bool announcedStream_ = false;
};

}  // namespace meo
