#if defined(_WIN32)

#include "VirtualCamera.h"

#include "FrameSource.h"

#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>
#include <mfvirtualcamera.h>
#include <ks.h>
#include <ksmedia.h>
#include <ksproxy.h>
#include <new>
#include <propvarutil.h>
#include <wrl/client.h>
#include <wrl/implements.h>

#include <atomic>
#include <mutex>
#include <string>

using Microsoft::WRL::ComPtr;

namespace {

std::atomic<long> g_objectCount{0};
std::atomic<long> g_serverLocks{0};
HMODULE g_module = nullptr;

HRESULT UnsupportedControl(ULONG* bytesReturned) noexcept {
  if (bytesReturned != nullptr) *bytesReturned = 0;
  return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
}

HRESULT CreateCameraMediaType(IMFMediaType** result) noexcept {
  if (result == nullptr) return E_POINTER;
  *result = nullptr;

  ComPtr<IMFMediaType> type;
  HRESULT hr = MFCreateMediaType(&type);
  if (FAILED(hr)) return hr;
  if (FAILED(hr = type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video))) return hr;
  if (FAILED(hr = type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12))) return hr;
  if (FAILED(hr = type->SetUINT32(MF_MT_INTERLACE_MODE,
                                  MFVideoInterlace_Progressive))) return hr;
  if (FAILED(hr = type->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE))) return hr;
  if (FAILED(hr = type->SetUINT32(MF_MT_DEFAULT_STRIDE,
                                  meo::kVirtualCameraWidth))) return hr;
  if (FAILED(hr = type->SetUINT32(MF_MT_SAMPLE_SIZE,
                                  meo::kVirtualCameraFrameBytes))) return hr;
  if (FAILED(hr = type->SetUINT32(
                 MF_MT_AVG_BITRATE,
                 meo::kVirtualCameraFrameBytes * 8 *
                     meo::kVirtualCameraFrameRate))) return hr;
  if (FAILED(hr = MFSetAttributeSize(type.Get(), MF_MT_FRAME_SIZE,
                                     meo::kVirtualCameraWidth,
                                     meo::kVirtualCameraHeight))) return hr;
  if (FAILED(hr = MFSetAttributeRatio(type.Get(), MF_MT_FRAME_RATE,
                                      meo::kVirtualCameraFrameRate, 1))) return hr;
  if (FAILED(hr = MFSetAttributeRatio(type.Get(), MF_MT_FRAME_RATE_RANGE_MIN,
                                      meo::kVirtualCameraFrameRate, 1))) return hr;
  if (FAILED(hr = MFSetAttributeRatio(type.Get(), MF_MT_FRAME_RATE_RANGE_MAX,
                                      meo::kVirtualCameraFrameRate, 1))) return hr;
  if (FAILED(hr = MFSetAttributeRatio(type.Get(), MF_MT_PIXEL_ASPECT_RATIO,
                                      1, 1))) return hr;
  *result = type.Detach();
  return S_OK;
}

