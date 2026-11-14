> Archived baseline mapping evidence. Findings describe pre-fix source at `e0dbabf8`. See [the integrated audit](../android-windows-compatibility-2026-11-14.md) for resolutions and current tests. Scratch paths identify investigation inputs, not published runtime evidence.

# Gesher POSIX/bionic and Windows translation audit

Date: 2026-11-14. Baseline: `e0dbabf8932df2c101d8ed3d28a8c560df771dc0`.

This is the read-only mapping phase. This agent changed no repository source, GitHub state, or branch. The inspected native surface comprises 30,393 lines in the 52 POSIX implementation files and the principal bionic libc adapters counted below. This is a subsystem and contract audit, not a proof of universal Android compatibility: most Windows implementations cannot execute on this Linux worker. A symbol being registered is not evidence that its semantics are implemented.

## Outcome and evidence strength

The highest-impact actionable gap is the conflation of `MADV_DONTNEED`, `MADV_FREE`, and `MADV_REMOVE`: shared-file `DONTNEED` currently writes zeros into the backing file. ADR0080 already acknowledges that narrow limitation; this audit extends it into a verified mapping/advice/error matrix, identifies file-copy provenance hazards that make a simplistic fix unsafe, and supplies a production-source negative control plus a Linux oracle. Pipe nonblocking and readiness contracts are another important family, but they require a broader descriptor/ownership change.

Evidence labels used here:

- **Executed**: local actual Linux contract or actual production C++ extracted into the existing controlled Win32 seam.
- **Source verified**: the relevant dispatch and implementation establish the mismatch; native Windows execution is still needed.
- **Known boundary**: already explicitly documented before this audit; not claimed as a new discovery.
- **Needs qualification**: plausible concern with missing integration evidence, not an established bug.

Scratch evidence:

- `madvise-linux-oracle.py`, `madvise-linux-oracle.json`: 20 independent Linux cases, kernel 6.18.57, 4 KiB pages, owned temporary mappings/files only.
- `madvise-production-baseline.cc`, `madvise-production-baseline.log`: production `madvise`, `mprotect`, range walker and zeroing tracker copied verbatim into the repository's Win32 seam. 21 assertions; five expected failures at baseline: shared-file data preservation, file `MADV_FREE` rejection and preservation, unknown advice rejection, unaligned zero-length rejection. Existing two zeroing concurrency cases remain in the fixture and pass. This is not a native Windows runtime result.

## Architectural map

The host `compat/posix` library is compiled into native Windows runtime components. The `compat/bionic` table exposes System V ABI entry points to Android ELF libraries, forwards many calls to the host layer, and separately converts LP64 types that differ from Windows LLP64. Some guest wrappers duplicate host logic instead of forwarding; those duplicate paths are a recurring source of semantic drift. Windows handle ownership, CRT descriptor ownership, and synthetic event/socket descriptions form separate layers and must remain coordinated through `record_lock_descriptors` and fd registry operations.

