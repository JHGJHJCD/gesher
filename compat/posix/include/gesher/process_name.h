// gesher: the process name Android's zygote gives an app process
// (android.os.Process.setArgV0, compat/posix/src/procfs.cc).
//
// On a device the app process's argv[0] is overwritten with its process name
// (the package name, or package:name), so /proc/self/cmdline reads
// "com.example.app\0". Libraries decide whether they run in the app's main
// process by comparing it with the package name (blankj's
// ProcessUtils.isMainProcess, many SDKs' "init only in the main process"), and
// an app whose Application.onCreate returns early outside its main process
// stays uninitialized. Without this, /proc/self/cmdline is dalvikvm.exe's
// Windows command line.
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

// Sets the name /proc/self/cmdline and /proc/self/comm report from now on.
void gesher_set_process_argv0(const char* name);

#ifdef __cplusplus
}
#endif