class MediaStream final
    : public Microsoft::WRL::RuntimeClass<
          Microsoft::WRL::RuntimeClassFlags<Microsoft::WRL::ClassicCom>,
          Microsoft::WRL::ChainInterfaces<IMFMediaStream2, IMFMediaStream,
                                          IMFMediaEventGenerator>,
          IKsControl> {
 public:
  MediaStream() { ++g_objectCount; }
  ~MediaStream() override { --g_objectCount; }

  HRESULT RuntimeClassInitialize(IMFMediaSource* source,
                                 DWORD streamId) noexcept {
    source_ = source;
    streamId_ = streamId;
    frameSource_.SetOutputFormat(meo::kVirtualCameraWidth,
                                 meo::kVirtualCameraHeight);

    HRESULT hr = MFCreateEventQueue(&eventQueue_);
    if (FAILED(hr)) return hr;
    hr = CreateCameraMediaType(&mediaType_);
    if (FAILED(hr)) return hr;
    IMFMediaType* types[] = {mediaType_.Get()};
    hr = MFCreateStreamDescriptor(streamId, ARRAYSIZE(types), types,
                                  &descriptor_);
    if (FAILED(hr)) return hr;
    ComPtr<IMFMediaTypeHandler> handler;
    hr = descriptor_->GetMediaTypeHandler(&handler);
    if (FAILED(hr)) return hr;
    hr = handler->SetCurrentMediaType(mediaType_.Get());
    if (FAILED(hr)) return hr;
    hr = MFCreateAttributes(&attributes_, 4);
    if (FAILED(hr)) return hr;

    IMFAttributes* stores[] = {attributes_.Get(), descriptor_.Get()};
    for (IMFAttributes* store : stores) {
      if (FAILED(hr = store->SetGUID(MF_DEVICESTREAM_STREAM_CATEGORY,
                                     PINNAME_VIDEO_CAPTURE))) return hr;
      if (FAILED(hr = store->SetUINT32(MF_DEVICESTREAM_STREAM_ID,
                                       streamId))) return hr;
      if (FAILED(hr = store->SetUINT32(MF_DEVICESTREAM_FRAMESERVER_SHARED,
                                       1))) return hr;
      if (FAILED(hr = store->SetUINT32(
                       MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES,
                       MFFrameSourceTypes_Color))) return hr;
    }
    return S_OK;
  }

  IMFStreamDescriptor* descriptor() const noexcept { return descriptor_.Get(); }
  IMFAttributes* attributes() const noexcept { return attributes_.Get(); }

  HRESULT SetAllocator(IUnknown* allocator) noexcept {
    if (allocator == nullptr) return E_POINTER;
    if (state_.load(std::memory_order_acquire) == MF_STREAM_STATE_RUNNING) {
      return MF_E_INVALIDREQUEST;
    }
    ComPtr<IMFVideoSampleAllocator> typed;
    const HRESULT hr = allocator->QueryInterface(IID_PPV_ARGS(&typed));
    if (FAILED(hr)) return hr;
    allocator_ = typed;
    return S_OK;
  }

  HRESULT Start(IMFMediaType* requestedType, LONGLONG startTime) noexcept {
    if (requestedType == nullptr) return E_POINTER;
    DWORD equalFlags = 0;
    HRESULT hr = mediaType_->IsEqual(requestedType, &equalFlags);
    (void)equalFlags;
    if (hr != S_OK) {
      return MF_E_INVALIDMEDIATYPE;
    }
    if (!fallbackAllocator_) {
      ComPtr<IMFVideoSampleAllocatorEx> fallback;
      hr = MFCreateVideoSampleAllocatorEx(IID_PPV_ARGS(&fallback));
      if (FAILED(hr)) {
        return hr;
      }
      hr = fallback->InitializeSampleAllocator(4, mediaType_.Get());
      if (FAILED(hr)) {
        return hr;
      }
      fallbackAllocator_ = fallback;
    }
    if (!allocator_) allocator_ = fallbackAllocator_;
    if (allocator_.Get() != fallbackAllocator_.Get()) {
      hr = allocator_->InitializeSampleAllocator(4, mediaType_.Get());
      if (FAILED(hr)) allocator_ = fallbackAllocator_;
    }
    startTime_.store(startTime, std::memory_order_release);
    frameIndex_.store(0, std::memory_order_release);
    state_.store(MF_STREAM_STATE_RUNNING, std::memory_order_release);
    return S_OK;
  }

  void Stop() noexcept {
    state_.store(MF_STREAM_STATE_STOPPED, std::memory_order_release);
  }
  void PauseStream() noexcept {
    state_.store(MF_STREAM_STATE_PAUSED, std::memory_order_release);
  }
  void ShutdownStream() noexcept {
    shutdown_.store(true, std::memory_order_release);
    state_.store(MF_STREAM_STATE_STOPPED, std::memory_order_release);
    source_ = nullptr;
    if (eventQueue_) eventQueue_->Shutdown();
  }

  IFACEMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override {
    if (shutdown_.load(std::memory_order_acquire)) return MF_E_SHUTDOWN;
    return eventQueue_->GetEvent(flags, event);
  }
  IFACEMETHODIMP BeginGetEvent(IMFAsyncCallback* callback,
                               IUnknown* state) override {
    if (shutdown_.load(std::memory_order_acquire)) return MF_E_SHUTDOWN;
    return eventQueue_->BeginGetEvent(callback, state);
  }
  IFACEMETHODIMP EndGetEvent(IMFAsyncResult* result,
                             IMFMediaEvent** event) override {
    if (shutdown_.load(std::memory_order_acquire)) return MF_E_SHUTDOWN;
    return eventQueue_->EndGetEvent(result, event);
  }
  IFACEMETHODIMP QueueEvent(MediaEventType type, REFGUID extendedType,
                            HRESULT status,
                            const PROPVARIANT* eventValue) override {
    if (shutdown_.load(std::memory_order_acquire)) return MF_E_SHUTDOWN;
    return eventQueue_->QueueEventParamVar(type, extendedType, status,
                                           eventValue);
  }

  IFACEMETHODIMP GetMediaSource(IMFMediaSource** source) override {
    if (source == nullptr) return E_POINTER;
    if (shutdown_.load(std::memory_order_acquire) || source_ == nullptr) {
      return MF_E_SHUTDOWN;
    }
    source_->AddRef();
    *source = source_;
    return S_OK;
  }
  IFACEMETHODIMP GetStreamDescriptor(
      IMFStreamDescriptor** descriptor) override {
    if (descriptor == nullptr) return E_POINTER;
    if (shutdown_.load(std::memory_order_acquire)) return MF_E_SHUTDOWN;
    return descriptor_.CopyTo(descriptor);
  }

  IFACEMETHODIMP RequestSample(IUnknown* token) override {
    if (shutdown_.load(std::memory_order_acquire)) return MF_E_SHUTDOWN;
    if (state_.load(std::memory_order_acquire) != MF_STREAM_STATE_RUNNING) {
      return MF_E_INVALIDREQUEST;
    }

    ComPtr<IMFSample> sample;
    HRESULT hr = allocator_->AllocateSample(&sample);
    if (FAILED(hr) && fallbackAllocator_ &&
        allocator_.Get() != fallbackAllocator_.Get()) {
      hr = fallbackAllocator_->AllocateSample(&sample);
    }
    if (FAILED(hr)) {
      return hr;
    }
    ComPtr<IMFMediaBuffer> buffer;
    hr = sample->GetBufferByIndex(0, &buffer);
    if (FAILED(hr)) return hr;

    const UINT64 frame = frameIndex_.fetch_add(1, std::memory_order_relaxed);
    ComPtr<IMF2DBuffer2> buffer2d;
    if (SUCCEEDED(buffer.As(&buffer2d))) {
      BYTE* firstRow = nullptr;
      BYTE* bufferStart = nullptr;
      LONG pitch = 0;
      DWORD bufferLength = 0;
      hr = buffer2d->Lock2DSize(MF2DBuffer_LockFlags_Write, &firstRow, &pitch,
                                &bufferStart, &bufferLength);
      if (FAILED(hr)) return hr;
      const bool valid = pitch >= static_cast<LONG>(meo::kVirtualCameraWidth) &&
          bufferLength >= static_cast<DWORD>(pitch) *
                              meo::kVirtualCameraHeight * 3 / 2;
      if (valid) Fill(firstRow, static_cast<UINT32>(pitch), frame);
      buffer2d->Unlock2D();
      if (!valid) return MF_E_BUFFERTOOSMALL;
    } else {
      BYTE* data = nullptr;
      DWORD capacity = 0;
      hr = buffer->Lock(&data, nullptr, &capacity);
      if (FAILED(hr)) return hr;
      const bool valid = capacity >= meo::kVirtualCameraFrameBytes;
      if (valid) Fill(data, meo::kVirtualCameraWidth, frame);
      buffer->Unlock();
      if (!valid) return MF_E_BUFFERTOOSMALL;
    }

    hr = buffer->SetCurrentLength(meo::kVirtualCameraFrameBytes);
    if (FAILED(hr)) return hr;
    // Frame Server compares samples against the Media Foundation system
    // clock.  A zero-based timestamp makes every generated frame look stale,
    // so it discards them and requests replacements without bound.
    const LONGLONG timestamp = MFGetSystemTime();
    if (FAILED(hr = sample->SetSampleTime(timestamp))) return hr;
    if (FAILED(hr = sample->SetSampleDuration(
                       meo::kVirtualCameraFrameDuration))) return hr;
    if (FAILED(hr = sample->SetUINT32(MFSampleExtension_CleanPoint, TRUE))) {
      return hr;
    }
    if (token != nullptr &&
        FAILED(hr = sample->SetUnknown(MFSampleExtension_Token, token))) {
      return hr;
    }
    return eventQueue_->QueueEventParamUnk(MEMediaSample, GUID_NULL, S_OK,
                                            sample.Get());
  }

  IFACEMETHODIMP SetStreamState(MF_STREAM_STATE state) override {
    if (shutdown_.load(std::memory_order_acquire)) return MF_E_SHUTDOWN;
    const MF_STREAM_STATE current = state_.load(std::memory_order_acquire);
    if (current == state) return S_OK;

    switch (state) {
      case MF_STREAM_STATE_RUNNING:
        // Frame Server is allowed to drive IMFMediaStream2 directly.  A
        // RUNNING transition must perform the same allocator setup as the
        // source Start path; merely changing the state leaves RequestSample
        // with no allocator and stalls the consumer on its first frame.
        return Start(mediaType_.Get(), MFGetSystemTime());
      case MF_STREAM_STATE_PAUSED:
        if (current != MF_STREAM_STATE_RUNNING) {
          return MF_E_INVALID_STATE_TRANSITION;
        }
        PauseStream();
        return S_OK;
      case MF_STREAM_STATE_STOPPED:
        Stop();
        return S_OK;
      default:
        return MF_E_INVALID_STATE_TRANSITION;
    }
  }
  IFACEMETHODIMP GetStreamState(MF_STREAM_STATE* state) override {
    if (state == nullptr) return E_POINTER;
    if (shutdown_.load(std::memory_order_acquire)) return MF_E_SHUTDOWN;
    *state = state_.load(std::memory_order_acquire);
    return S_OK;
  }

  IFACEMETHODIMP KsProperty(PKSPROPERTY, ULONG, void*, ULONG,
                            ULONG* bytesReturned) override {
    return UnsupportedControl(bytesReturned);
  }
  IFACEMETHODIMP KsMethod(PKSMETHOD, ULONG, void*, ULONG,
                          ULONG* bytesReturned) override {
    return UnsupportedControl(bytesReturned);
  }
  IFACEMETHODIMP KsEvent(PKSEVENT, ULONG, void*, ULONG,
                         ULONG* bytesReturned) override {
    return UnsupportedControl(bytesReturned);
  }

 private:
  void Fill(BYTE* data, UINT32 stride, UINT64 frame) noexcept {
    if (!fillBusy_.test_and_set(std::memory_order_acquire)) {
      frameSource_.FillFrame(data, stride, frame);
      fillBusy_.clear(std::memory_order_release);
      return;
    }
    // Concurrent requests never wait for one another. They still receive a
    // valid frame, as required by the always-frame invariant.
    meo::RenderSlate(meo::ReadStatus::kStale, data,
                     meo::kVirtualCameraWidth, meo::kVirtualCameraHeight,
                     stride, frame);
  }

  IMFMediaSource* source_ = nullptr;
  DWORD streamId_ = 0;
  ComPtr<IMFMediaEventQueue> eventQueue_;
  ComPtr<IMFAttributes> attributes_;
  ComPtr<IMFStreamDescriptor> descriptor_;
  ComPtr<IMFMediaType> mediaType_;
  ComPtr<IMFVideoSampleAllocator> allocator_;
  ComPtr<IMFVideoSampleAllocator> fallbackAllocator_;
  meo::CameraFrameSource frameSource_;
  std::atomic<MF_STREAM_STATE> state_{MF_STREAM_STATE_STOPPED};
  std::atomic<bool> shutdown_{false};
  std::atomic<UINT64> frameIndex_{0};
  std::atomic<LONGLONG> startTime_{0};
  std::atomic_flag fillBusy_ = ATOMIC_FLAG_INIT;
};

