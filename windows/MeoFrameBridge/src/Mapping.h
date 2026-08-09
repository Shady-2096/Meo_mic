// The one piece of the frame bridge that is platform code.
//
// Everything above this interface — the seqlock, the ring, the validation, the
// watchdogs — is portable, which is what lets the risky concurrency logic be
// tested on a Mac (ADR 0006) and what leaves the door open for the macOS
// backend if ADR 0004 ever unblocks.

#pragma once

#include <cstddef>

namespace meo::detail {

class Mapping {
 public:
  Mapping();
  ~Mapping();

  Mapping(const Mapping&) = delete;
  Mapping& operator=(const Mapping&) = delete;

  // Creates the section if absent, or opens and reuses it if a previous host
  // left one behind. `bytes` is the full mapping size.
  bool Create(const char* name, size_t bytes);

  // Opens an existing section. Production readers only read it; tests also
  // use this primitive to inject hostile bytes into the mapping.
  bool Open(const char* name, size_t bytes);

  void Close();

  void* data() const { return data_; }
  size_t size() const { return size_; }
  bool valid() const { return data_ != nullptr; }

  // True when Create found no existing section and therefore handed back
  // zeroed memory. The writer uses this to decide whether it is initialising a
  // fresh header or adopting one an earlier run left mapped.
  bool created_fresh() const { return created_fresh_; }

  // The default section name. Deliberately defined in the platform files
  // rather than the header, because ADR 0006's open sub-question is precisely
  // which namespace this belongs in, and the answer must be changeable in one
  // place.
  static const char* DefaultName();

 private:
  void* data_ = nullptr;
  size_t size_ = 0;
  bool created_fresh_ = false;

#if defined(_WIN32)
  void* handle_ = nullptr;
#else
  int fd_ = -1;
  bool owner_ = false;
  char shm_name_[64] = {};
#endif
};

}  // namespace meo::detail
