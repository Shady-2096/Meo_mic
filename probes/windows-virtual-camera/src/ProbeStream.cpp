#include "ProbeStream.h"

namespace meo {

ProbeStream::ProbeStream() = default;

HRESULT ProbeStream::Initialize(IMFMediaSource* source,
                                DWORD streamIdentifier) {
  source_ = source;
  streamIdentifier_ = streamIdentifier;

  HRESULT hr = MFCreateEventQueue(&eventQueue_);
  if (FAILED(hr)) {
    return hr;
  }

  ComPtr<IMFMediaType> mediaType;
  hr = CreateMediaType(&mediaType);
  if (FAILED(hr)) {
    return hr;
  }

  IMFMediaType* types[] = {mediaType.Get()};
  hr = MFCreateStreamDescriptor(streamIdentifier, ARRAYSIZE(types), types,
                                &descriptor_);
  if (FAILED(hr)) {
    return hr;
  }

  ComPtr<IMFMediaTypeHandler> handler;
  hr = descriptor_->GetMediaTypeHandler(&handler);
  if (FAILED(hr)) {
    return hr;
  }
  hr = handler->SetCurrentMediaType(mediaType.Get());
  if (FAILED(hr)) {
    return hr;
  }

  hr = MFCreateAttributes(&attributes_, 4);
  if (FAILED(hr)) {
    return hr;
  }

  // The frame server reads these from both the stream attribute store and
  // the descriptor. Keep them identical, as the Microsoft sample does.
  IMFAttributes* stores[] = {attributes_.Get(), descriptor_.Get()};
  for (IMFAttributes* store : stores) {
    hr = store->SetGUID(MF_DEVICESTREAM_STREAM_CATEGORY,
                        PINNAME_VIDEO_CAPTURE);
    if (FAILED(hr)) return hr;
    hr = store->SetUINT32(MF_DEVICESTREAM_STREAM_ID, streamIdentifier);
    if (FAILED(hr)) return hr;
    hr = store->SetUINT32(MF_DEVICESTREAM_FRAMESERVER_SHARED, 1);
    if (FAILED(hr)) return hr;
    hr = store->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES,
                          MFFrameSourceTypes_Color);
    if (FAILED(hr)) return hr;
  }

  return S_OK;
}

HRESULT ProbeStream::CreateMediaType(IMFMediaType** type) const {
  ComPtr<IMFMediaType> mediaType;
  HRESULT hr = MFCreateMediaType(&mediaType);
  if (FAILED(hr)) {
    return hr;
  }

  mediaType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
  mediaType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
  mediaType->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
  mediaType->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE);
  mediaType->SetUINT32(MF_MT_DEFAULT_STRIDE, kWidth);
  mediaType->SetUINT32(MF_MT_SAMPLE_SIZE, kFrameBytes);
  MFSetAttributeSize(mediaType.Get(), MF_MT_FRAME_SIZE, kWidth, kHeight);
  MFSetAttributeRatio(mediaType.Get(), MF_MT_FRAME_RATE, kFrameRate, 1);
  MFSetAttributeRatio(mediaType.Get(), MF_MT_FRAME_RATE_RANGE_MIN, kFrameRate,
                      1);
  MFSetAttributeRatio(mediaType.Get(), MF_MT_FRAME_RATE_RANGE_MAX, kFrameRate,
                      1);
  MFSetAttributeRatio(mediaType.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);

  *type = mediaType.Detach();
  return S_OK;
}

HRESULT ProbeStream::OnSourceStart(IMFMediaType* mediaType,
                                   LONGLONG startTime100ns) {
  if (mediaType == nullptr) {
    return E_POINTER;
  }
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_) {
    return MF_E_SHUTDOWN;
  }
  if (!sampleAllocator_) {
    // A normal frame-server activation supplies this through
    // IMFSampleAllocatorControl. The direct probe reader does not, so give
    // that diagnostic path an equivalent bounded allocator of its own.
    ComPtr<IMFVideoSampleAllocatorEx> fallbackAllocator;
    HRESULT hr = MFCreateVideoSampleAllocatorEx(
        IID_PPV_ARGS(&fallbackAllocator));
    if (FAILED(hr)) {
      return hr;
    }
    hr = fallbackAllocator->InitializeSampleAllocator(3, mediaType);
    if (FAILED(hr)) {
      return hr;
    }
    sampleAllocator_ = fallbackAllocator;
    ProbeLog(L"ProbeStream created fallback sample allocator");
  }
  currentMediaType_ = mediaType;
  startTime100ns_ = startTime100ns;
  wallClockStart100ns_ = static_cast<LONGLONG>(MFGetSystemTime());
  frameIndex_ = 0;
  state_ = MF_STREAM_STATE_RUNNING;
  ProbeLog(L"ProbeStream source start -> RUNNING");
  return S_OK;
}