class MediaSource final
    : public Microsoft::WRL::RuntimeClass<
          Microsoft::WRL::RuntimeClassFlags<Microsoft::WRL::ClassicCom>,
          Microsoft::WRL::ChainInterfaces<IMFMediaSourceEx, IMFMediaSource,
                                          IMFMediaEventGenerator>,
          IMFGetService, IKsControl, IMFSampleAllocatorControl> {
 public:
  MediaSource() { ++g_objectCount; }
  ~MediaSource() override { --g_objectCount; }

  HRESULT RuntimeClassInitialize(IMFAttributes* activateAttributes) noexcept {
    HRESULT hr = MFCreateEventQueue(&eventQueue_);
    if (FAILED(hr)) return hr;
    hr = MFCreateAttributes(&attributes_, 3);
    if (FAILED(hr)) return hr;
    if (activateAttributes != nullptr) {
      hr = activateAttributes->CopyAllItems(attributes_.Get());
      if (FAILED(hr)) return hr;
    }
    hr = attributes_->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES,
                                MFFrameSourceTypes_Color);
    if (FAILED(hr)) return hr;

    ComPtr<IMFSensorProfileCollection> profiles;
    hr = MFCreateSensorProfileCollection(&profiles);
    if (FAILED(hr)) return hr;
    ComPtr<IMFSensorProfile> legacyProfile;
    hr = MFCreateSensorProfile(KSCAMERAPROFILE_Legacy, 0, nullptr,
                               &legacyProfile);
    if (FAILED(hr)) return hr;
    hr = legacyProfile->AddProfileFilter(kStreamId,
                                         L"((RES==;FRT<=30,1;SUT==))");
    if (FAILED(hr)) return hr;
    hr = profiles->AddProfile(legacyProfile.Get());
    if (FAILED(hr)) return hr;
    hr = attributes_->SetUnknown(MF_DEVICEMFT_SENSORPROFILE_COLLECTION,
                                 profiles.Get());
    if (FAILED(hr)) return hr;
    try {
      hr = Microsoft::WRL::MakeAndInitialize<MediaStream>(
          &stream_, static_cast<IMFMediaSource*>(this), kStreamId);
    } catch (...) {
      return E_OUTOFMEMORY;
    }
    if (FAILED(hr)) return hr;
    IMFStreamDescriptor* descriptors[] = {stream_->descriptor()};
    hr = MFCreatePresentationDescriptor(ARRAYSIZE(descriptors), descriptors,
                                        &presentationDescriptor_);
    if (FAILED(hr)) return hr;
    hr = presentationDescriptor_->SelectStream(0);
    return hr;
  }

  IFACEMETHODIMP GetCharacteristics(DWORD* characteristics) override {
    if (characteristics == nullptr) return E_POINTER;
    std::lock_guard<std::mutex> guard(lock_);
    if (shutdown_) return MF_E_SHUTDOWN;
    *characteristics = MFMEDIASOURCE_IS_LIVE;
    return S_OK;
  }
  IFACEMETHODIMP CreatePresentationDescriptor(
      IMFPresentationDescriptor** descriptor) override {
    if (descriptor == nullptr) return E_POINTER;
    std::lock_guard<std::mutex> guard(lock_);
    if (shutdown_) return MF_E_SHUTDOWN;
    return presentationDescriptor_->Clone(descriptor);
  }
  IFACEMETHODIMP Start(IMFPresentationDescriptor* descriptor,
                       const GUID* timeFormat,
                       const PROPVARIANT* startPosition) override {
    if (descriptor == nullptr || startPosition == nullptr) return E_INVALIDARG;
    if (timeFormat != nullptr && *timeFormat != GUID_NULL) {
      return MF_E_UNSUPPORTED_TIME_FORMAT;
    }
    BOOL selected = FALSE;
    ComPtr<IMFStreamDescriptor> streamDescriptor;
    HRESULT hr = descriptor->GetStreamDescriptorByIndex(
        0, &selected, &streamDescriptor);
    if (FAILED(hr)) return hr;
    if (!selected) return MF_E_INVALIDREQUEST;
    ComPtr<IMFMediaTypeHandler> handler;
    if (FAILED(hr = streamDescriptor->GetMediaTypeHandler(&handler))) return hr;
    ComPtr<IMFMediaType> mediaType;
    if (FAILED(hr = handler->GetCurrentMediaType(&mediaType))) return hr;

    LONGLONG startTime = 0;
    if (startPosition->vt == VT_I8) startTime = startPosition->hVal.QuadPart;
    bool firstStart = false;
    ComPtr<IMFMediaEventQueue> queue;
    ComPtr<MediaStream> stream;
    {
      std::lock_guard<std::mutex> guard(lock_);
      if (shutdown_) return MF_E_SHUTDOWN;
      firstStart = !announced_;
      announced_ = true;
      queue = eventQueue_;
      stream = stream_;
    }
    if (FAILED(hr = queue->QueueEventParamUnk(
                   firstStart ? MENewStream : MEUpdatedStream, GUID_NULL,
                   S_OK, static_cast<IMFMediaStream*>(stream.Get())))) return hr;
    hr = stream->Start(mediaType.Get(), startTime);
    if (FAILED(hr)) {
      return hr;
    }
    PROPVARIANT eventValue;
    PropVariantInit(&eventValue);
    eventValue.vt = VT_I8;
    eventValue.hVal.QuadPart = startTime;
    hr = stream->QueueEvent(firstStart ? MEStreamStarted : MEStreamSeeked,
                            GUID_NULL, S_OK, &eventValue);
    if (SUCCEEDED(hr)) {
      hr = queue->QueueEventParamVar(
          firstStart ? MESourceStarted : MESourceSeeked, GUID_NULL, S_OK,
          &eventValue);
    }
    PropVariantClear(&eventValue);
    return hr;
  }
  IFACEMETHODIMP Stop() override {
    ComPtr<IMFMediaEventQueue> queue;
    ComPtr<MediaStream> stream;
    {
      std::lock_guard<std::mutex> guard(lock_);
      if (shutdown_) return MF_E_SHUTDOWN;
      queue = eventQueue_;
      stream = stream_;
    }
    stream->Stop();
    HRESULT hr = stream->QueueEvent(MEStreamStopped, GUID_NULL, S_OK, nullptr);
    if (FAILED(hr)) return hr;
    return queue->QueueEventParamVar(MESourceStopped, GUID_NULL, S_OK, nullptr);
  }
  IFACEMETHODIMP Pause() override {
    ComPtr<IMFMediaEventQueue> queue;
    ComPtr<MediaStream> stream;
    {
      std::lock_guard<std::mutex> guard(lock_);
      if (shutdown_) return MF_E_SHUTDOWN;
      queue = eventQueue_;
      stream = stream_;
    }
    stream->PauseStream();
    HRESULT hr = stream->QueueEvent(MEStreamPaused, GUID_NULL, S_OK, nullptr);
    if (FAILED(hr)) return hr;
    return queue->QueueEventParamVar(MESourcePaused, GUID_NULL, S_OK, nullptr);
  }
  IFACEMETHODIMP Shutdown() override {
    ComPtr<IMFMediaEventQueue> queue;
    ComPtr<MediaStream> stream;
    {
      std::lock_guard<std::mutex> guard(lock_);
      if (shutdown_) return MF_E_SHUTDOWN;
      shutdown_ = true;
      queue = eventQueue_;
      stream = stream_;
    }
    stream->ShutdownStream();
    queue->Shutdown();
    return S_OK;
  }

  IFACEMETHODIMP GetSourceAttributes(IMFAttributes** attributes) override {
    if (attributes == nullptr) return E_POINTER;
    std::lock_guard<std::mutex> guard(lock_);
    if (shutdown_) return MF_E_SHUTDOWN;
    return attributes_.CopyTo(attributes);
  }
  IFACEMETHODIMP GetStreamAttributes(DWORD streamId,
                                     IMFAttributes** attributes) override {
    if (attributes == nullptr) return E_POINTER;
    if (streamId != kStreamId) return MF_E_INVALIDSTREAMNUMBER;
    std::lock_guard<std::mutex> guard(lock_);
    if (shutdown_) return MF_E_SHUTDOWN;
    return stream_->attributes()->QueryInterface(IID_PPV_ARGS(attributes));
  }
  IFACEMETHODIMP SetD3DManager(IUnknown*) override { return E_NOTIMPL; }
  IFACEMETHODIMP GetService(REFGUID, REFIID, LPVOID* object) override {
    if (object == nullptr) return E_POINTER;
    *object = nullptr;
    return MF_E_UNSUPPORTED_SERVICE;
  }

  IFACEMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override {
    ComPtr<IMFMediaEventQueue> queue;
    {
      std::lock_guard<std::mutex> guard(lock_);
      if (shutdown_) return MF_E_SHUTDOWN;
      queue = eventQueue_;
    }
    return queue->GetEvent(flags, event);
  }
  IFACEMETHODIMP BeginGetEvent(IMFAsyncCallback* callback,
                               IUnknown* state) override {
    ComPtr<IMFMediaEventQueue> queue;
    {
      std::lock_guard<std::mutex> guard(lock_);
      if (shutdown_) return MF_E_SHUTDOWN;
      queue = eventQueue_;
    }
    return queue->BeginGetEvent(callback, state);
  }
  IFACEMETHODIMP EndGetEvent(IMFAsyncResult* result,
                             IMFMediaEvent** event) override {
    ComPtr<IMFMediaEventQueue> queue;
    {
      std::lock_guard<std::mutex> guard(lock_);
      if (shutdown_) return MF_E_SHUTDOWN;
      queue = eventQueue_;
    }
    return queue->EndGetEvent(result, event);
  }
  IFACEMETHODIMP QueueEvent(MediaEventType type, REFGUID extendedType,
                            HRESULT status,
                            const PROPVARIANT* eventValue) override {
    std::lock_guard<std::mutex> guard(lock_);
    if (shutdown_) return MF_E_SHUTDOWN;
    return eventQueue_->QueueEventParamVar(type, extendedType, status,
                                           eventValue);
  }

  IFACEMETHODIMP KsProperty(PKSPROPERTY, ULONG, void*, ULONG,
                            ULONG* bytesReturned) override {
    return UnsupportedControl(bytesReturned);
  }
  IFACEMETHODIMP KsMethod(PKSMETHOD, ULONG, void*, ULONG,
                          ULONG* bytesReturned) override {
    return UnsupportedControl(bytesReturned);
  }
  IFACEMETHODIMP KsEvent(PKSEVENT, ULONG, void*, ULONG,
                         ULONG* bytesReturned) override {
    return UnsupportedControl(bytesReturned);
  }
  IFACEMETHODIMP SetDefaultAllocator(DWORD outputStreamId,
                                     IUnknown* allocator) override {
    if (outputStreamId != kStreamId) return MF_E_INVALIDSTREAMNUMBER;
    ComPtr<MediaStream> stream;
    {
      std::lock_guard<std::mutex> guard(lock_);
      if (shutdown_) return MF_E_SHUTDOWN;
      stream = stream_;
    }
    return stream->SetAllocator(allocator);
  }
  IFACEMETHODIMP GetAllocatorUsage(
      DWORD outputStreamId, DWORD* inputStreamId,
      MFSampleAllocatorUsage* usage) override {
    if (inputStreamId == nullptr || usage == nullptr) return E_POINTER;
    if (outputStreamId != kStreamId) return MF_E_INVALIDSTREAMNUMBER;
    *inputStreamId = kStreamId;
    *usage = MFSampleAllocatorUsage_UsesProvidedAllocator;
    return S_OK;
  }

 private:
  static constexpr DWORD kStreamId = 0;
  std::mutex lock_;
  ComPtr<IMFMediaEventQueue> eventQueue_;
  ComPtr<IMFAttributes> attributes_;
  ComPtr<IMFPresentationDescriptor> presentationDescriptor_;
  ComPtr<MediaStream> stream_;
  bool shutdown_ = false;
  bool announced_ = false;
};