| Family and source inventory | Present implementation | Remaining contract boundaries / relevant tests |
|---|---|---|
| Paths and file identity: `path.cc`, `fsops.cc`, `links.cc`, `stat.cc`, `dirent.cc`, `ftw.cc`; guest `posix_bionic.cc` | UTF-8/UTF-16 mapping; Win32 file access, *at helpers, links, stat metadata, directory traversal; Android-specific ftw behavior | Partial. NT path/permission differences, duplicate guest *at logic, directory-name and lazy-dirfd concerns below. Prior path-reserved-name PR24 and identity PR25 excluded. Tests: directory, rename, timestamps, positioned-I/O, sendfile, statvfs contracts; real `posix_probe`. |
| Descriptors and scalar/vector I/O: `fd.cc`, `unistd.cc`, `system.cc`, `internal.cc`; guest `shim.cc`, `posix_bionic.cc` | CRT files, synthetic device descriptions, duplicated sockets/events, `readv`/`writev`, positioned I/O, sendfile | Partial. Generic `F_GETFL`/`F_SETFL`, pipe flags/readiness and seals are not faithful. Previous vector dispatch and select fixes already present. Tests: host event-loop, sendfile, positioned-I/O, `posix_probe`/`socket_probe`. |
| Memory: `mman.cc`, `mman_alias_prepare.cc`, `mman_registry_internal.h`; guest mmap and `extra_sys_bionic.cc` wrappers | Private allocation, placeholder-backed fixed replacement, shared/private sections, COW protections, file-copy fallback/tails, anonymous remap, bounded shared alias admission, zeroing fault retry | Partial. Advice corruption, admission/overflow gaps, missing residency/lock parity; `mremap` file resizing remains bounded. PR26 accounting excluded. Tests: `mman_probe`, zeroing seam, file-mremap component/union fixtures. |
| Allocation: `memalign.cc`; guest `shim.cc`, `extra_bionic.cc` | Aligned allocation registry preserving free/realloc routes, Linux-width allocation APIs, FORTIFY | Implemented core, partial OOM domain. Standard containers/throwing `new` in many registry paths under no-exceptions remain unqualified; not all failures translate to ENOMEM. Allocation contract fixtures already cover selected APIs. |
| Polling and event objects: `fd.cc`, `epoll.cc`, `select.cc`, `event_internal.h` | eventfd counter/readiness, nested epoll, one-shot rearming, fair cursor, socket/native polling with wake channels, select/pselect conversion | Partial. Eventfd copy/error behavior, ET/exclusive semantics and alias identity not fully Linux. Historical fairness/vector fixes excluded. Tests: `epoll_probe`, host event-loop, guest wait contracts. |
| Timers and clock/sleep: `timerfd.cc`, `sleep.cc`, guest `time_bionic.cc`, `shim.cc`, `extra_sys_bionic.cc` | Owned timerfd descriptions; joined callbacks; elapsed-expiration counting; UTC conversion; monotonic/realtime clock sources; wide clock_nanosleep loop | Partial. Separate ordinary nanosleep arithmetic, clock_getres validation, suspend/realtime-change qualifications, guest residency/locking stubs. Tests: timerfd, bionic wait/system/semaphore fixtures. |
| Threads, locks, TLS: `pthread.cc`, `thread_register.cc`; guest `pthread_bionic.cc`, `pthread_cleanup.h`, `extra_sys_bionic.cc` | Winpthreads and WaitOnAddress; Android object layouts; keys/generations, cancellation cleanup, mutex/cond/rwlock/spin/barrier/semaphore APIs | Partial. Process-shared synchronization, invalid clock IDs in selected wait adapters, scheduler policy/affinity, host target CPU clock. TLS generation repairs already present. Tests: key, wait, semaphore contracts and guest suites. |
| Futex/syscalls: `syscall.cc`; guest `shim.cc` and `time_bionic.cc` dispatch | Address-owned wait queues, bitsets, bounded wakes/requeue, selected syscall routing and memfd | Implemented selected operations. PI/robust-owner-death/WAKE_OP, cross-process and different-address shared-file aliases, Linux signal interruption remain known gaps. Current implementation supersedes the older 11/13 lifecycle report's bitset/wake claim. Test: `test_futex_contracts.py`, native common fixture. |
| Signals and faults: `signal.cc`, `fault_signals.cc`, `crash.cc`, `process.cc`; guest `signal_bionic.cc`, jump/signal adapters | Host pending-signal mailbox for sigwait; Windows fault routing to ART; separate guest mask/delivery system; setjmp mask restoration | Partial. Host ordinary handlers are not asynchronously delivered; guest signal/altstack/synchronous delivery boundaries remain. `tgkill` adapter below ignores tgid and only addresses current thread. Full Linux signal equivalence is not established. |
| Process/environment/resources: `process.cc`, `users.cc`, `system.cc`, `misc.cc`; guest `process_bionic.cc`, `extra_sys_bionic.cc` | Windows process launch/wait/exec glue, environment handling, thread priority mapping, UID model, resource-limit storage | Partial. `fork` ENOSYS, limits largely unenforced, groups/sessions differ, RUSAGE_CHILDREN zeros. Do not repeat stale docs saying all native exec is absent. Identity PR25 excluded. Tests: spawn/wait/system contracts, process app tests. |
| Record locks: six `record_lock_*.cc` files and associated headers | Range normalization, file/owner identity, process vs open-description lock ownership, nonblocking registry operations, bounded internal wait transaction | Production `F_SETLKW`/`F_OFD_SETLKW` still not wired. Existing ADR80/union test infrastructure must not be mistaken for completed public dispatch. Known gap. Tests in `tests/native3-locks`, helper/union workflows. |
| Sockets and network configuration: `socket.cc`, `winsock.cc`, `linux_socket.h`, socket ownership/copy helpers | IPv4/IPv6/Unix path sockets, TCP/UDP, ownership for provider I/O/cancellation, socketpair, nonblocking descriptions, selected options/multicast/interface bindings | Partial. Ancillary transfer explicitly unsupported; timeout width/short-result issues below; some Linux options absent. Current ConnectEx/datagram ownership work is substantial and not replaced by generic wrappers. Tests: socket-control, socket_probe, network lifecycle fixtures. |
| Name service: `netdb.cc`, `network_dns.cc`; guest `socket_bionic.cc`, `dns_wire.cc` | Linux/Windows address types and BSD bionic AI/EAI translations, UTF conversion, interfaces, deep-owned legacy results, cancellable raw DNS query completion | Partial. Legacy reentrant/database enumeration APIs not complete, unknown getnameinfo flags ignored on host, resolver policy/full Android netd behavior differs. Prior DNS alias/result ownership repairs present. |
| File watching: `inotify.cc` | IOCP/ReadDirectoryChangesExW worker, eventfd readiness, inode-based duplicate-watch recognition, close-write approximation, queue/oneshot handling | Partial. Known absent open/access/close-nowrite and delayed cross-process modify; rename handling below retires watched files instead of following inode. Tests largely `posix_probe`; not complete all-mask/event-sequence qualification. |
| Binder: `binder.cc`, `binder_internal.h`, `binder_sg.h`, `binder_wire.h` | Broker protocol over named pipes, process/thread connections, transaction allocation/copying and fd translation, IOCTL forwarding | Substantial implementation, partial Linux driver parity. Three scheduler/spam IOCTLs accepted with no action; extended-error always BR_OK. Correctness of concurrent transaction/fd lifetimes depends on broker-side code outside this assignment. |
| libc text/stdio/locale: `fnmatch.cc`; guest `stdio_bionic.cc`, `locale_bionic.cc`, `unicode_bionic.cc`, `unicode_width.cc`, `search_bionic.cc`, `zlib_bionic.cc` | Format ABI conversion, streams, locale/UTF encoding, search trees/hash, zlib structure wrappers | Partial. CRT and Android locale/time ranges differ; open_memstream/fmemopen unsupported in current guest code; unusual widths/large data/OOM deserve qualification. Existing format, Unicode, stream, allocation tests are family-specific, not complete libc certification. |
| System facade: `procfs.cc`, `system_properties.cc`, `sysprop_callbacks.cc`, `base_properties.cc`, `libcap.cc`, `sysv_shims.cc`, `errno.cc`, logs, font/timezone helpers | Own-process virtual procfs; properties; capability-empty model; Win32→Linux errno; Android logging; installed fonts and CLDR zone mapping | Deliberately partial kernel/system model. Other-process procfs, capabilities, mounts/xattrs, live timezone propagation differ. Memory accounting PR26 not touched. Property/system/timezone tests and docs already identify boundaries. |

