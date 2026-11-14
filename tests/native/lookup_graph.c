// One ordinary DT_NEEDED graph, built for Linux, Android x86-64 and ARM64.
// root -> middle,right; middle -> leaf,shared; right -> shared.
#if LOOKUP_ROLE == 1
#include <dlfcn.h>
#include <stdio.h>

static int checks, failures;
static void check(const char* label, int ok) {
  ++checks;
  if (!ok) { ++failures; printf("FAIL lookup graph: %s\n", label); }
}
static void value(void* handle, const char* symbol, int expected) {
  int (*function)(void) = (int (*)(void))dlsym(handle, symbol);
  check(symbol, function != NULL && function() == expected);
}
int lookup_root_priority(void) { return 11; }
int gesher_selftest(void) {
  void* handle = dlopen(LOOKUP_ROOT_SONAME, RTLD_NOW | RTLD_LOCAL);
  check("ordinary root handle", handle != NULL);
  if (handle != NULL) {
    value(handle, "lookup_root_priority", 11);
    value(handle, "lookup_middle_value", 21);
    value(handle, "lookup_deep_value", 31);
    value(handle, "lookup_shared_value", 51);
    value(handle, "lookup_breadth_priority", 41);
    value(handle, "lookup_same_depth_priority", 32);
    dlerror();
    check("missing symbol", dlsym(handle, "lookup_does_not_exist") == NULL);
    check("missing symbol error", dlerror() != NULL);
    check("error consumed once", dlerror() == NULL);
    value(handle, "lookup_deep_value", 31);
    check("close own handle", dlclose(handle) == 0);
  }
  printf("elf_lookup_graph: %d checks, %d failures\n", checks, failures);
  if (failures == 0) puts("elf_lookup_graph all passed");
  return failures;
}
#elif LOOKUP_ROLE == 2
int lookup_middle_value(void) { return 21; }
int lookup_root_priority(void) { return 22; }
#elif LOOKUP_ROLE == 3
int lookup_breadth_priority(void) { return 41; }
#elif LOOKUP_ROLE == 4
int lookup_deep_value(void) { return 31; }
int lookup_breadth_priority(void) { return 33; }
int lookup_same_depth_priority(void) { return 32; }
#elif LOOKUP_ROLE == 5
int lookup_shared_value(void) { return 51; }
int lookup_same_depth_priority(void) { return 52; }
#else
#error Define LOOKUP_ROLE from 1 to 5
#endif