class Activate final
    : public Microsoft::WRL::RuntimeClass<
          Microsoft::WRL::RuntimeClassFlags<Microsoft::WRL::ClassicCom>,
          Microsoft::WRL::ChainInterfaces<IMFActivate, IMFAttributes>> {
 public:
  Activate() { ++g_objectCount; }
  ~Activate() override { --g_objectCount; }
  HRESULT RuntimeClassInitialize() noexcept {
    HRESULT hr = MFCreateAttributes(&attributes_, 4);
    if (FAILED(hr)) return hr;
    return attributes_->SetUINT32(
        MF_VIRTUALCAMERA_PROVIDE_ASSOCIATED_CAMERA_SOURCES, 1);
  }
  IFACEMETHODIMP ActivateObject(REFIID riid, void** object) override {
    if (object == nullptr) return E_POINTER;
    *object = nullptr;
    std::lock_guard<std::mutex> guard(lock_);
    if (!source_) {
      HRESULT hr = E_OUTOFMEMORY;
      try {
        hr = Microsoft::WRL::MakeAndInitialize<MediaSource>(
            &source_, attributes_.Get());
      } catch (...) {
        return E_OUTOFMEMORY;
      }
      if (FAILED(hr)) return hr;
    }
    return source_.CopyTo(riid, object);
  }
  IFACEMETHODIMP ShutdownObject() override {
    // The frame server can release the activation wrapper while retaining
    // the IMFMediaSource returned from ActivateObject. Its lifetime is owned
    // by that returned interface; shutting it down here leaves Windows with a
    // valid COM pointer to an unusable source. Microsoft's virtual-camera
    // sample intentionally makes this operation a no-op for the same reason.
    return S_OK;
  }
  IFACEMETHODIMP DetachObject() override {
    std::lock_guard<std::mutex> guard(lock_);
    source_.Reset();
    return S_OK;
  }

#define FORWARD_ATTRIBUTE(method, ...) \
  return attributes_ ? attributes_->method(__VA_ARGS__) : E_UNEXPECTED
  IFACEMETHODIMP GetItem(REFGUID k, PROPVARIANT* v) override { FORWARD_ATTRIBUTE(GetItem, k, v); }
  IFACEMETHODIMP GetItemType(REFGUID k, MF_ATTRIBUTE_TYPE* t) override { FORWARD_ATTRIBUTE(GetItemType, k, t); }
  IFACEMETHODIMP CompareItem(REFGUID k, REFPROPVARIANT v, BOOL* r) override { FORWARD_ATTRIBUTE(CompareItem, k, v, r); }
  IFACEMETHODIMP Compare(IMFAttributes* a, MF_ATTRIBUTES_MATCH_TYPE t, BOOL* r) override { FORWARD_ATTRIBUTE(Compare, a, t, r); }
  IFACEMETHODIMP GetUINT32(REFGUID k, UINT32* v) override { FORWARD_ATTRIBUTE(GetUINT32, k, v); }
  IFACEMETHODIMP GetUINT64(REFGUID k, UINT64* v) override { FORWARD_ATTRIBUTE(GetUINT64, k, v); }
  IFACEMETHODIMP GetDouble(REFGUID k, double* v) override { FORWARD_ATTRIBUTE(GetDouble, k, v); }
  IFACEMETHODIMP GetGUID(REFGUID k, GUID* v) override { FORWARD_ATTRIBUTE(GetGUID, k, v); }
  IFACEMETHODIMP GetStringLength(REFGUID k, UINT32* v) override { FORWARD_ATTRIBUTE(GetStringLength, k, v); }
  IFACEMETHODIMP GetString(REFGUID k, LPWSTR v, UINT32 s, UINT32* n) override { FORWARD_ATTRIBUTE(GetString, k, v, s, n); }
  IFACEMETHODIMP GetAllocatedString(REFGUID k, LPWSTR* v, UINT32* n) override { FORWARD_ATTRIBUTE(GetAllocatedString, k, v, n); }
  IFACEMETHODIMP GetBlobSize(REFGUID k, UINT32* s) override { FORWARD_ATTRIBUTE(GetBlobSize, k, s); }
  IFACEMETHODIMP GetBlob(REFGUID k, UINT8* v, UINT32 s, UINT32* n) override { FORWARD_ATTRIBUTE(GetBlob, k, v, s, n); }
  IFACEMETHODIMP GetAllocatedBlob(REFGUID k, UINT8** v, UINT32* s) override { FORWARD_ATTRIBUTE(GetAllocatedBlob, k, v, s); }
  IFACEMETHODIMP GetUnknown(REFGUID k, REFIID i, LPVOID* o) override { FORWARD_ATTRIBUTE(GetUnknown, k, i, o); }
  IFACEMETHODIMP SetItem(REFGUID k, REFPROPVARIANT v) override { FORWARD_ATTRIBUTE(SetItem, k, v); }
  IFACEMETHODIMP DeleteItem(REFGUID k) override { FORWARD_ATTRIBUTE(DeleteItem, k); }
  IFACEMETHODIMP DeleteAllItems() override { FORWARD_ATTRIBUTE(DeleteAllItems); }
  IFACEMETHODIMP SetUINT32(REFGUID k, UINT32 v) override { FORWARD_ATTRIBUTE(SetUINT32, k, v); }
  IFACEMETHODIMP SetUINT64(REFGUID k, UINT64 v) override { FORWARD_ATTRIBUTE(SetUINT64, k, v); }
  IFACEMETHODIMP SetDouble(REFGUID k, double v) override { FORWARD_ATTRIBUTE(SetDouble, k, v); }
  IFACEMETHODIMP SetGUID(REFGUID k, REFGUID v) override { FORWARD_ATTRIBUTE(SetGUID, k, v); }
  IFACEMETHODIMP SetString(REFGUID k, LPCWSTR v) override { FORWARD_ATTRIBUTE(SetString, k, v); }
  IFACEMETHODIMP SetBlob(REFGUID k, const UINT8* v, UINT32 s) override { FORWARD_ATTRIBUTE(SetBlob, k, v, s); }
  IFACEMETHODIMP SetUnknown(REFGUID k, IUnknown* o) override { FORWARD_ATTRIBUTE(SetUnknown, k, o); }
  IFACEMETHODIMP LockStore() override { FORWARD_ATTRIBUTE(LockStore); }
  IFACEMETHODIMP UnlockStore() override { FORWARD_ATTRIBUTE(UnlockStore); }
  IFACEMETHODIMP GetCount(UINT32* c) override { FORWARD_ATTRIBUTE(GetCount, c); }
  IFACEMETHODIMP GetItemByIndex(UINT32 i, GUID* k, PROPVARIANT* v) override { FORWARD_ATTRIBUTE(GetItemByIndex, i, k, v); }
  IFACEMETHODIMP CopyAllItems(IMFAttributes* d) override { FORWARD_ATTRIBUTE(CopyAllItems, d); }
#undef FORWARD_ATTRIBUTE

 private:
  std::mutex lock_;
  ComPtr<IMFAttributes> attributes_;
  ComPtr<MediaSource> source_;
};