## Ranked findings

### P0: mapped-file advice writes data instead of discarding mappings

**Known narrow boundary; newly executed extended contract evidence.** `compat/posix/src/mman.cc:1253–1291` routes DONTNEED, FREE and REMOVE into one operation. Its `MEM_MAPPED` branch calls `memset` on writable pages. With MAP_SHARED that is a write to the shared file contents. With private COW it produces zero data instead of restoring the current backing-file page. With `MADV_FREE` it accepts mapping classes Linux rejects. The default branch accepts every unknown number. The controlled production test observes these actual code paths; the actual Linux oracle observes the expected semantics.

This is not only a performance discrepancy. Mapped application databases, caches, resource images and libraries can depend on preserved file content; returning success after changing it produces incorrect reads or later parsing failure. Native Windows regression is still needed to confirm the complete descriptor→section→mapping path.

`docs/decisions/0080-memory-footprint.md:110` expressly acknowledges writable-file DONTNEED zeroing. Its existing tests focus private-anonymous allocator behavior and concurrent protection. Keep those guarantees intact while fixing this broader family.

#### Linux oracle results

Here `B` is the original file byte and `W` is a caller's write before advice. Each case owns a fresh mapping and file.

| Mapping | DONTNEED | FREE | REMOVE |
|---|---|---|---|
| Private anonymous | Success, W→zero | Success, may retain W until reclaim; immediate zero is also allowed | EINVAL, W preserved |
| Shared anonymous | Success, W retained | EINVAL, W preserved | Success, W→zero on local Linux tmpfs |
| Private file | Success, W→B; backing stays B | EINVAL, W preserved | EACCES, W preserved |
| Shared file | Success, W retained in mapping/file | EINVAL, W preserved | Success, mapping/file→zero through hole removal |

