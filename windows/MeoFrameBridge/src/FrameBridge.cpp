#include "meo/FrameBridge.h"

#include "Mapping.h"

#include <cstring>

#if defined(_WIN32)
#include <windows.h>
#else
#include <ctime>
#include <unistd.h>
#endif

namespace meo {
namespace {

// The atomics below live in memory shared between processes, so they have to
// be genuinely lock-free — a libstdc++ fallback lock would be per-process and
// would silently protect nothing across the boundary.
static_assert(std::atomic<uint64_t>::is_always_lock_free,
              "frame bridge needs lock-free 64-bit atomics in shared memory");
static_assert(std::atomic<uint32_t>::is_always_lock_free,
              "frame bridge needs lock-free 32-bit atomics in shared memory");

// ---------------------------------------------------------------------------
// Wire layout. Documented in adr/0006-windows-frame-bridge.md; the layout is
// versioned and a reader refuses anything it does not recognise.
// ---------------------------------------------------------------------------

struct SharedHeader {
  std::atomic<uint32_t> magic;            // 0  — written last on init
  std::atomic<uint16_t> layout_version;   // 4
  std::atomic<uint16_t> header_bytes;     // 6
  std::atomic<uint32_t> slot_count;       // 8
  std::atomic<uint32_t> slot_stride;      // 12
  std::atomic<uint32_t> payload_capacity; // 16
  std::atomic<uint32_t> reserved0;        // 20
  std::atomic<uint64_t> heartbeat_ms;     // 24
  std::atomic<uint64_t> instance_id;      // 32
  std::atomic<uint32_t> latest_slot;      // 40
  std::atomic<uint32_t> producer_pid;     // 44
  std::atomic<uint64_t> published_count;  // 48
  std::atomic<uint64_t> reserved1;        // 56
};
static_assert(sizeof(SharedHeader) == 64, "header layout is part of the wire");

struct SlotHeader {
  std::atomic<uint64_t> seq;           // 0  — odd while being written
  std::atomic<uint64_t> frame_id;      // 8
  std::atomic<uint64_t> timestamp_ms;  // 16
  std::atomic<uint32_t> width;         // 24
  std::atomic<uint32_t> height;        // 28
  std::atomic<uint32_t> stride_y;      // 32
  std::atomic<uint32_t> pixel_format;  // 36
  std::atomic<uint32_t> payload_bytes; // 40
  std::atomic<uint32_t> rotation;      // 44
  std::atomic<uint32_t> stream_state;  // 48
  std::atomic<uint32_t> flags;         // 52
  std::atomic<uint64_t> reserved;      // 56
};
static_assert(sizeof(SlotHeader) == 64, "slot layout is part of the wire");

constexpr uint32_t kFlagMirrored = 1u << 0;

// kPayloadCapacityBytes is already a multiple of 64, but assert it rather than
// trust it: a future format change that breaks alignment would otherwise
// misalign every slot's atomics.
static_assert(kPayloadCapacityBytes % 64 == 0, "slots must stay 64-aligned");
constexpr uint32_t kSlotStride = sizeof(SlotHeader) + kPayloadCapacityBytes;

// Bounds used when validating a header that may have been fuzzed (§13.1).
// These are sanity limits, not the exact expected values — the exact values are
// checked separately, and keeping both makes a malformed-vs-mismatched
// distinction possible in diagnostics later.
constexpr uint32_t kMaxPlausibleSlotCount = 16;
constexpr uint32_t kMaxPlausiblePayloadBytes = 64u * 1024 * 1024;

SharedHeader* HeaderOf(void* base) {
  return static_cast<SharedHeader*>(base);
}

SlotHeader* SlotAt(void* base, uint32_t index) {
  auto* bytes = static_cast<uint8_t*>(base);
  return reinterpret_cast<SlotHeader*>(bytes + sizeof(SharedHeader) +
                                       static_cast<size_t>(index) * kSlotStride);
}

uint8_t* PayloadAt(void* base, uint32_t index) {
  return reinterpret_cast<uint8_t*>(SlotAt(base, index)) + sizeof(SlotHeader);
}

// Bytes an NV12 frame of this geometry must occupy: a full-height Y plane
// followed by a half-height interleaved UV plane, both at the same stride.
//
// Computed in 64-bit and returned as 64-bit precisely because the inputs may
// be hostile — a fuzzed stride and height must not wrap a 32-bit product into
// something that looks reasonable.
uint64_t RequiredNV12Bytes(uint32_t stride_y, uint32_t height) {
  const uint64_t s = stride_y;
  const uint64_t h = height;
  return s * h + s * (h / 2);
}

bool GeometryIsPlausible(uint32_t width, uint32_t height, uint32_t stride_y,
                         PixelFormat format, uint32_t payload_bytes) {
  if (format != PixelFormat::kNV12) return false;
  if (width == 0 || height == 0) return false;
  if (width > kMaxWidth || height > kMaxHeight) return false;
  // NV12 subsamples chroma by two in both directions.
  if ((width % 2) != 0 || (height % 2) != 0) return false;
  if (stride_y < width) return false;
  if (payload_bytes == 0 || payload_bytes > kPayloadCapacityBytes) return false;
  return RequiredNV12Bytes(stride_y, height) == payload_bytes;
}

}  // namespace

// ---------------------------------------------------------------------------

size_t BridgeMappingBytes() {
  return sizeof(SharedHeader) + static_cast<size_t>(kSlotCount) * kSlotStride;
}

uint64_t MonotonicMillis() {
#if defined(_WIN32)
  // Machine-wide, monotonic, boot-relative, and cheap. Crucially it reads the
  // same value in every process, which is what makes a cross-process heartbeat
  // meaningful at all.
  return static_cast<uint64_t>(GetTickCount64());
#else
  timespec ts{};
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return static_cast<uint64_t>(ts.tv_sec) * 1000ull +
         static_cast<uint64_t>(ts.tv_nsec) / 1000000ull;
#endif
}

// ---------------------------------------------------------------------------
// Writer
// ---------------------------------------------------------------------------

FrameBridgeWriter::FrameBridgeWriter() = default;

FrameBridgeWriter::~FrameBridgeWriter() { Close(); }

bool FrameBridgeWriter::attached() const {
  return mapping_ != nullptr && mapping_->valid();
}

bool FrameBridgeWriter::Create(const char* name) {
  Close();

  auto mapping = std::make_unique<detail::Mapping>();
  if (!mapping->Create(name, BridgeMappingBytes())) return false;

  void* base = mapping->data();
  SharedHeader* header = HeaderOf(base);

  // Invalidate before touching anything else. A reader that arrives mid-init
  // must see "not a bridge" rather than a half-written one, and the magic is
  // the only field it checks before trusting the rest.
  header->magic.store(0, std::memory_order_release);
  std::atomic_thread_fence(std::memory_order_release);

  header->layout_version.store(kLayoutVersion, std::memory_order_relaxed);
  header->header_bytes.store(static_cast<uint16_t>(sizeof(SharedHeader)),
                             std::memory_order_relaxed);
  header->slot_count.store(kSlotCount, std::memory_order_relaxed);
  header->slot_stride.store(kSlotStride, std::memory_order_relaxed);
  header->payload_capacity.store(kPayloadCapacityBytes,
                                 std::memory_order_relaxed);
  header->reserved0.store(0, std::memory_order_relaxed);
  header->reserved1.store(0, std::memory_order_relaxed);
  header->latest_slot.store(0, std::memory_order_relaxed);
  header->published_count.store(0, std::memory_order_relaxed);
  header->heartbeat_ms.store(MonotonicMillis(), std::memory_order_relaxed);

#if defined(_WIN32)
  const uint64_t pid = static_cast<uint64_t>(GetCurrentProcessId());
#else
  const uint64_t pid = static_cast<uint64_t>(::getpid());
#endif
  header->producer_pid.store(static_cast<uint32_t>(pid),
                             std::memory_order_relaxed);

  // Identifies this run of the host. A reader that sees it change knows the
  // host restarted, which is worth distinguishing from a host that never
  // stopped. Not a secret and not used for trust.
  instance_id_ = (MonotonicMillis() << 16) ^ (pid * 0x9E3779B97F4A7C15ull);
  header->instance_id.store(instance_id_, std::memory_order_relaxed);

  // A crashed writer can leave a slot's seqlock odd forever, which would make
  // every future read of that slot retry and fail. Adopting a section means
  // inheriting that, so reset all of them.
  for (uint32_t i = 0; i < kSlotCount; ++i) {
    SlotHeader* slot = SlotAt(base, i);
    slot->seq.store(0, std::memory_order_relaxed);
    slot->frame_id.store(0, std::memory_order_relaxed);
    slot->timestamp_ms.store(0, std::memory_order_relaxed);
    slot->width.store(0, std::memory_order_relaxed);
    slot->height.store(0, std::memory_order_relaxed);
    slot->stride_y.store(0, std::memory_order_relaxed);
    slot->pixel_format.store(static_cast<uint32_t>(PixelFormat::kUnknown),
                             std::memory_order_relaxed);
    slot->payload_bytes.store(0, std::memory_order_relaxed);
    slot->rotation.store(0, std::memory_order_relaxed);
    slot->stream_state.store(static_cast<uint32_t>(StreamState::kNoPhonePaired),
                             std::memory_order_relaxed);
    slot->flags.store(0, std::memory_order_relaxed);
    slot->reserved.store(0, std::memory_order_relaxed);
  }

  // Publish last. Everything above is visible to any reader that observes it.
  header->magic.store(kBridgeMagic, std::memory_order_release);

  mapping_ = std::move(mapping);
  next_frame_id_ = 1;
  return true;
}

void FrameBridgeWriter::Close() {
  // Deliberately *not* invalidating the magic on the way out.
  //
  // A clean shutdown and a crash must look identical to the camera backend,
  // because only one of them gets to run cleanup code and §8.4 requires both to
  // produce the same slate. Absence is detected by the heartbeat going stale,
  // which covers both cases with one mechanism instead of two.
  mapping_.reset();
}

bool FrameBridgeWriter::PublishFrame(const void* payload,
                                     uint32_t payload_bytes,
                                     const FrameInfo& info) {
  if (!attached()) return false;
  if (payload == nullptr || payload_bytes == 0) return false;
  if (!GeometryIsPlausible(info.width, info.height, info.stride_y,
                           info.pixel_format, payload_bytes)) {
    // A caller error, not a bridge error: the host tried to publish something
    // its own readers would have to reject. Failing here keeps the malformed
    // frame out of shared memory entirely.
    return false;
  }

  void* base = mapping_->data();
  SharedHeader* header = HeaderOf(base);

  const uint32_t previous = header->latest_slot.load(std::memory_order_relaxed);
  const uint32_t index = (previous + 1) % kSlotCount;
  SlotHeader* slot = SlotAt(base, index);

  // Seqlock write: make the counter odd, publish the data, make it even again.
  const uint64_t seq = slot->seq.load(std::memory_order_relaxed);
  slot->seq.store(seq + 1, std::memory_order_relaxed);
  std::atomic_thread_fence(std::memory_order_release);

  slot->frame_id.store(next_frame_id_, std::memory_order_relaxed);
  slot->timestamp_ms.store(info.capture_timestamp_ms != 0
                               ? info.capture_timestamp_ms
                               : MonotonicMillis(),
                           std::memory_order_relaxed);
  slot->width.store(info.width, std::memory_order_relaxed);
  slot->height.store(info.height, std::memory_order_relaxed);
  slot->stride_y.store(info.stride_y, std::memory_order_relaxed);
  slot->pixel_format.store(static_cast<uint32_t>(info.pixel_format),
                           std::memory_order_relaxed);
  slot->payload_bytes.store(payload_bytes, std::memory_order_relaxed);
  slot->rotation.store(info.rotation_degrees, std::memory_order_relaxed);
  slot->stream_state.store(static_cast<uint32_t>(StreamState::kLive),
                           std::memory_order_relaxed);
  slot->flags.store(info.mirrored ? kFlagMirrored : 0u,
                    std::memory_order_relaxed);

  std::memcpy(PayloadAt(base, index), payload, payload_bytes);

  slot->seq.store(seq + 2, std::memory_order_release);

  header->latest_slot.store(index, std::memory_order_release);
  header->published_count.fetch_add(1, std::memory_order_relaxed);
  header->heartbeat_ms.store(MonotonicMillis(), std::memory_order_release);

  ++next_frame_id_;
  return true;
}

bool FrameBridgeWriter::PublishState(StreamState state) {
  if (!attached()) return false;
  if (state == StreamState::kUnknown) return false;

  void* base = mapping_->data();
  SharedHeader* header = HeaderOf(base);

  const uint32_t previous = header->latest_slot.load(std::memory_order_relaxed);
  const uint32_t index = (previous + 1) % kSlotCount;
  SlotHeader* slot = SlotAt(base, index);

  const uint64_t seq = slot->seq.load(std::memory_order_relaxed);
  slot->seq.store(seq + 1, std::memory_order_relaxed);
  std::atomic_thread_fence(std::memory_order_release);

  // Zero payload with zeroed geometry. §9.2 names zero-length frames as a case
  // the reader must survive; this is that case arriving legitimately rather
  // than as corruption, and it is how the camera is told to draw a slate
  // without the host rendering slate pixels itself.
  slot->frame_id.store(next_frame_id_, std::memory_order_relaxed);
  slot->timestamp_ms.store(MonotonicMillis(), std::memory_order_relaxed);
  slot->width.store(0, std::memory_order_relaxed);
  slot->height.store(0, std::memory_order_relaxed);
  slot->stride_y.store(0, std::memory_order_relaxed);
  slot->pixel_format.store(static_cast<uint32_t>(PixelFormat::kUnknown),
                           std::memory_order_relaxed);
  slot->payload_bytes.store(0, std::memory_order_relaxed);
  slot->rotation.store(0, std::memory_order_relaxed);
  slot->stream_state.store(static_cast<uint32_t>(state),
                           std::memory_order_relaxed);
  slot->flags.store(0, std::memory_order_relaxed);

  slot->seq.store(seq + 2, std::memory_order_release);

  header->latest_slot.store(index, std::memory_order_release);
  header->published_count.fetch_add(1, std::memory_order_relaxed);
  header->heartbeat_ms.store(MonotonicMillis(), std::memory_order_release);

  ++next_frame_id_;
  return true;
}

void FrameBridgeWriter::Heartbeat() {
  if (!attached()) return;
  HeaderOf(mapping_->data())
      ->heartbeat_ms.store(MonotonicMillis(), std::memory_order_release);
}

// ---------------------------------------------------------------------------
// Reader
// ---------------------------------------------------------------------------

FrameBridgeReader::FrameBridgeReader()
    : mapping_(std::make_unique<detail::Mapping>()) {}

FrameBridgeReader::~FrameBridgeReader() { Close(); }

bool FrameBridgeReader::attached() const {
  return mapping_->valid();
}

bool FrameBridgeReader::Open(const char* name) {
  Close();
  return mapping_->Open(name, BridgeMappingBytes());
}

void FrameBridgeReader::Close() { mapping_->Close(); }

ReadStatus FrameBridgeReader::ReadLatest(void* dest, size_t dest_capacity,
                                         FrameInfo* info) {
  return ReadInternal(dest, dest_capacity, info, /*copy_payload=*/true);
}

ReadStatus FrameBridgeReader::PeekStatus(FrameInfo* info) {
  return ReadInternal(nullptr, 0, info, /*copy_payload=*/false);
}

ReadStatus FrameBridgeReader::ReadInternal(void* dest, size_t dest_capacity,
                                           FrameInfo* info, bool copy_payload) {
  FrameInfo scratch;
  if (info == nullptr) info = &scratch;
  *info = FrameInfo{};

  if (!attached()) return ReadStatus::kNotAttached;

  void* base = mapping_->data();
  const SharedHeader* header = HeaderOf(base);

  // --- Header validation -------------------------------------------------
  // Everything below this point derefences offsets computed from these values,
  // so nothing here may be assumed. §13.1 fuzzes exactly this memory.
  if (header->magic.load(std::memory_order_acquire) != kBridgeMagic) {
    return ReadStatus::kMalformed;
  }
  if (header->layout_version.load(std::memory_order_relaxed) !=
      kLayoutVersion) {
    return ReadStatus::kMalformed;
  }
  if (header->header_bytes.load(std::memory_order_relaxed) !=
      sizeof(SharedHeader)) {
    return ReadStatus::kMalformed;
  }

  const uint32_t slot_count = header->slot_count.load(std::memory_order_relaxed);
  const uint32_t slot_stride =
      header->slot_stride.load(std::memory_order_relaxed);
  const uint32_t capacity =
      header->payload_capacity.load(std::memory_order_relaxed);

  if (slot_count == 0 || slot_count > kMaxPlausibleSlotCount) {
    return ReadStatus::kMalformed;
  }
  if (capacity == 0 || capacity > kMaxPlausiblePayloadBytes) {
    return ReadStatus::kMalformed;
  }
  if (slot_stride < sizeof(SlotHeader) + capacity) {
    return ReadStatus::kMalformed;
  }
  // The writer and this reader must agree exactly, not merely plausibly. A
  // bridge with a different geometry is a different bridge.
  if (slot_count != kSlotCount || slot_stride != kSlotStride ||
      capacity != kPayloadCapacityBytes) {
    return ReadStatus::kMalformed;
  }
  // Belt and braces: the computed extent must land inside what was mapped.
  const size_t extent =
      sizeof(SharedHeader) + static_cast<size_t>(slot_count) * slot_stride;
  if (extent > mapping_->size()) return ReadStatus::kMalformed;

  // --- Producer liveness (§8.4) ------------------------------------------
  const uint64_t now = MonotonicMillis();
  const uint64_t heartbeat = header->heartbeat_ms.load(std::memory_order_acquire);
  // A heartbeat in the future means the two reads straddled a tick, or the
  // value is garbage. Treat it as fresh either way; the frame-age check below
  // still catches a genuinely stalled stream.
  if (now > heartbeat && (now - heartbeat) > kProducerTimeoutMs) {
    return ReadStatus::kProducerAbsent;
  }

  // --- Seqlock read ------------------------------------------------------
  bool copied = false;
  for (int attempt = 0; attempt < kMaxReadAttempts; ++attempt) {
    // Re-read the newest slot on every attempt rather than once up front.
    // A retry means the writer overwrote the slot mid-copy, so the slot it has
    // just finished with is both the freshest frame available and the one it
    // will not revisit for another kSlotCount publishes — retrying against the
    // stale index would keep racing the same writer position.
    const uint32_t latest = header->latest_slot.load(std::memory_order_acquire);
    if (latest >= slot_count) return ReadStatus::kMalformed;

    const SlotHeader* slot = SlotAt(base, latest);

    const uint64_t seq_before = slot->seq.load(std::memory_order_acquire);
    if ((seq_before & 1ull) != 0) continue;  // writer is mid-publish

    FrameInfo candidate;
    candidate.frame_id = slot->frame_id.load(std::memory_order_relaxed);
    candidate.capture_timestamp_ms =
        slot->timestamp_ms.load(std::memory_order_relaxed);
    candidate.width = slot->width.load(std::memory_order_relaxed);
    candidate.height = slot->height.load(std::memory_order_relaxed);
    candidate.stride_y = slot->stride_y.load(std::memory_order_relaxed);
    candidate.pixel_format = static_cast<PixelFormat>(
        slot->pixel_format.load(std::memory_order_relaxed));
    candidate.payload_bytes =
        slot->payload_bytes.load(std::memory_order_relaxed);
    candidate.rotation_degrees = slot->rotation.load(std::memory_order_relaxed);
    candidate.stream_state = static_cast<StreamState>(
        slot->stream_state.load(std::memory_order_relaxed));
    candidate.mirrored =
        (slot->flags.load(std::memory_order_relaxed) & kFlagMirrored) != 0;

    // Metadata validation, before any of it is used to size a copy.
    if (candidate.payload_bytes > capacity) continue;
    if (candidate.payload_bytes > 0 &&
        !GeometryIsPlausible(candidate.width, candidate.height,
                             candidate.stride_y, candidate.pixel_format,
                             candidate.payload_bytes)) {
      // Either a torn read of the metadata or corruption. Retrying
      // distinguishes them: a torn read resolves, corruption does not.
      continue;
    }
    if (candidate.rotation_degrees % 90 != 0 ||
        candidate.rotation_degrees >= 360) {
      continue;
    }

    if (copy_payload && candidate.payload_bytes > 0) {
      if (dest == nullptr || dest_capacity < candidate.payload_bytes) {
        // The caller's buffer cannot hold what is published. Under correct
        // operation this is unreachable, because §5.4 fixes the bridge format
        // for the life of a stream — so reaching it means a mid-stream format
        // change, which §9.2 requires be survived rather than rendered.
        *info = candidate;
        return ReadStatus::kMalformed;
      }
      std::memcpy(dest, PayloadAt(base, latest), candidate.payload_bytes);
    }

    // The payload was copied out of a slot the writer may have re-entered, so
    // re-check the counter before trusting a single byte of it.
    std::atomic_thread_fence(std::memory_order_acquire);
    if (slot->seq.load(std::memory_order_relaxed) != seq_before) continue;

    *info = candidate;
    copied = true;
    break;
  }

  if (!copied) return ReadStatus::kTorn;

  // --- Classification ----------------------------------------------------
  info->age_ms = now > info->capture_timestamp_ms
                     ? now - info->capture_timestamp_ms
                     : 0;

  switch (info->stream_state) {
    case StreamState::kPaused:
      return ReadStatus::kPaused;
    case StreamState::kNoPhonePaired:
      return ReadStatus::kNoPhonePaired;
    case StreamState::kPhoneOffline:
      return ReadStatus::kPhoneOffline;
    case StreamState::kReconnecting:
      return ReadStatus::kReconnecting;
    case StreamState::kLive:
      break;
    case StreamState::kUnknown:
    default:
      return ReadStatus::kMalformed;
  }

  // Claims to be live. Two ways that claim can still be false:
  if (info->payload_bytes == 0) return ReadStatus::kStale;
  if (info->age_ms > kFrameStaleMs) return ReadStatus::kStale;

  return ReadStatus::kLive;
}

}  // namespace meo