class ClassFactory final : public IClassFactory {
 public:
  ClassFactory() { ++g_objectCount; }
  ~ClassFactory() { --g_objectCount; }
  IFACEMETHODIMP QueryInterface(REFIID riid, void** object) override {
    if (object == nullptr) return E_POINTER;
    *object = nullptr;
    if (riid != IID_IUnknown && riid != IID_IClassFactory) return E_NOINTERFACE;
    *object = static_cast<IClassFactory*>(this);
    AddRef();
    return S_OK;
  }
  IFACEMETHODIMP_(ULONG) AddRef() override { return ++refs_; }
  IFACEMETHODIMP_(ULONG) Release() override {
    const ULONG refs = --refs_;
    if (refs == 0) delete this;
    return refs;
  }
  IFACEMETHODIMP CreateInstance(IUnknown* outer, REFIID riid,
                                void** object) override {
    if (object == nullptr) return E_POINTER;
    *object = nullptr;
    if (outer != nullptr) return CLASS_E_NOAGGREGATION;
    ComPtr<Activate> activate;
    HRESULT hr = E_OUTOFMEMORY;
    try {
      hr = Microsoft::WRL::MakeAndInitialize<Activate>(&activate);
    } catch (...) {
      return E_OUTOFMEMORY;
    }
    if (FAILED(hr)) return hr;
    return activate.CopyTo(riid, object);
  }
  IFACEMETHODIMP LockServer(BOOL lock) override {
    if (lock) ++g_serverLocks;
    else --g_serverLocks;
    return S_OK;
  }
 private:
  std::atomic<ULONG> refs_{1};
};