HRESULT ProbeStream::OnSourceStop() {
  std::lock_guard<std::mutex> guard(lock_);
  state_ = MF_STREAM_STATE_STOPPED;
  return S_OK;
}

HRESULT ProbeStream::OnSourcePause() {
  std::lock_guard<std::mutex> guard(lock_);
  state_ = MF_STREAM_STATE_PAUSED;
  return S_OK;
}

void ProbeStream::OnSourceShutdown() {
  ComPtr<IMFMediaEventQueue> queue;
  {
    std::lock_guard<std::mutex> guard(lock_);
    shutdown_ = true;
    state_ = MF_STREAM_STATE_STOPPED;
    source_ = nullptr;
    queue = eventQueue_;
    eventQueue_.Reset();
  }
  if (queue) {
    queue->Shutdown();
  }
}

HRESULT ProbeStream::SetSampleAllocator(IUnknown* allocator) {
  if (allocator == nullptr) {
    return E_POINTER;
  }
  ComPtr<IMFVideoSampleAllocator> videoAllocator;
  HRESULT hr = allocator->QueryInterface(IID_PPV_ARGS(&videoAllocator));
  if (FAILED(hr)) {
    return hr;
  }
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_) {
    return MF_E_SHUTDOWN;
  }
  sampleAllocator_ = videoAllocator;
  ProbeLog(L"ProbeStream received frame-server sample allocator");
  return S_OK;
}

HRESULT ProbeStream::CreateSample(IMFSample** sample) {
  if (sample == nullptr) {
    return E_POINTER;
  }

  ComPtr<IMFSample> newSample;
  HRESULT hr = sampleAllocator_->AllocateSample(&newSample);
  if (FAILED(hr)) {
    return hr;
  }

  ComPtr<IMFMediaBuffer> buffer;
  hr = newSample->GetBufferByIndex(0, &buffer);
  if (FAILED(hr)) {
    return hr;
  }

  ComPtr<IMF2DBuffer2> buffer2d;
  if (SUCCEEDED(buffer.As(&buffer2d))) {
    BYTE* scanline0 = nullptr;
    BYTE* bufferStart = nullptr;
    LONG pitch = 0;
    DWORD bufferLength = 0;
    hr = buffer2d->Lock2DSize(MF2DBuffer_LockFlags_Write, &scanline0, &pitch,
                              &bufferStart, &bufferLength);
    if (FAILED(hr)) {
      return hr;
    }
    if (pitch < static_cast<LONG>(kWidth) ||
        bufferLength < static_cast<DWORD>(pitch) * kHeight * 3 / 2) {
      buffer2d->Unlock2D();
      return MF_E_BUFFERTOOSMALL;
    }
    WriteTestFrame(scanline0, pitch, frameIndex_);
    buffer2d->Unlock2D();
  } else {
    BYTE* data = nullptr;
    DWORD capacity = 0;
    hr = buffer->Lock(&data, nullptr, &capacity);
    if (FAILED(hr)) {
      return hr;
    }
    if (capacity < kFrameBytes) {
      buffer->Unlock();
      return MF_E_BUFFERTOOSMALL;
    }
    WriteTestFrame(data, static_cast<LONG>(kWidth), frameIndex_);
    buffer->Unlock();
  }

  hr = buffer->SetCurrentLength(kFrameBytes);
  if (FAILED(hr)) {
    return hr;
  }

  // Timestamps come from the frame counter rather than the wall clock, so
  // they stay exactly one frame apart. A consumer that requests samples
  // faster than real time still gets a well-formed 30 FPS timeline.
  const LONGLONG timestamp =
      startTime100ns_ +
      static_cast<LONGLONG>(frameIndex_) * kFrameDuration100ns;
  newSample->SetSampleTime(timestamp);
  newSample->SetSampleDuration(kFrameDuration100ns);
  newSample->SetUINT32(MFSampleExtension_CleanPoint, TRUE);

  *sample = newSample.Detach();
  return S_OK;
}

