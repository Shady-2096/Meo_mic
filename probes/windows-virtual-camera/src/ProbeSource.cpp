#include "ProbeSource.h"

#include <wrl/module.h>

namespace meo {

ProbeSource::ProbeSource() = default;

IFACEMETHODIMP ProbeSource::QueryInterface(REFIID riid, void** object) {
  wchar_t requestedInterface[64] = {};
  StringFromGUID2(riid, requestedInterface, ARRAYSIZE(requestedInterface));
  const HRESULT hr = RuntimeClassT::QueryInterface(riid, object);
  ProbeLog(L"ProbeSource::QueryInterface %s -> %s", requestedInterface,
           FormatHresult(hr).c_str());
  return hr;
}

HRESULT ProbeSource::RuntimeClassInitialize() {
  ProbeLog(L"ProbeSource created in process %lu", GetCurrentProcessId());

  HRESULT hr = MFCreateEventQueue(&eventQueue_);
  if (FAILED(hr)) {
    return hr;
  }
  ProbeLog(L"ProbeSource event queue created");

  hr = MFCreateAttributes(&attributes_, 4);
  if (FAILED(hr)) {
    return hr;
  }
  ProbeLog(L"ProbeSource attributes created");
  // Declares this as a colour camera rather than depth or infrared. Without
  // it the frame server may classify the device as a sensor and hide it from
  // ordinary camera pickers.
  hr = attributes_->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES,
                              MFFrameSourceTypes_Color);
  if (FAILED(hr)) {
    return hr;
  }
  ProbeLog(L"ProbeSource frame-source type set");

  stream_ = Microsoft::WRL::Make<ProbeStream>();
  if (!stream_) {
    return E_OUTOFMEMORY;
  }
  ProbeLog(L"ProbeSource stream allocated");
  hr = stream_->Initialize(static_cast<IMFMediaSource*>(this),
                           kStreamIdentifier);
  if (FAILED(hr)) {
    return hr;
  }
  ProbeLog(L"ProbeSource stream initialized");

  IMFStreamDescriptor* descriptors[] = {stream_->Descriptor()};
  hr = MFCreatePresentationDescriptor(ARRAYSIZE(descriptors), descriptors,
                                      &presentationDescriptor_);
  if (FAILED(hr)) {
    return hr;
  }
  ProbeLog(L"ProbeSource presentation descriptor created");
  return presentationDescriptor_->SelectStream(0);
}

IFACEMETHODIMP ProbeSource::GetCharacteristics(DWORD* characteristics) {
  if (characteristics == nullptr) {
    return E_POINTER;
  }
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_) {
    return MF_E_SHUTDOWN;
  }
  // A camera is a live source: it cannot seek and it cannot be rewound.
  *characteristics = MFMEDIASOURCE_IS_LIVE;
  return S_OK;
}

IFACEMETHODIMP ProbeSource::CreatePresentationDescriptor(
    IMFPresentationDescriptor** descriptor) {
  if (descriptor == nullptr) {
    return E_POINTER;
  }
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_ || !presentationDescriptor_) {
    return MF_E_SHUTDOWN;
  }
  // Each caller gets its own copy; handing out the shared one lets a consumer
  // mutate stream selection under another consumer's feet.
  return presentationDescriptor_->Clone(descriptor);
}

