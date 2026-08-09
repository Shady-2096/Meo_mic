#pragma once

#include "Common.h"

namespace meo {

// The single video stream the probe source exposes.
//
// It implements the pull model: the frame server calls RequestSample, and the
// stream answers by queueing one MEMediaSample event carrying a freshly drawn
// test frame. There is no producer thread and no queue, which is the whole
// point — if a real application cannot see this camera, the cause is
// enumeration or registration, not anything this class is doing.
class ProbeStream
    : public Microsoft::WRL::RuntimeClass<
          Microsoft::WRL::RuntimeClassFlags<Microsoft::WRL::ClassicCom>,
          Microsoft::WRL::ChainInterfaces<IMFMediaStream2, IMFMediaStream,
                                          IMFMediaEventGenerator>,
          IKsControl> {
 public:
  ProbeStream();

  // `source` is held raw, not as a ComPtr. The source owns the stream, so a
  // strong reference back would be a cycle that never releases and would keep
  // the DLL loaded in the frame server forever.
  HRESULT Initialize(IMFMediaSource* source, DWORD streamIdentifier);

  // Called by the source when it starts, stops, pauses, or shuts down.
  HRESULT OnSourceStart(IMFMediaType* mediaType, LONGLONG startTime100ns);
  HRESULT OnSourceStop();
  HRESULT OnSourcePause();
  void OnSourceShutdown();

  IMFStreamDescriptor* Descriptor() const { return descriptor_.Get(); }
  IMFAttributes* Attributes() const { return attributes_.Get(); }
  HRESULT SetSampleAllocator(IUnknown* allocator);

  // IMFMediaEventGenerator
  IFACEMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override;
  IFACEMETHODIMP BeginGetEvent(IMFAsyncCallback* callback,
                               IUnknown* state) override;
  IFACEMETHODIMP EndGetEvent(IMFAsyncResult* result,
                             IMFMediaEvent** event) override;
  IFACEMETHODIMP QueueEvent(MediaEventType type, REFGUID extendedType,
                            HRESULT status, const PROPVARIANT* value) override;

  // IMFMediaStream
  IFACEMETHODIMP GetMediaSource(IMFMediaSource** source) override;
  IFACEMETHODIMP GetStreamDescriptor(IMFStreamDescriptor** descriptor) override;
  IFACEMETHODIMP RequestSample(IUnknown* token) override;

  // IMFMediaStream2
  IFACEMETHODIMP SetStreamState(MF_STREAM_STATE state) override;
  IFACEMETHODIMP GetStreamState(MF_STREAM_STATE* state) override;

  // IKsControl. The frame server queries cameras for control properties; the
  // probe supports none and says so, rather than failing in a way that could
  // be mistaken for the camera being unusable.
  IFACEMETHODIMP
  KsProperty(PKSPROPERTY property, ULONG propertyLength, void* propertyData,
             ULONG dataLength, ULONG* bytesReturned) override;
  IFACEMETHODIMP
  KsMethod(PKSMETHOD method, ULONG methodLength, void* methodData,
           ULONG dataLength, ULONG* bytesReturned) override;
  IFACEMETHODIMP
  KsEvent(PKSEVENT event, ULONG eventLength, void* eventData, ULONG dataLength,
          ULONG* bytesReturned) override;

 private:
  HRESULT CreateMediaType(IMFMediaType** type) const;
  HRESULT CreateSample(IMFSample** sample);

  std::mutex lock_;
  ComPtr<IMFMediaEventQueue> eventQueue_;
  ComPtr<IMFAttributes> attributes_;
  ComPtr<IMFStreamDescriptor> descriptor_;
  ComPtr<IMFMediaType> currentMediaType_;
  ComPtr<IMFVideoSampleAllocator> sampleAllocator_;
  IMFMediaSource* source_ = nullptr;  // weak, see Initialize
  DWORD streamIdentifier_ = 0;
  MF_STREAM_STATE state_ = MF_STREAM_STATE_STOPPED;
  bool shutdown_ = false;
  UINT64 frameIndex_ = 0;
  LONGLONG startTime100ns_ = 0;
  LONGLONG wallClockStart100ns_ = 0;
};

}  // namespace meo
