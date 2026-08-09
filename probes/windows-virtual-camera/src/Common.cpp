#include "Common.h"

#include <cstdarg>
#include <cstdio>

// {0B914DE5-CF52-4F35-B43D-104314D226D1}
extern "C" const GUID CLSID_MeoProbeSource = {
    0x0b914de5,
    0xcf52,
    0x4f35,
    {0xb4, 0x3d, 0x10, 0x43, 0x14, 0xd2, 0x26, 0xd1}};

namespace meo {
namespace {

// Eight colour bars across the frame, in Y/U/V. Values are studio-range
// (Y 16-235) because that is what a camera source is expected to emit and
// because a full-range frame shown through a studio-range consumer is a
// washed-out grey that looks like a bug elsewhere.
struct Bar {
  BYTE y, u, v;
};

constexpr Bar kBars[8] = {
    {235, 128, 128},  // white
    {210, 16, 146},   // yellow
    {170, 166, 16},   // cyan
    {145, 54, 34},    // green
    {106, 202, 222},  // magenta
    {81, 90, 240},    // red
    {41, 240, 110},   // blue
    {16, 128, 128},   // black
};

}  // namespace

void WriteTestFrame(BYTE* dest, LONG stride, UINT64 frameIndex) {
  if (dest == nullptr || stride < static_cast<LONG>(kWidth)) {
    return;
  }

  // A bar that crosses the frame once every two seconds. If this stops
  // moving, frames have stopped arriving — which is exactly the "stale frozen
  // image mistaken for live" failure the plan calls out in §14.
  const UINT32 sweepPeriod = kFrameRate * 2;
  const UINT32 sweepX =
      static_cast<UINT32>((frameIndex % sweepPeriod) * kWidth / sweepPeriod);
  constexpr UINT32 kSweepHalfWidth = 6;

  const UINT32 barWidth = kWidth / 8;

  // Y plane.
  for (UINT32 y = 0; y < kHeight; ++y) {
    BYTE* row = dest + static_cast<size_t>(y) * stride;
    for (UINT32 x = 0; x < kWidth; ++x) {
      const Bar& bar = kBars[(x / barWidth) & 7];
      const bool inSweep = (x + kSweepHalfWidth > sweepX) &&
                           (x < sweepX + kSweepHalfWidth);
      row[x] = inSweep ? 235 : bar.y;
    }
  }

  // Interleaved UV plane at half resolution in both axes. It starts
  // immediately after the Y plane, using the same stride.
  BYTE* uvPlane = dest + static_cast<size_t>(kHeight) * stride;
  for (UINT32 y = 0; y < kHeight / 2; ++y) {
    BYTE* row = uvPlane + static_cast<size_t>(y) * stride;
    for (UINT32 x = 0; x < kWidth / 2; ++x) {
      const Bar& bar = kBars[((x * 2) / barWidth) & 7];
      const bool inSweep = ((x * 2) + kSweepHalfWidth > sweepX) &&
                           ((x * 2) < sweepX + kSweepHalfWidth);
      row[x * 2] = inSweep ? 128 : bar.u;
      row[x * 2 + 1] = inSweep ? 128 : bar.v;
    }
  }
}

std::wstring FormatHresult(HRESULT hr) {
  wchar_t code[32];
  swprintf_s(code, L"0x%08X", static_cast<unsigned>(hr));

  LPWSTR text = nullptr;
  const DWORD chars = FormatMessageW(
      FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
          FORMAT_MESSAGE_IGNORE_INSERTS,
      nullptr, static_cast<DWORD>(hr), 0, reinterpret_cast<LPWSTR>(&text), 0,
      nullptr);

  std::wstring result = code;
  if (chars > 0 && text != nullptr) {
    std::wstring message(text, chars);
    while (!message.empty() &&
           (message.back() == L'\r' || message.back() == L'\n' ||
            message.back() == L' ')) {
      message.pop_back();
    }
    result += L" (" + message + L")";
  }
  if (text != nullptr) {
    LocalFree(text);
  }
  return result;
}

void ProbeLog(const wchar_t* format, ...) {
  wchar_t buffer[1024];
  wchar_t line[1100];

  va_list args;
  va_start(args, format);
  _vsnwprintf_s(buffer, _TRUNCATE, format, args);
  va_end(args);

  // The frame server hosts this DLL in its own process with no console, so
  // OutputDebugString is the only channel that survives there. The console
  // write is for the host executable.
  _snwprintf_s(line, _TRUNCATE, L"[MeoProbe] %s\n", buffer);
  OutputDebugStringW(line);

  wprintf(L"[MeoProbe] %s\n", buffer);
  fflush(stdout);
}

}  // namespace meo