IFACEMETHODIMP ProbeSource::Start(IMFPresentationDescriptor* descriptor,
                                  const GUID* timeFormat,
                                  const PROPVARIANT* startPosition) {
  if (descriptor == nullptr || startPosition == nullptr) {
    return E_INVALIDARG;
  }
  if (timeFormat != nullptr && *timeFormat != GUID_NULL) {
    return MF_E_UNSUPPORTED_TIME_FORMAT;
  }

  ComPtr<IMFMediaEventQueue> queue;
  Microsoft::WRL::ComPtr<ProbeStream> stream;
  bool announce = false;
  LONGLONG startTime = 0;
  ComPtr<IMFMediaType> mediaType;

  BOOL selected = FALSE;
  ComPtr<IMFStreamDescriptor> selectedDescriptor;
  HRESULT hr = descriptor->GetStreamDescriptorByIndex(
      0, &selected, &selectedDescriptor);
  if (FAILED(hr)) {
    return hr;
  }
  if (!selected) {
    return MF_E_INVALIDREQUEST;
  }
  ComPtr<IMFMediaTypeHandler> handler;
  hr = selectedDescriptor->GetMediaTypeHandler(&handler);
  if (FAILED(hr)) {
    return hr;
  }
  hr = handler->GetCurrentMediaType(&mediaType);
  if (FAILED(hr)) {
    return hr;
  }

  {
    std::lock_guard<std::mutex> guard(lock_);
    if (shutdown_) {
      return MF_E_SHUTDOWN;
    }
    if (startPosition != nullptr && startPosition->vt == VT_I8) {
      startTime = startPosition->hVal.QuadPart;
    }
    queue = eventQueue_;
    stream = stream_;
    announce = !announcedStream_;
    announcedStream_ = true;
  }

  ProbeLog(L"Start requested (startTime=%lld, firstStart=%d)", startTime,
           announce ? 1 : 0);

  // The pipeline learns about the stream through MENewStream the first time
  // and MEUpdatedStream on every restart. Getting this order wrong is a
  // common way for a source to start but never deliver frames.
  PROPVARIANT streamValue;
  PropVariantInit(&streamValue);
  streamValue.vt = VT_UNKNOWN;
  streamValue.punkVal = static_cast<IMFMediaStream*>(stream.Get());
  streamValue.punkVal->AddRef();
  queue->QueueEventParamVar(announce ? MENewStream : MEUpdatedStream,
                            GUID_NULL, S_OK, &streamValue);
  PropVariantClear(&streamValue);

  hr = stream->OnSourceStart(mediaType.Get(), startTime);
  if (FAILED(hr)) {
    return hr;
  }

  PROPVARIANT timeValue;
  PropVariantInit(&timeValue);
  timeValue.vt = VT_I8;
  timeValue.hVal.QuadPart = startTime;
  stream->QueueEvent(announce ? MEStreamStarted : MEStreamSeeked, GUID_NULL,
                     S_OK, &timeValue);
  queue->QueueEventParamVar(announce ? MESourceStarted : MESourceSeeked,
                            GUID_NULL, S_OK, &timeValue);
  PropVariantClear(&timeValue);

  return S_OK;
}

IFACEMETHODIMP ProbeSource::Stop() {
  ComPtr<IMFMediaEventQueue> queue;
  Microsoft::WRL::ComPtr<ProbeStream> stream;
  {
    std::lock_guard<std::mutex> guard(lock_);
    if (shutdown_) {
      return MF_E_SHUTDOWN;
    }
    queue = eventQueue_;
    stream = stream_;
  }

  ProbeLog(L"Stop requested");
  stream->OnSourceStop();
  stream->QueueEvent(MEStreamStopped, GUID_NULL, S_OK, nullptr);
  return queue->QueueEventParamVar(MESourceStopped, GUID_NULL, S_OK, nullptr);
}

IFACEMETHODIMP ProbeSource::Pause() {
  ComPtr<IMFMediaEventQueue> queue;
  Microsoft::WRL::ComPtr<ProbeStream> stream;
  {
    std::lock_guard<std::mutex> guard(lock_);
    if (shutdown_) {
      return MF_E_SHUTDOWN;
    }
    queue = eventQueue_;
    stream = stream_;
  }

  ProbeLog(L"Pause requested");
  stream->OnSourcePause();
  stream->QueueEvent(MEStreamPaused, GUID_NULL, S_OK, nullptr);
  return queue->QueueEventParamVar(MESourcePaused, GUID_NULL, S_OK, nullptr);
}

IFACEMETHODIMP ProbeSource::Shutdown() {
  ComPtr<IMFMediaEventQueue> queue;
  Microsoft::WRL::ComPtr<ProbeStream> stream;
  {
    std::lock_guard<std::mutex> guard(lock_);
    if (shutdown_) {
      return MF_E_SHUTDOWN;
    }
    shutdown_ = true;
    queue = eventQueue_;
    stream = stream_;
    eventQueue_.Reset();
    presentationDescriptor_.Reset();
    stream_.Reset();
  }

  ProbeLog(L"Shutdown requested");
  if (stream) {
    stream->OnSourceShutdown();
  }
  if (queue) {
    queue->Shutdown();
  }
  return S_OK;
}

IFACEMETHODIMP ProbeSource::GetSourceAttributes(IMFAttributes** attributes) {
  if (attributes == nullptr) {
    return E_POINTER;
  }
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_ || !attributes_) {
    return MF_E_SHUTDOWN;
  }
  return attributes_.CopyTo(attributes);
}

