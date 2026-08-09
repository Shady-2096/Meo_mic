#if defined(_WIN32)

#include "Mapping.h"

#include <windows.h>

#include <array>

namespace meo::detail {
namespace {

// ADR 0006's open sub-question lives here, and nowhere else.
//
// `Local\` scopes the section to the current terminal-services session, which
// is correct *if* the Windows frame server activates Meo's media source inside
// the user's own session. ADR 0003 already flags that the frame server is a
// separate service running under a different account, so that is an
// assumption, not a fact.
//
// If the probe shows the source is activated in session 0 instead, this has to
// become `Global\` plus an explicit DACL granting the frame server read
// access — and creating a Global section needs SeCreateGlobalPrivilege, which
// a standard user does not hold. That would move a UAC prompt out of install
// and into the runtime path, which is materially worse than ADR 0003's worst
// case and needs to be known before the installer is designed.
//
// Do not change this to Global speculatively. Measure it first: the probe in
// probes/windows-virtual-camera already loads a Meo DLL inside the frame
// server, so logging GetCurrentProcessId and ProcessIdToSessionId from its
// DllMain answers it in the same run that answers ADR 0002 and 0003.
constexpr char kDefaultName[] = "Local\\MeoCamera.FrameBridge.v1";

bool Widen(const char* utf8, wchar_t* wide, int capacity) {
  if (utf8 == nullptr || *utf8 == '\0' || wide == nullptr || capacity <= 0) {
    return false;
  }
  return MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8, -1, wide,
                             capacity) > 0;
}

}  // namespace

const char* Mapping::DefaultName() { return kDefaultName; }

Mapping::Mapping() = default;
Mapping::~Mapping() { Close(); }

bool Mapping::Create(const char* name, size_t bytes) {
  Close();

  std::array<wchar_t, 260> wide{};
  if (!Widen(name != nullptr ? name : kDefaultName, wide.data(),
             static_cast<int>(wide.size()))) return false;

  // The default DACL already grants the creating user full access, which is
  // what a same-session frame server needs. Nothing broader is requested,
  // because §6.4's defence-in-depth posture says not to widen access to
  // decoded camera frames beyond the user who owns them.
  const HANDLE handle = CreateFileMappingW(
      INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE,
      static_cast<DWORD>(static_cast<uint64_t>(bytes) >> 32),
      static_cast<DWORD>(bytes & 0xFFFFFFFFu), wide.data());
  if (handle == nullptr) return false;

  // A section left behind by a previous host run is adopted rather than
  // rejected. The writer re-initialises the header either way; this flag only
  // tells it whether the bytes it received were guaranteed zeroed.
  const bool existed = GetLastError() == ERROR_ALREADY_EXISTS;

  void* view = MapViewOfFile(handle, FILE_MAP_ALL_ACCESS, 0, 0, bytes);
  if (view == nullptr) {
    CloseHandle(handle);
    return false;
  }

  handle_ = handle;
  data_ = view;
  size_ = bytes;
  created_fresh_ = !existed;
  return true;
}

bool Mapping::Open(const char* name, size_t bytes) {
  Close();

  std::array<wchar_t, 260> wide{};
  if (!Widen(name != nullptr ? name : kDefaultName, wide.data(),
             static_cast<int>(wide.size()))) return false;

  const HANDLE handle =
      OpenFileMappingW(FILE_MAP_ALL_ACCESS, FALSE, wide.data());
  if (handle == nullptr) {
    // The ordinary case when the host has not started yet. The caller turns
    // this into a "Meo isn't running" slate, not an error dialog.
    return false;
  }

  void* view = MapViewOfFile(handle, FILE_MAP_ALL_ACCESS, 0, 0, bytes);
  if (view == nullptr) {
    CloseHandle(handle);
    return false;
  }

  handle_ = handle;
  data_ = view;
  size_ = bytes;
  created_fresh_ = false;
  return true;
}

void Mapping::Close() {
  if (data_ != nullptr) {
    UnmapViewOfFile(data_);
    data_ = nullptr;
  }
  if (handle_ != nullptr) {
    CloseHandle(static_cast<HANDLE>(handle_));
    handle_ = nullptr;
  }
  size_ = 0;
  created_fresh_ = false;
}

}  // namespace meo::detail

#endif  // _WIN32
