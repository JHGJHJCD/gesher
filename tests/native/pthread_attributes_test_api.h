// Shared assertions for the real bionic resolver and host-source seam.
#pragma once
#include "../../compat/bionic/abi_types.h"
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <initializer_list>

namespace pthread_attributes_test {
using Attr = gesher::bionic::bionic_pthread_attr_t;
using Thread = gesher::bionic::bionic_pthread_t;
inline int checks = 0;
inline int failures = 0;
inline void Check(bool ok, const char* name) {
  ++checks;
  if (!ok) ++failures;
  std::printf("%s pthread attributes: %s\n", ok ? "PASS" : "FAIL", name);
  std::fflush(stdout);
}
struct Api {
  int (BIONIC_SYSV* init)(Attr*) = nullptr;
  int (BIONIC_SYSV* detach_set)(Attr*, int) = nullptr;
  int (BIONIC_SYSV* detach_get)(const Attr*, int*) = nullptr;
  int (BIONIC_SYSV* scope_set)(Attr*, int) = nullptr;
  int (BIONIC_SYSV* scope_get)(const Attr*, int*) = nullptr;
  int (BIONIC_SYSV* inherit_set)(Attr*, int) = nullptr;
  int (BIONIC_SYSV* inherit_get)(const Attr*, int*) = nullptr;
  int (BIONIC_SYSV* policy_set)(Attr*, int) = nullptr;
  int (BIONIC_SYSV* stack_set)(Attr*, void*, uint64_t) = nullptr;
  int (BIONIC_SYSV* size_set)(Attr*, uint64_t) = nullptr;
  int (BIONIC_SYSV* getattr)(Thread, Attr*) = nullptr;
  Thread (BIONIC_SYSV* self)() = nullptr;
  int (BIONIC_SYSV* create)(Thread*, const Attr*, void* (BIONIC_SYSV*)(void*), void*) = nullptr;
  int (BIONIC_SYSV* join)(Thread, void**) = nullptr;
  int (BIONIC_SYSV* detach)(Thread) = nullptr;
};
inline bool Load(Api& api, void* (*symbol)(const char*)) {
#define LOAD(member, name) \
  api.member = reinterpret_cast<decltype(api.member)>(symbol(name)); \
  if (!api.member) { Check(false, "missing public export " name); return false; }
  LOAD(init, "pthread_attr_init")
  LOAD(detach_set, "pthread_attr_setdetachstate")
  LOAD(detach_get, "pthread_attr_getdetachstate")
  LOAD(scope_set, "pthread_attr_setscope")
  LOAD(scope_get, "pthread_attr_getscope")
  LOAD(inherit_set, "pthread_attr_setinheritsched")
  LOAD(inherit_get, "pthread_attr_getinheritsched")
  LOAD(policy_set, "pthread_attr_setschedpolicy")
  LOAD(stack_set, "pthread_attr_setstack")
  LOAD(size_set, "pthread_attr_setstacksize")
  LOAD(getattr, "pthread_getattr_np")
  LOAD(self, "pthread_self")
  LOAD(create, "pthread_create")
  LOAD(join, "pthread_join")
  LOAD(detach, "pthread_detach")
#undef LOAD
  return true;
}
inline bool Contains(const Attr& attr, uintptr_t pointer) {
  uintptr_t base = reinterpret_cast<uintptr_t>(attr.stack_base);
  return pointer >= base && pointer - base < attr.stack_size;
}
inline void ValueContracts(const Api& api) {
  Attr attr{};
  int value = -1;
  Check(api.init(&attr) == 0, "initialize attributes");
  Check(api.detach_get(&attr, &value) == 0 && value == 0, "default joinable state");
  Check(api.inherit_get(&attr, &value) == 0 && value == 1, "Android default INHERIT_SCHED is one");
  attr.flags |= 0x100;
  Check(api.detach_set(&attr, 1) == 0 && api.detach_get(&attr, &value) == 0 && value == 1 &&
            (attr.flags & 0x100), "detached state preserves other flags");
  Check(api.detach_set(&attr, 0) == 0 && api.detach_get(&attr, &value) == 0 && value == 0 &&
            (attr.flags & 0x100), "joinable state preserves other flags");
  for (int invalid : {-1, 2, INT_MAX}) {
    Attr before = attr;
    Check(api.detach_set(&attr, invalid) == 22 && std::memcmp(&attr, &before, sizeof attr) == 0,
          "invalid detach value preserves attributes");
    Check(api.inherit_set(&attr, invalid) == 22 && std::memcmp(&attr, &before, sizeof attr) == 0,
          "invalid inheritance value preserves attributes");
  }
  Check(api.scope_set(&attr, 0) == 0 && api.scope_get(&attr, &value) == 0 && value == 0,
        "system scheduling scope");
  Check(api.scope_set(&attr, 1) == 95, "process scheduling scope reports ENOTSUP");
  Check(api.scope_set(&attr, -1) == 22 && api.scope_set(&attr, 2) == 22,
        "unknown scheduling scope reports EINVAL");
  api.init(&attr);
  Check(api.policy_set(&attr, 1) == 0 && api.inherit_get(&attr, &value) == 0 && value == 0,
        "historical nondefault policy selects EXPLICIT_SCHED");
  Check(api.inherit_set(&attr, 1) == 0 && api.inherit_get(&attr, &value) == 0 && value == 1,
        "explicit inheritance overrides nondefault policy");
  Check(api.inherit_set(&attr, 0) == 0 && api.policy_set(&attr, 0) == 0 &&
            api.inherit_get(&attr, &value) == 0 && value == 0,
        "explicit scheduling remains selected after policy change");
  Check(api.inherit_set(&attr, 1) == 0 && (attr.flags & 4) && !(attr.flags & 8),
        "inheritance clears the prior explicit flag");
  Check(api.inherit_set(&attr, 0) == 0 && (attr.flags & 8) && !(attr.flags & 4),
        "explicit scheduling clears the prior inherit flag");
  attr.flags |= 0x101;
  Check(api.inherit_set(&attr, 1) == 0 && (attr.flags & 0x101) == 0x101,
        "inheritance preserves detached and unrelated flags");
  for (uint64_t size : {uint64_t{0}, uint64_t{16383}, uint64_t{16385}, UINT64_MAX}) {
    Attr before = attr;
    Check(api.stack_set(&attr, reinterpret_cast<void*>(0x100000), size) == 22 &&
              std::memcmp(&attr, &before, sizeof attr) == 0,
          "invalid supplied stack size preserves attributes");
  }
  Attr before = attr;
  Check(api.stack_set(&attr, reinterpret_cast<void*>(0x100001), 16384) == 22 &&
            std::memcmp(&attr, &before, sizeof attr) == 0,
        "misaligned supplied stack base preserves attributes");
  Check(api.stack_set(&attr, reinterpret_cast<void*>(0x100000), 16384) == 0 &&
            attr.stack_base == reinterpret_cast<void*>(0x100000) && attr.stack_size == 16384,
        "aligned minimum supplied stack attribute roundtrip");
  Check(api.stack_set(&attr, nullptr, 16384) == 0 && attr.stack_base == nullptr,
        "null stack base is page aligned");
  Check(api.size_set(&attr, 16385) == 0 && attr.stack_size == 16385,
        "setstacksize does not impose setstack alignment");
  before = attr;
  Check(api.size_set(&attr, 16383) == 22 && std::memcmp(&attr, &before, sizeof attr) == 0,
        "short stacksize preserves attributes");
}
}  // namespace pthread_attributes_test