std::wstring ClsidString() {
  wchar_t text[64] = {};
  StringFromGUID2(CLSID_MeoVirtualCameraSource, text, ARRAYSIZE(text));
  return text;
}
std::wstring ModulePath() {
  wchar_t path[MAX_PATH] = {};
  GetModuleFileNameW(g_module, path, ARRAYSIZE(path));
  return path;
}
HRESULT SetRegistryString(const std::wstring& keyPath, const wchar_t* name,
                          const std::wstring& value) {
  HKEY key = nullptr;
  LONG error = RegCreateKeyExW(HKEY_LOCAL_MACHINE, keyPath.c_str(), 0, nullptr,
                               REG_OPTION_NON_VOLATILE, KEY_WRITE, nullptr,
                               &key, nullptr);
  if (error != ERROR_SUCCESS) return HRESULT_FROM_WIN32(error);
  error = RegSetValueExW(
      key, name, 0, REG_SZ, reinterpret_cast<const BYTE*>(value.c_str()),
      static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t)));
  RegCloseKey(key);
  return error == ERROR_SUCCESS ? S_OK : HRESULT_FROM_WIN32(error);
}
HRESULT RegisterSource() {
  const std::wstring base =
      L"Software\\Classes\\CLSID\\" + ClsidString();
  HRESULT hr = SetRegistryString(base, nullptr, L"Meo Camera Media Source");
  if (FAILED(hr)) return hr;
  hr = SetRegistryString(base + L"\\InprocServer32", nullptr, ModulePath());
  if (FAILED(hr)) return hr;
  return SetRegistryString(base + L"\\InprocServer32", L"ThreadingModel",
                           L"Both");
}
HRESULT UnregisterSource() {
  const std::wstring base =
      L"Software\\Classes\\CLSID\\" + ClsidString();
  RegDeleteKeyExW(HKEY_LOCAL_MACHINE, (base + L"\\InprocServer32").c_str(),
                  0, 0);
  const LONG error = RegDeleteKeyExW(HKEY_LOCAL_MACHINE, base.c_str(), 0, 0);
  return error == ERROR_SUCCESS || error == ERROR_FILE_NOT_FOUND
             ? S_OK
             : HRESULT_FROM_WIN32(error);
}

}  // namespace

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
  if (reason == DLL_PROCESS_ATTACH) {
    g_module = module;
    DisableThreadLibraryCalls(module);
  }
  return TRUE;
}

STDAPI DllGetClassObject(REFCLSID clsid, REFIID riid, void** object) {
  if (object == nullptr) return E_POINTER;
  *object = nullptr;
  if (clsid != CLSID_MeoVirtualCameraSource) {
    return CLASS_E_CLASSNOTAVAILABLE;
  }
  auto* factory = new (std::nothrow) ClassFactory();
  if (factory == nullptr) return E_OUTOFMEMORY;
  const HRESULT hr = factory->QueryInterface(riid, object);
  factory->Release();
  return hr;
}
STDAPI DllCanUnloadNow() {
  return g_objectCount.load() == 0 && g_serverLocks.load() == 0 ? S_OK
                                                                : S_FALSE;
}
STDAPI DllRegisterServer() { return RegisterSource(); }
STDAPI DllUnregisterServer() { return UnregisterSource(); }

#endif
