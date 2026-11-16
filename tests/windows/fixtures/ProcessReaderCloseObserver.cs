// Independent CI observer for actual helper calls. Capture before disposal:
// probing a freed numeric handle later cannot identify the original pipe.
namespace ProcessReaderObservation {
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

public static class NativeWitnesses {
  sealed class Witness {
    public readonly SafeFileHandle Owner;
    public readonly IntPtr Original;
    public IntPtr Duplicate;
    public Witness(SafeFileHandle owner) {
      Owner = owner;
      Original = owner.DangerousGetHandle();
    }
  }
  [ThreadStatic] static Dictionary<IntPtr, Witness> witnesses;
  [ThreadStatic] static bool active;

  [DllImport("kernel32.dll")]
  static extern IntPtr GetCurrentProcess();
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool DuplicateHandle(IntPtr process, SafeFileHandle source,
      IntPtr target, out IntPtr duplicate, uint access, bool inherit, uint options);
  [DllImport("kernelbase.dll", SetLastError = true)]
  static extern bool CompareObjectHandles(IntPtr first, IntPtr second);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool CloseHandle(IntPtr handle);

  public static Action<SafeFileHandle> CaptureCallback { get { return Capture; } }
  public static void Begin() {
    if (active) throw new InvalidOperationException("reader observation already active");
    witnesses = new Dictionary<IntPtr, Witness>(2);
    active = true;
  }
  static void Capture(SafeFileHandle owner) {
    if (!active || owner == null || owner.IsClosed || owner.IsInvalid)
      throw new InvalidOperationException("independent observer needs a live owned pipe");
    var witness = new Witness(owner);
    if (witnesses.Count >= 2 || witnesses.ContainsKey(witness.Original))
      throw new InvalidOperationException("previous reader witnesses were not consumed");
    bool recorded = false;
    try {
      IntPtr process = GetCurrentProcess();
      // SafeFileHandle marshaling pins the source throughout duplication.
      if (!DuplicateHandle(process, owner, process, out witness.Duplicate, 0, false, 2))
        throw new Win32Exception(Marshal.GetLastWin32Error(), "independent reader witness");
      if (!CompareObjectHandles(witness.Original, witness.Duplicate))
        throw new InvalidOperationException("independent witness names a different object");
      witnesses.Add(witness.Original, witness);
      recorded = true;
    } finally {
      if (!recorded) Release(witness);
    }
  }
  static void Release(Witness witness) {
    IntPtr value = witness.Duplicate;
    witness.Duplicate = IntPtr.Zero;
    // Never inspect or retry the freed duplicate's numeric slot.
    if (value != IntPtr.Zero && !CloseHandle(value))
      throw new Win32Exception(Marshal.GetLastWin32Error(), "independent witness close");
  }
  public static bool Closed(IntPtr original) {
    Witness witness;
    if (!active || !witnesses.TryGetValue(original, out witness)) return false;
    witnesses.Remove(original);
    try {
      bool same = CompareObjectHandles(original, witness.Duplicate);
      int error = Marshal.GetLastWin32Error();
      // Both an invalid slot and a slot reused for another object prove the
      // original reader handle is gone. A retained pipe still compares equal.
      return witness.Owner.IsClosed && !same && (error == 6 || error == 1656);
    } finally { Release(witness); }
  }
  public static bool ClosedPair(IntPtr output, IntPtr error) {
    bool outputClosed = Closed(output);
    bool errorClosed = Closed(error);
    return output != error && outputClosed && errorClosed;
  }
  public static void End() {
    if (!active) return;
    active = false;
    int pending = witnesses.Count;
    Exception failure = null;
    foreach (Witness witness in witnesses.Values) {
      try { Release(witness); }
      catch (Exception error) { if (failure == null) failure = error; }
    }
    witnesses.Clear();
    if (failure != null) throw failure;
    if (pending != 0) throw new InvalidOperationException("unverified reader witnesses remain: " + pending);
  }
}
}
