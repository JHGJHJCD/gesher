# Memory advice: preserve mapping origin and backing data

The previous `compat/posix/src/mman.cc` implementation routed DONTNEED, FREE
and REMOVE through the same zeroing operation. Writable shared-file views
therefore changed their backing files, while copied private-file tails were
mistaken for anonymous memory. ADR0080 already documented the narrow file
zeroing limitation; this repair addresses it and makes the remaining unsupported
branches explicit. It does not implement arbitrary Linux virtual-memory parity.

## Implementation

Each logical page now retains an origin independent of Windows' allocation
type: private/shared anonymous, private/shared file, device or unknown. This
costs one byte per page, about 0.025% of virtual mapping size with 4 KiB pages.
Placeholder splits, fixed replacement, file tails, plain copied-file fallback,
shared aliases and Binder receive buffers preserve or replace the origin
appropriately. The anonymous mremap copy path rejects nonanonymous origins
instead of silently converting them into discardable anonymous mappings.

Advice validates its number, aligned start, checked page rounding, checked end,
complete live-page coverage and origin before modifying memory. A rejected
mixed range leaves its earlier private-anonymous pages intact. Existing
zeroing/protection serialization and fault retry remain in place. Batched
QueryWorkingSetEx calls reject locked or large private pages before discard;
host mlock and munlock share the same registry lock. Calls outside the mapping
registry can receive validated nondestructive hints, but lack the provenance
needed for destructive advice.

The public mmap admission also rejects missing/invalid mapping classes,
nonanonymous invalid descriptors, unaligned offsets and arithmetic overflow.
An unaligned nonfixed address remains a valid hint. Internal anonymous helpers
keep their existing internal flags conventions.

## Exact supported policy

| Mapping | DONTNEED | FREE | REMOVE |
| --- | --- | --- | --- |
| Private anonymous | Zero-fill committed pages; preserve a live PROT_NONE reservation | Eager zero-fill, permitted by Linux's reclaim semantics | EINVAL |
| Shared anonymous | EOPNOTSUPP; unchanged | EINVAL; unchanged | EOPNOTSUPP; unchanged |
| Private file, including copied tails | EOPNOTSUPP; unchanged | EINVAL; unchanged | EACCES; unchanged |
| Shared file | EOPNOTSUPP; unchanged | EINVAL; unchanged | EOPNOTSUPP; unchanged |
| Binder/device | EINVAL; unchanged | EINVAL; unchanged | EINVAL; unchanged |
| Unregistered native allocation | EOPNOTSUPP; unchanged | EOPNOTSUPP; unchanged | EOPNOTSUPP; unchanged |

File/shared DONTNEED and shared REMOVE are valid Linux operations that remain
unsupported here. A private file discard needs to restore its section/COW
relationship, not write a snapshot of file bytes back into private memory.
REMOVE needs a backing-store hole operation, not memset. Shared-anonymous
mappings still use the existing Windows private-allocation backend; this repair
does not add cross-process sharing.

NORMAL, RANDOM, SEQUENTIAL, HUGEPAGE and NOHUGEPAGE are explicit validated
preferences. DONTFORK/DOFORK remain accepted while fork itself is unsupported;
ART uses DONTFORK for shared JIT views. WILLNEED prefetches accessible committed
runs and propagates Windows errors. It does not commit PROT_NONE reservations.
Unknown advice returns EINVAL, including at zero length. A zero-length valid
request still requires alignment. Positive ranges containing holes return
ENOMEM. Other recognized Linux advice values are outside this implementation.

## Verification and integration

`tools/test_mman_zeroing_mappings.py` extracts actual production functions into
the existing controlled Win32 seam. The repaired source passed 30 assertions
with AddressSanitizer/UndefinedBehaviorSanitizer, including both preexisting
concurrency cases. The exact pre-repair source compiled against the same
fixture and produced 15 expected assertion failures. Assertion totals differ
because the old code incorrectly enters extra physical decommit operations;
the difference is not a measure of coverage. Leak detection was disabled only
for the local ptrace-restricted runner; the CI command need not disable it.

`tools/test_madvise_reference.py` compiles `tests/native/madvise_contract.c`
against real Linux libc/kernel. It passed 147 checks locally. The common source
also builds as the Windows host `madvise_probe` and as x86-64/ARM64 Android ELF
libraries exporting `gesher_selftest`. Android builds must explicitly define
`GESHER_MADVISE_WINDOWS`; this selects precise unsupported-result expectations
instead of accepting either success or failure. Tests cover the advice matrix,
backing-file bytes, independent shared aliases, original descriptor close/reuse,
nonzero offsets, private partial tails, fixed mixed provenance and replacement,
page rounding, invalid requests, holes, PROT_NONE/read-only pages, host locks and
ART's shared-view DONTFORK path. Guest mlock's existing separate stub is not
certified by the host/Linux lock cases.

Both Windows mapping modes must execute the common host and guest fixtures:
the normal placeholder path and `GESHER_MMAP_NO_PLACEHOLDERS=1`. Existing
`mman_probe` concurrency/fault tests remain required. Windows x64 and ARM64
production objects compile with the pinned llvm-mingw toolchain; local Linux
and compilation results do not establish Windows runtime correctness. The PR's
Windows CI results supply that final evidence.

## Primary contracts

- [Android 16 kernel memory advice](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12-2025-05-exp/mm/madvise.c)
- [Linux madvise(2)](https://man7.org/linux/man-pages/man2/madvise.2.html)
- [QueryWorkingSetEx](https://learn.microsoft.com/en-us/windows/win32/api/psapi/nf-psapi-queryworkingsetex)
- [Working-set Locked/Valid fields](https://learn.microsoft.com/en-us/windows/win32/api/psapi/ns-psapi-psapi_working_set_ex_block)
- [VirtualUnlock](https://learn.microsoft.com/en-us/windows/win32/api/memoryapi/nf-memoryapi-virtualunlock): can evict unlocked pages but cannot discard private COW state; not substituted for DONTNEED here.
- [DiscardVirtualMemory](https://learn.microsoft.com/en-us/windows/win32/api/memoryapi/nf-memoryapi-discardvirtualmemory): restored contents are undefined; not a zero-fill substitute.