IFACEMETHODIMP ProbeSource::GetStreamAttributes(DWORD streamIdentifier,
                                                IMFAttributes** attributes) {
  if (attributes == nullptr) {
    return E_POINTER;
  }
  if (streamIdentifier != kStreamIdentifier) {
    return MF_E_INVALIDSTREAMNUMBER;
  }
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_ || !stream_) {
    return MF_E_SHUTDOWN;
  }
  return stream_->Attributes()->QueryInterface(IID_PPV_ARGS(attributes));
}

IFACEMETHODIMP ProbeSource::SetD3DManager(IUnknown* /*manager*/) {
  // The probe hands out plain system-memory buffers, so there is nothing to
  // do with a D3D manager. Returning S_OK rather than E_NOTIMPL avoids the
  // frame server treating the source as broken.
  return S_OK;
}

IFACEMETHODIMP ProbeSource::GetService(REFGUID /*service*/, REFIID riid,
                                       LPVOID* object) {
  if (object == nullptr) {
    return E_POINTER;
  }
  *object = nullptr;
  wchar_t requestedInterface[64] = {};
  StringFromGUID2(riid, requestedInterface, ARRAYSIZE(requestedInterface));
  ProbeLog(L"ProbeSource::GetService requested %s", requestedInterface);
  return MF_E_UNSUPPORTED_SERVICE;
}

IFACEMETHODIMP ProbeSource::GetEvent(DWORD flags, IMFMediaEvent** event) {
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

IFACEMETHODIMP ProbeSource::BeginGetEvent(IMFAsyncCallback* callback,
                                          IUnknown* state) {
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_ || !eventQueue_) {
    return MF_E_SHUTDOWN;
  }
  return eventQueue_->BeginGetEvent(callback, state);
}

IFACEMETHODIMP ProbeSource::EndGetEvent(IMFAsyncResult* result,
                                        IMFMediaEvent** event) {
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_ || !eventQueue_) {
    return MF_E_SHUTDOWN;
  }
  return eventQueue_->EndGetEvent(result, event);
}

IFACEMETHODIMP ProbeSource::QueueEvent(MediaEventType type,
                                       REFGUID extendedType, HRESULT status,
                                       const PROPVARIANT* value) {
  std::lock_guard<std::mutex> guard(lock_);
  if (shutdown_ || !eventQueue_) {
    return MF_E_SHUTDOWN;
  }
  return eventQueue_->QueueEventParamVar(type, extendedType, status, value);
}

IFACEMETHODIMP
ProbeSource::KsProperty(PKSPROPERTY, ULONG, void*, ULONG,
                        ULONG* bytesReturned) {
  if (bytesReturned != nullptr) {
    *bytesReturned = 0;
  }
  return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
}

IFACEMETHODIMP
ProbeSource::KsMethod(PKSMETHOD, ULONG, void*, ULONG, ULONG* bytesReturned) {
  if (bytesReturned != nullptr) {
    *bytesReturned = 0;
  }
  return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
}

IFACEMETHODIMP
ProbeSource::KsEvent(PKSEVENT, ULONG, void*, ULONG, ULONG* bytesReturned) {
  if (bytesReturned != nullptr) {
    *bytesReturned = 0;
  }
  return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
}

IFACEMETHODIMP ProbeSource::SetDefaultAllocator(
    DWORD outputStreamIdentifier, IUnknown* allocator) {
  if (outputStreamIdentifier != kStreamIdentifier) {
    return MF_E_INVALIDSTREAMNUMBER;
  }
  Microsoft::WRL::ComPtr<ProbeStream> stream;
  {
    std::lock_guard<std::mutex> guard(lock_);
    if (shutdown_ || !stream_) {
      return MF_E_SHUTDOWN;
    }
    stream = stream_;
  }
  return stream->SetSampleAllocator(allocator);
}

IFACEMETHODIMP ProbeSource::GetAllocatorUsage(
    DWORD outputStreamIdentifier, DWORD* inputStreamIdentifier,
    MFSampleAllocatorUsage* usage) {
  if (inputStreamIdentifier == nullptr || usage == nullptr) {
    return E_POINTER;
  }
  if (outputStreamIdentifier != kStreamIdentifier) {
    return MF_E_INVALIDSTREAMNUMBER;
  }
  *inputStreamIdentifier = kStreamIdentifier;
  *usage = MFSampleAllocatorUsage_UsesProvidedAllocator;
  return S_OK;
}

}  // namespace meo