IFACEMETHODIMP ProbeStream::RequestSample(IUnknown* token) {
  ComPtr<IMFSample> sample;
  ComPtr<IMFMediaEventQueue> queue;

  {
    std::lock_guard<std::mutex> guard(lock_);
    if (shutdown_ || !eventQueue_) {
      return MF_E_SHUTDOWN;
    }
    if (state_ != MF_STREAM_STATE_RUNNING) {
      ProbeLog(L"ProbeStream RequestSample rejected in state %u",
               static_cast<unsigned>(state_));
      return MF_E_INVALIDREQUEST;
    }

    if (frameIndex_ < 3) {
      ProbeLog(L"ProbeStream RequestSample frame %llu", frameIndex_);
    }

    // Hold the request until this frame is actually due. Without this the
    // pipeline drains samples as fast as the CPU allows and the sweep bar in
    // the test pattern races, which makes a genuinely stalled camera hard to
    // tell from a working one.
    const LONGLONG elapsed =
        static_cast<LONGLONG>(MFGetSystemTime()) - wallClockStart100ns_;
    const LONGLONG due =
        static_cast<LONGLONG>(frameIndex_) * kFrameDuration100ns;
    if (due > elapsed) {
      const LONGLONG wait100ns = due - elapsed;
      // Cap the wait at one frame so a clock jump can never park a pipeline
      // thread here.
      const DWORD waitMs = static_cast<DWORD>(
          (wait100ns > kFrameDuration100ns ? kFrameDuration100ns : wait100ns) /
          10'000);
      if (waitMs > 0) {
        Sleep(waitMs);
      }
    }

    HRESULT hr = CreateSample(&sample);
    if (FAILED(hr)) {
      return hr;
    }
    ++frameIndex_;
    queue = eventQueue_;
  }

  if (token != nullptr) {
    sample->SetUnknown(MFSampleExtension_Token, token);
  }

  PROPVARIANT value;
  PropVariantInit(&value);
  value.vt = VT_UNKNOWN;
  value.punkVal = sample.Get();
  value.punkVal->AddRef();

  const HRESULT hr =
      queue->QueueEventParamVar(MEMediaSample, GUID_NULL, S_OK, &value);
  PropVariantClear(&value);
  return hr;
}

IFACEMETHODIMP ProbeStream::SetStreamState(MF_STREAM_STATE state) {
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_) {
    return MF_E_SHUTDOWN;
  }
  ProbeLog(L"ProbeStream SetStreamState %u -> %u",
           static_cast<unsigned>(state_), static_cast<unsigned>(state));
  state_ = state;
  return S_OK;
}

IFACEMETHODIMP ProbeStream::GetStreamState(MF_STREAM_STATE* state) {
  if (state == nullptr) {
    return E_POINTER;
  }
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_) {
    return MF_E_SHUTDOWN;
  }
  *state = state_;
  return S_OK;
}

IFACEMETHODIMP ProbeStream::GetMediaSource(IMFMediaSource** source) {
  if (source == nullptr) {
    return E_POINTER;
  }
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_ || source_ == nullptr) {
    return MF_E_SHUTDOWN;
  }
  source_->AddRef();
  *source = source_;
  return S_OK;
}

IFACEMETHODIMP ProbeStream::GetStreamDescriptor(
    IMFStreamDescriptor** descriptor) {
  if (descriptor == nullptr) {
    return E_POINTER;
  }
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_ || !descriptor_) {
    return MF_E_SHUTDOWN;
  }
  return descriptor_.CopyTo(descriptor);
}

IFACEMETHODIMP ProbeStream::GetEvent(DWORD flags, IMFMediaEvent** event) {
  ComPtr<IMFMediaEventQueue> queue;
  {
    std::lock_guard<std::mutex> guard(lock_);
    if (shutdown_ || !eventQueue_) {
      return MF_E_SHUTDOWN;
    }
    queue = eventQueue_;
  }
  // Deliberately outside the lock: GetEvent blocks when flags is 0.
  return queue->GetEvent(flags, event);
}

IFACEMETHODIMP ProbeStream::BeginGetEvent(IMFAsyncCallback* callback,
                                          IUnknown* state) {
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_ || !eventQueue_) {
    return MF_E_SHUTDOWN;
  }
  return eventQueue_->BeginGetEvent(callback, state);
}

IFACEMETHODIMP ProbeStream::EndGetEvent(IMFAsyncResult* result,
                                        IMFMediaEvent** event) {
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_ || !eventQueue_) {
    return MF_E_SHUTDOWN;
  }
  return eventQueue_->EndGetEvent(result, event);
}

IFACEMETHODIMP ProbeStream::QueueEvent(MediaEventType type,
                                       REFGUID extendedType, HRESULT status,
                                       const PROPVARIANT* value) {
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_ || !eventQueue_) {
    return MF_E_SHUTDOWN;
  }
  return eventQueue_->QueueEventParamVar(type, extendedType, status, value);
}

IFACEMETHODIMP
ProbeStream::KsProperty(PKSPROPERTY, ULONG, void*, ULONG, ULONG* bytesReturned) {
  if (bytesReturned != nullptr) {
    *bytesReturned = 0;
  }
  return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
}

IFACEMETHODIMP
ProbeStream::KsMethod(PKSMETHOD, ULONG, void*, ULONG, ULONG* bytesReturned) {
  if (bytesReturned != nullptr) {
    *bytesReturned = 0;
  }
  return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
}

IFACEMETHODIMP
ProbeStream::KsEvent(PKSEVENT, ULONG, void*, ULONG, ULONG* bytesReturned) {
  if (bytesReturned != nullptr) {
    *bytesReturned = 0;
  }
  return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
}

}  // namespace meo