Additional observations: DONTNEED length 1 covers the full page. Address 1 with length 0 returns EINVAL; aligned address 0 with length 0 returns success for a valid advice; unknown advice with length 0 is EINVAL; a positive length over unmapped address 0 is ENOMEM; length `SIZE_MAX` is EINVAL. A shared PROT_READ mapping created with an O_RDWR file still permits REMOVE on Linux: the kernel's write capability is not identical to current page protection. Do not derive that permission solely from `VirtualQuery.Protect`.

Primary contract: [Linux madvise(2)](https://man7.org/linux/man-pages/man2/madvise.2.html). Primary Windows alternatives: [DiscardVirtualMemory](https://learn.microsoft.com/en-us/windows/win32/api/memoryapi/nf-memoryapi-discardvirtualmemory) and [VirtualUnlock](https://learn.microsoft.com/en-us/windows/win32/api/memoryapi/nf-memoryapi-virtualunlock).

#### Bounded Windows repair recommendation

1. Add real mapping provenance to `Region`: private anonymous, shared anonymous, real file view, copied file content. `Kind::kAnon` is physical storage, not Linux mapping origin. `GroupFile` at roughly 621–778 stores its partial final page or entire execution fallback in anonymous memory; `CopyFileInto` at 840 also loses file origin. Splits, fixed replacement, remap, and destruction must preserve/reset provenance consistently. `MAP_SHARED|MAP_ANONYMOUS` currently becomes a private VirtualAlloc and must not be treated like private anonymous during advice.
2. Validate alignment and checked rounded end before touching memory; preflight the entire mapping/provenance/protection range under `State::lock`. A rejected mixed range should not already have had its first pages zeroed. Distinguish live PROT_NONE reserved anonymous pages from holes/unmapped pages.
3. Keep correct private-anonymous DONTNEED zeroing via the existing decommit/recommit tracker. FREE can use eager discard for private anonymous, which is allowed; reject file/shared mappings with EINVAL before mutation. This must not include copied file tails.
4. Shared-file DONTNEED can potentially use `VirtualUnlock` for actual working-set eviction while preserving the shared content. Microsoft documents that unlocked pages are evicted and ERROR_NOT_LOCKED is returned; that specific return is not a generic failure. However, Linux rejects locked DONTNEED and this implementation currently has no mapped-page lock ledger. Qualify locked/mixed/protection/alias cases first, e.g. with `QueryWorkingSetEx` plus serialized host mlock, before accepting the operation. If reliable eviction/admission cannot be implemented in this bounded change, return a documented EOPNOTSUPP without mutation. This is reduced compatibility but avoids false success/data corruption.
5. Private-file DONTNEED needs the original section relationship restored, not merely copying file bytes over COW pages. Copying eagerly dirties the private page again and fails a later backing-file-update observation before the next private write. A correct per-range remap must preserve unadvised dirty pages, protections, concurrent access, allocation failures and rollback; the existing `PunchView` is destructive and ignores several remap errors. A documented EOPNOTSUPP preserving data is an honest bounded boundary until this larger transaction is implemented. Read-only private views also need provenance/copy-state reasoning, not a blanket no-op.
6. REMOVE requires actual shared backing-range deallocation. The retained section handle is not a retained file handle for FSCTL hole-punch operations. Private anonymous should give EINVAL; private file gives EACCES; otherwise eligible but unsupported shared backing should give EOPNOTSUPP. Do not replace REMOVE by a plain memset or DONTNEED. Shared anonymous backing requires a real shared section implementation, separately.
7. NORMAL/RANDOM/SEQUENTIAL may be explicit validated advisory hints (no content guarantee requires a fake action). WILLNEED should propagate failed prefetch with appropriate error rather than unconditional success. Unknown advice gives EINVAL. Audit each other recognized advice instead of retaining a default-success arm. `MADV_DONTFORK` is actively used by ART via `patches/art/0017-jit-dual-view-code-cache-on-windows.patch`, so an indiscriminate rejection change can regress startup; with fork unsupported its behavior needs an explicit documented policy.
8. Do not use DiscardVirtualMemory as a zero-fill/COW reset substitute: its documented restored contents are undefined and it only accepts PAGE_READWRITE. Likewise VirtualUnlock preserves private COW contents, so it is not sufficient for private-file DONTNEED.

Suggested exact regression gate: one common C contract compiled against Linux, production host gesher-base on Windows, and guest bionic on x86-64/ARM64. Cover file descriptor closed/reused after mmap; independent shared alias; private dirty page and unchanged neighbor; nonzero file offset; partial final page/copy fallback; PROT_NONE/read-only/executable; partial range/page rounding; invalid advice/alignment/overflow/hole; rejected operation leaves file and all mappings unchanged; retained existing concurrency cases. Unsupported Windows branches must be explicitly distinguished from Linux success in the test result, not hidden by accepting either zero or minus one.

### P1: pipe flags and readiness contradict the descriptor contract

**Source verified.** `unistd.cc:289` implements pipe2 with CRT `_pipe`, using only O_CLOEXEC. It ignores O_NONBLOCK and unknown flags. `system.cc:451–454` reports O_RDWR for generic files/pipes and returns success for F_SETFL without changing behavior. `fd.cc:388–411` calls PeekNamedPipe on both pipe ends and treats every failure as peer closure, reporting HUP and requested IN/OUT. A normal write endpoint lacks GENERIC_READ, so PeekNamedPipe failure is not evidence of hangup. Full-pipe output is always reported ready. Event loops using a self-pipe/nonblocking wake channel can therefore block or see false hangup despite successful setup.

Primary references: [pipe(2)](https://man7.org/linux/man-pages/man2/pipe.2.html), [PeekNamedPipe](https://learn.microsoft.com/en-us/windows/win32/api/namedpipeapi/nf-namedpipeapi-peeknamedpipe), [anonymous pipe access rights](https://learn.microsoft.com/en-us/windows/win32/ipc/anonymous-pipe-security-and-access-rights), [named pipe open modes](https://learn.microsoft.com/en-us/windows/win32/ipc/named-pipe-open-modes), [SetNamedPipeHandleState](https://learn.microsoft.com/en-us/windows/win32/api/namedpipeapi/nf-namedpipeapi-setnamedpipehandlestate).

Feasibility: validating unknown flags is small. Correct O_NONBLOCK is not just a flag check: anonymous `_pipe` read handles do not have FILE_WRITE_ATTRIBUTES needed to change pipe state; availability-then-blocking-read has a race. Prefer an owned named-pipe description or another explicitly owned pipe backend with atomic read/write/nonblocking behavior, shared flags across dup, PIPE_BUF guarantees, close/inheritance coordination and quota/direction-aware polling. NtQueryInformationFile pipe-local information may support readiness, but needs an explicit implementation/qualification decision. Do not label an EINVAL-on-O_NONBLOCK patch as implemented nonblocking support.

Regression design: empty nonblocking read EAGAIN, no HUP on a live writer, no OUT for a full writer, EOF after final writer closes, readable residual bytes plus HUP, dup flag sharing, CLOEXEC inheritance, invalid flags, concurrent readers, writes <=PIPE_BUF with competing writers, bounded tests that cannot hang CI.

### P1: public memory admission and overflow validation is incomplete

**Source verified.** `mman.cc:1102` rejects every unaligned address hint even without FIXED; accepts nonanonymous fd -1 by treating it as anonymous; does not validate mapping type; uses unchecked RoundUp. `MapFile` explicitly accepts unaligned file offsets through its plain-view path even though Linux mmap requires page alignment. `munmap:1130` computes begin+rounded length unchecked. `mprotect:1207` lacks start alignment/protection-bit/overflow validation and returns early for length zero before alignment validation. `ForEachRun:963` computes pointer end unchecked. These can yield false success, wrong errno, wrong mapping class, or no operation on an overflowed range.

Primary contract: [mmap(2)](https://man7.org/linux/man-pages/man2/mmap.2.html), [mprotect(2)](https://man7.org/linux/man-pages/man2/mprotect.2.html). This is a feasible separate bounded fix using explicit admission helpers plus Linux oracle. Check ART's internal MapAnonymous calls separately from public mmap: internal helpers intentionally use flags 0 in some paths. Do not tighten those private helpers as if they are public admission.

### P1/P2: guest *at wrappers bypass stronger host implementations

**Source verified; Windows regression needed.** `posix_bionic.cc:311` Fstatat zeroes the output before validation and discards flags other than its few special cases. Guest Faccessat at 379 discards all flags, Fchownat at 393 discards flags, Linkat at 445 discards AT_SYMLINK_FOLLOW, Unlinkat at 365 ignores unknown flags. Own AtPath does not check that dirfd refers to a directory and accepts the empty relative string as the directory handle's path. Host fsops has stronger flags/path admission for several equivalents. `fstatat(AT_FDCWD,"",...,AT_EMPTY_PATH)` also reaches fstat(-100) instead of current-directory behavior. `lchown` ultimately follows the target, which mishandles dangling links.

Primary references: [fstatat](https://man7.org/linux/man-pages/man2/stat.2.html), [linkat](https://man7.org/linux/man-pages/man2/link.2.html), [faccessat](https://man7.org/linux/man-pages/man2/access.2.html), [unlinkat](https://man7.org/linux/man-pages/man2/unlink.2.html). A useful future slice is forwarding guest wrappers to the existing host Linux-layout ABI where compatible, then shared host/guest tests for flags, empty path, regular-file dirfd, symlink-follow/nofollow and output preservation. Keep PR24/25 ownership independent.

### P2: ordinary sleep and clock validation are weaker than newer wide helpers

**Source verified.** `shim.cc:643` multiplies signed 64-bit seconds by 1000 without checked widening, clamps to one 0x7ffffffe-ms wait and then reports completed sleep even for longer requests. `Usleep:658` adds 999 in uint32_t (wrap at high inputs); SleepSeconds at 665 clamps rather than representing the complete duration/remainder. `ClockGetres:613` accepts every nonnegative clock ID. `extra_sys_bionic.cc:125,277,280,286` routes unsupported sem/rwlock/mutex clock IDs to monotonic instead of EINVAL. The separate ClockNanosleep implementation already uses wide deadline/chunk logic, so reusing it is preferable to another independent arithmetic implementation.

Regression design: controlled clock/InterruptibleSleep seams advance large durations without wall-clock waits; invalid clock IDs; boundaries around UINT32_MAX microseconds, INT64_MAX seconds and one Windows wait chunk; EINTR remaining time and untouched errno for successful calls; compare a reasonable finite-duration public fixture against Linux. Primary source for semaphore clock validation: [AOSP semaphore.cpp](https://android.googlesource.com/platform/bionic/+/master/libc/bionic/semaphore.cpp). These are distinct from the prior fixed guest target-CPU-clock support.

### P2: socket timeval width and short buffer contracts are lost

**Source verified.** `socket_bionic.cc:90–120` narrows full-width bionic tv_usec to Windows long before validating; e.g. an out-of-domain value differing by 2^32 can become a valid microsecond value. Negative huge seconds can wrap. Host `socket.cc:754` rejects negative tv_sec with EDOM, whereas Linux sock_set_timeout accepts it as an immediate timeout; invalid tv_usec is EDOM. Guest/host getsockopt timeout wrappers reject short output lengths instead of Linux bounded copy semantics. Avoid inventing a single EINVAL rule for all invalid time values.

Primary Android kernel source: [sock_set_timeout](https://android.googlesource.com/kernel/common/+/54343d951838ef4e423de7d124616bf66eca92e7/net/core/sock.c), especially lines corresponding to function validation and negative seconds. Suggested slice: validate at full guest width, model timeout ownership/rounding explicitly, preserve the open description, and test getsockopt byte-limited copy with canaries. The datagram/provider I/O ownership code has many prior repairs; keep this slice limited to options.

### P2: synthetic event objects copy user buffers without syscall-style errors

**Source verified.** `epoll.cc:185–218` ReadEventFd decrements the counter and updates readiness before memcpy to the caller. Neither read nor write validates a non-null/readable/writable user range. Thus a rejected result copy can terminate the process or consume state rather than returning EFAULT with the original state. Host timerfd already has a narrower null validation. Similar direct copies exist in inotify read and synthetic /dev/zero. Use the already existing Windows user-copy conventions in socket/record-lock code and validate before mutation; do not claim arbitrary invalid-pointer races solved with a null guard alone.

Primary contract: [eventfd(2)](https://man7.org/linux/man-pages/man2/eventfd.2.html). Regression design: controlled failed copy with original event counter/readiness unchanged, then successful read; short-length errors; eventfd semaphore mode; input failure does not wake consumers. A separate security/exploitation exercise is neither needed nor proposed.

### P2: inotify watched-file rename retires its watch

**Source verified.** `inotify.cc:262–265` emits IN_MOVE_SELF and calls End on the old filename. Linux watches attach to the inode and survive rename, so subsequent modification should still be observed. Current implementation tracks directory/name after initial inode identity but does not update the watched filename on rename. `IN_EXCL_UNLINK` is stored at 523; a source search finds no behavior consuming that flag beyond masking. Existing docs already list other event-family limits; do not claim full inotify support from queue/readiness implementation.

Primary contract: [inotify(7)](https://man7.org/linux/man-pages/man7/inotify.7.html). Regression design: add watch on a file, rename within directory, modify through new name and preopened descriptor, expect IN_MOVE_SELF then later IN_MODIFY without IN_IGNORED until final unlink/removal; cross-directory rename needs its own defined support envelope.

## Explicit stubs and known compatibility boundaries

These are source-present but not full implementations. Some are purposeful unsupported policies, others misleading success answers. They are backlog, not all recommended for this PR.

| API family | Actual current behavior | Status |
|---|---|---|
| Guest mlock/munlock/mlock2/mlockall/munlockall | `extra_sys_bionic.cc:1078–1082` registers Zero2/Zero1/Zero0; no lock is taken | Misleading success; host VirtualLock implementation exists but has different limit/error semantics |
| Guest mincore | `extra_sys_bionic.cc:592` fills all vector entries with 1; ignores address | Misleading residency; host version equates MEM_COMMIT with residence, also not true residency |
| Guest posix_madvise | Always 0 at 596, including invalid args | Advisory content can be ignored, admission errors still meaningful; do not map POSIX_DONTNEED to destructive Linux DONTNEED |
| fcntl seals | `system.cc:459–461` returns 0 for add/get without enforcing or storing | Known documented gap in memfd-syscall followup; not new and not equivalent to successful sealing |
| memfd advanced flags | Selected unsupported sealing/hugepage/noexec requests explicitly rejected | Prior repair; preserve truthful failures |
| Host scheduler/affinity | sched_setaffinity returns 0; getaffinity is available CPU set; param setters ignored | Partial; group topology/policy/permission not Linux |
| Host pthread_getcpuclockid | Ignores target and returns current-thread clock | Known 11/13 system-contract report; not new |
| Guest process-shared synchronization | Several attrs store flags; sem_init ignores pshared; waits are WaitOnAddress in one process | Not cross-process synchronization; already called out in earlier reports |
| Guest tgkill/tkill | `extra_sys_bionic.cc:604` ignores tgid; only current native thread accepted, forwards raise | Partial targeting, not Linux tgid/tid verification |
| fork, mounts, unshare | Host ENOSYS; guest mount family EPERM | Explicit unsupported policies |
| xattrs | ENOTSUP host / EOPNOTSUPP guest | Explicit unsupported policy; does not persist labels |
| Resource limits | Stored values, mostly no enforcement; children CPU use all zero | Known system-model limitation |
| Blocking record locks | Public dispatch absent despite sophisticated internal bounded wait helper | Known gap; separate dispatch/cancellation/deadlock contract required |
| Socket ancillary control | Unsupported control rejected instead of silently dropped after prior fixes | Deliberate remaining gap: SCM_RIGHTS/credentials/packet info |
| Epoll edge/exclusive / descriptor identity | ET treated like level; exclusive wake unsupported; watch lifetime follows numeric fd rather than last open-description alias | Partial; needs explicit event-generation/ownership model |
| Guest memory streams | fmemopen/open_memstream return ENOSYS | Native libc callers need honest capability/fallback; not symbol absence |
| Binder scheduling/extended diagnostics | Selected IOCTLs return success/no action; extended-error always BR_OK | Partial Linux driver behavior |
| Virtual procfs | Own process and selected synthetic kernel/system files only | Cannot satisfy arbitrary /proc/PID or device probing |

## Concerns not yet promoted to verified bug claims

- Guest Dirfd at `posix_bionic.cc:201` lazily opens the original path; an intervening cwd change or rename can produce a different descriptor than the DIR enumeration. Reproduce through real Windows directory handles before selecting this slice.
- Guest readdir copies into a 256-byte name field; Windows permits filenames with 255 UTF-16 code units, which can exceed 255 UTF-8 bytes. Long names can be silently truncated or cut inside UTF-8. Needs boundary test and a deliberate name-length policy shared with creation APIs.
- Timed recvmmsg polls then receives; a second receiver can consume readiness. Confirm actual nonblocking flags/deadline loop and Linux's own documented recvmmsg timeout semantics before asserting a stronger timeout guarantee.
- Localtime/mktime remain CRT-bound, unlike the new wide UTC paths; Android timezone/history and negative epoch behavior need direct comparison. strftime width arithmetic and allocations also need bounded malformed-format testing.
- Throwing allocations in descriptor/map/watch state use the project's no-exceptions configuration; OOM translation is not universally covered by prior malloc-return tests. Use injected allocation-failure boundaries, not memory exhaustion on the machine.
- `PunchView` and several mapping operations ignore Win32 failures in their rollback/reconstruction paths. A transactional overhaul requires the existing file-alias/helper ownership design, not a local errno patch.

## Integration and QA handoff

Recommended first implementation scope: advice safety and its exact supported envelope, plus public range admission only where tests/callers justify including it. Preserve ART private-anonymous zeroing, `RetryAfterMadvise`, alias eligibility, and existing source-extraction markers. Do not silently rewrite the file-mremap helper/union workflows; root owns CI and integration.

Run the new Linux oracle, the production-seam negative control against the old source, and the repaired seam against new source. Then real Windows `mman_probe` on placeholders enabled and disabled, production guest ELF x86-64/ARM64 tests, and existing zeroing/fault/alias tests. Expected unsupported results must be explicit in reports. Only Windows execution proves MapViewOfFile/VirtualQuery/VirtualUnlock/COW behavior; Linux seam success alone is insufficient.

All source claims above are anchored in this baseline's files and the cited platform contracts. Prior reports, symbol counts and CI success certify their particular tested scope; none certifies that every Android app or every unusual API combination works.
