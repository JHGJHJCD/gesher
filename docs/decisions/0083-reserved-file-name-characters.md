# 0083 — File names with characters Windows reserves

**Status:** accepted, 2026-11-14 (posix track)

Extends [0010](0010-paths.md) (paths) and [0045](0045-posix-paths-tz-procfs.md)
(the path calls). Code: `compat/posix/include/gesher/reserved_names.h`.

## Context
A Linux file name may hold any byte but `/` and NUL. Windows reserves
`< > : " | ? *` and the control characters 1–31. Android apps use some of these
characters in names they create themselves:

- The Firebase SDK (Remote Config, Performance) keeps
  `shared_prefs/frc_<n>:<project number>:android:<app id>_<namespace>_settings.xml`.
  Waze, and every app with Firebase, tries to create it on each start.
  `SharedPreferencesImpl` logs "Couldn't create directory for SharedPreferences
  file" each time, and the settings are never saved.
- Play Store keeps a directory per process, named `<package>:<process>`:
  "[Counters] failed to create relevant process or counters directory".

On NTFS a `:` in a name selects an alternate data stream. Two colons
(`name:stream:type`) fail with an invalid-name error. One colon (`a:b`) quietly
succeeds: it creates or opens the stream `b` of a file `a`, which `readdir` never
lists under the name that was given. Before this change, gesher-base passed
names to Windows as they were.

## Decision
gesher-base stores each reserved character `c` of a name as the Unicode
private-use character U+F000 + `c`. Cygwin uses the same mapping, so NTFS
volumes shared with Cygwin tools read the same names. Every name that leaves
the disk for Linux code turns back:

| Direction | Where |
|---|---|
| Linux to Windows (encode) | `MapPath` (every path call, including configured/install font roots), a relative `symlink` target, the bionic shim's `unlink`/`rmdir`, the exec tools' `WindowsPath`, Java's `Zygote.windowsPath` |
| Windows to Linux (decode) | `LinuxPath` (`getcwd`, `realpath`, `readlink` of absolute targets and of `/proc/self/fd`), `readdir`, `readlink` of relative targets, inotify event names, the exec shell's globbing and `ls`, Java's `Zygote.fromWindowsPath` |

The Windows root of a path keeps its characters in both directions. Native
paths decode only below that root, before conversion to the `/mnt/unc`
namespace, so private-use characters in a server or share remain literal.
That means a drive `C:`, the
`\\?\` and `\\.\` prefixes, and a UNC server and share, so the Windows paths
that gesher-base also accepts (ADR 0010) mean what they meant. A drive is a
letter and a colon before a separator or the end. `x:cfg` is a Linux name in
the current directory, also for the `*at` calls, not a path relative to drive
X's current directory. Only the
reserved characters move: `/` and `\` still separate names. `.` and `..` are
unchanged, and so are a trailing dot or space and the device names (`CON`,
`NUL`). Those are still Windows' to interpret.

## Consequences
- Firebase's settings files and Play Store's per-process directories are
  created, listed, renamed and removed under the names the app gave.
- A literal U+F000+`c` in an ordinary name, supplied by either a Linux caller
  or Windows software, aliases the corresponding reserved character. This
  mapping cannot represent those two names separately. Windows root
  components are preserved and do not undergo that decoding.
- Gesher's Java code that builds Windows paths itself goes through
  `Zygote.windowsPath` (icons, APKs, logs, caches, and a WebView file upload of an
  app's file), which encodes the names under the root the same way, so an
  uploaded `a:b.txt` is the file the app wrote. A file picker's Windows path
  comes back through `Zygote.fromWindowsPath`, which decodes.
- Windows tools such as Explorer, a backup tool or `dir` show the private-use
  characters, usually as boxes. The data directory is the app's, not the user's.
- Data written before this change: a two-colon name never reached the disk. A
  one-colon name `a:b` was written as a stream of `a`. The app now looks for
  the encoded name, does not find it, and starts that file afresh. The old
  stream stays in `a` until `a` is removed.
- A trailing dot or space is still dropped by Windows, so `x.` and `x` name one
  file. Encoding them would change the names of files that already exist, so
  that is left for a later decision.

## Tests
`tests/paths/reserved_names_test.cc` (CI, portable): every reserved character
and only those, round trips, the Windows roots, idempotence, and private-use
UNC server/share names combined with encoded leaf names.
`probes/posix/posix_probe.cc` "ReservedNames" (Windows): Firebase's and Play
Store's names through `mkdir`, `fopen`, `stat`, `readdir`, `rename`, `O_EXCL`,
`symlink`/`readlink`, `chdir`/`getcwd` and inotify. It also checks that the
disk holds the encoded name and no data stream. Dirfd-relative single-letter
colon names exercise `openat`, `fstatat`, `renameat`, `mkdirat`, `symlinkat`,
`readlinkat` and `unlinkat`. A configured font root under an encoded directory
must work through both `/system/etc/fonts.xml` and `/system/fonts`.

The mandatory exec shell smoke suite checks quoted literal `?` and `*` next
to an active wildcard, a quoted directory component before an active child
pattern, and a wildcard matching a filename that itself contains `*`.

`tests/dex-services/gesher/services/ZygotePathTest.java`: the Java conversion,
the roots kept, and round trips through `fromWindowsPath`.
