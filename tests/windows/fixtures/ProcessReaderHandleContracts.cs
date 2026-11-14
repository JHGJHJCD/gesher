// Real Win32 ownership controls for the verbatim shared cleanup primitive.
// No source, timeout or success predicate is replaced by this fixture.
namespace ProcessReaderHandleContracts {
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;

public static class Fixture {
  const int InvalidHandle = 6;
  const int NotSameObject = 1656;
  static int checks, failures;
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool CreatePipe(out IntPtr read, out IntPtr write, IntPtr attributes, uint size);
  [DllImport("kernel32.dll")]
  static extern IntPtr GetCurrentProcess();
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool DuplicateHandle(IntPtr process, IntPtr source, IntPtr target,
      out IntPtr duplicate, uint access, bool inherit, uint options);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool CloseHandle(IntPtr value);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool GetHandleInformation(IntPtr value, out uint flags);
  [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
  static extern IntPtr CreateEventW(IntPtr attributes, bool manual, bool initial, string name);
  [DllImport("kernelbase.dll", SetLastError = true)]
  static extern bool CompareObjectHandles(IntPtr first, IntPtr second);

  static void Check(bool value, string message) {
    ++checks;
    if (!value) { ++failures; Console.WriteLine("FAIL reader ownership: " + message); }
  }
  static void CloseExact(IntPtr value) {
    if (!CloseHandle(value)) throw new Win32Exception(Marshal.GetLastWin32Error(), "fixture-owned native close");
  }
  static bool Valid(IntPtr value) {
    uint flags;
    return GetHandleInformation(value, out flags);
  }

  // Independent native witness, captured BEFORE invoking the production helper.
  // Neither SafeHandle.IsClosed nor the helper's boolean can satisfy this check.
  sealed class Witness : IDisposable {
    readonly IntPtr original;
    IntPtr duplicate;
    public Witness(IntPtr value) {
      original = value;
      IntPtr process = GetCurrentProcess();
      if (!DuplicateHandle(process, value, process, out duplicate, 0, false, 2))
        throw new Win32Exception(Marshal.GetLastWin32Error(), "fixture witness duplicate");
      try {
        if (!CompareObjectHandles(original, duplicate))
          throw new InvalidOperationException("fixture witness must name the original kernel object");
      } catch {
        IntPtr acquired = duplicate; duplicate = IntPtr.Zero;
        CloseExact(acquired);
        throw;
      }
    }
    public bool OriginalGone() {
      bool same = CompareObjectHandles(original, duplicate);
      int error = Marshal.GetLastWin32Error();
      return !same && (error == InvalidHandle || error == NotSameObject);
    }
    public bool OriginalStillOpen() { return CompareObjectHandles(original, duplicate); }
    public void Dispose() {
      IntPtr value = duplicate; duplicate = IntPtr.Zero;
      if (value != IntPtr.Zero) CloseExact(value);
    }
  }

  // Run the ordinary Dispose first, then hold a real event that occupies its
  // freed numeric slot. This controls the exact race without a production hook
  // or replacing any Win32 API. Failure to force reuse is a failing control.
  sealed class ReusingReader : StreamReader {
    readonly IntPtr original;
    // Reserve storage before any pipe is disposed: recording a newly acquired
    // event cannot allocate or leave that handle unrecorded on allocation failure.
    readonly List<IntPtr> events = new List<IntPtr>(8192);
    bool disposed;
    public bool Reused { get; private set; }
    public int Allocations { get { return events.Count; } }
    public ReusingReader(FileStream file, IntPtr value) : base(file) { original = value; }
    protected override void Dispose(bool disposing) {
      var timer = Stopwatch.StartNew();
      base.Dispose(disposing);
      if (!disposing || disposed) return;
      disposed = true;
      for (int i = 0; i < 8192 && timer.ElapsedMilliseconds < 3000; ++i) {
        IntPtr value = CreateEventW(IntPtr.Zero, false, false, null);
        if (value == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error(), "reuse event");
        events.Add(value);
        if (value == original) { Reused = true; break; }
      }
      if (!Reused) throw new InvalidOperationException("real numeric slot reuse was not reached within the fixed control bound");
    }
    public void ReleaseEvents() {
      Exception failure = null;
      foreach (IntPtr value in events) {
        try { CloseExact(value); }
        catch (Exception e) { if (failure == null) failure = e; }
      }
      events.Clear();
      if (failure != null) throw failure;
    }
  }

  sealed class Pipe : IDisposable {
    public readonly IntPtr Raw;
    public readonly SafeFileHandle Handle;
    public readonly FileStream File;
    public readonly StreamReader Reader;
    readonly bool owns;
    bool released;
    public Pipe(bool ownsHandle = true, bool leaveOpen = false, bool reuse = false) {
      IntPtr read, write;
      if (!CreatePipe(out read, out write, IntPtr.Zero, 4096))
        throw new Win32Exception(Marshal.GetLastWin32Error(), "fixture pipe");
      Raw = read; owns = ownsHandle;
      try { CloseExact(write); }
      catch { CloseExact(read); throw; }
      try {
        Handle = new SafeFileHandle(read, ownsHandle);
        File = new FileStream(Handle, FileAccess.Read, 4096, false);
        Reader = reuse ? (StreamReader)new ReusingReader(File, Raw) :
            new StreamReader(File, Encoding.UTF8, true, 1024, leaveOpen);
      } catch {
        try {
          if (File != null) File.Dispose();
          else if (Handle != null) Handle.Dispose();
          else CloseExact(read);
        } finally { if (!owns && Handle != null) CloseExact(read); }
        throw;
      }
    }
    public void CompleteReader() {
      Task<string> task = Reader.ReadToEndAsync();
      if (!task.Wait(2000) || task.Status != TaskStatus.RanToCompletion || task.Result.Length != 0)
        throw new InvalidOperationException("fixture EOF must finish within two seconds");
    }
    public void Dispose() {
      if (released) return;
      released = true;
      try { Reader.Dispose(); }
      finally {
        try { File.Dispose(); }
        finally {
          try { if (!owns) CloseExact(Raw); }
          finally {
            var reused = Reader as ReusingReader;
            if (reused != null) reused.ReleaseEvents();
          }
        }
      }
    }
  }

  static void Case(string name, Action action) {
    int before = failures;
    try { action(); }
    catch (Exception e) { ++failures; Console.WriteLine("FAIL reader ownership: " + name + ": " + e); }
    if (failures == before) Console.WriteLine("PASS reader ownership: " + name);
  }

  // Exact pre-repair predicate, used only as a negative control on another
  // independently created real pipe and the same forced-reuse reader.
  static bool LegacyClose(StreamReader reader, SafeFileHandle owned) {
    var file = reader.BaseStream as FileStream;
    bool same = file != null && Object.ReferenceEquals(file.SafeFileHandle, owned);
    IntPtr value = owned.DangerousGetHandle();
    reader.Dispose();
    uint flags;
    return same && owned.IsClosed && !GetHandleInformation(value, out flags) && Marshal.GetLastWin32Error() == 6;
  }

  public static int Run() {
    checks = failures = 0;
    var timer = Stopwatch.StartNew();
    Case("normal exact pipe release", delegate {
      using (var pipe = new Pipe())
      using (var witness = new Witness(pipe.Raw)) {
        pipe.CompleteReader();
        Check(GtestProcessNativeHandles.CloseOwnedReader(pipe.Reader, pipe.Handle), "normal helper close");
        Check(pipe.Handle.IsClosed && witness.OriginalGone(), "normal independent exact-object native release");
        Check(GtestProcessNativeHandles.LastDiagnostic.Contains("witness-closed=True"), "normal private witness released");
      }
    });
    Case("actual reused numeric value", delegate {
      using (var pipe = new Pipe(reuse: true))
      using (var witness = new Witness(pipe.Raw)) {
        pipe.CompleteReader();
        Check(GtestProcessNativeHandles.CloseOwnedReader(pipe.Reader, pipe.Handle),
            "reused value must retain successful exact-object close: " + GtestProcessNativeHandles.LastDiagnostic);
        var reader = (ReusingReader)pipe.Reader;
        Check(reader.Reused && Valid(pipe.Raw), "a real event now occupies the original numeric slot");
        Check(pipe.Handle.IsClosed && witness.OriginalGone(), "independent witness proves original pipe released despite valid slot");
        Check(GtestProcessNativeHandles.LastDiagnostic.Contains("compare-error=1656") &&
              GtestProcessNativeHandles.LastDiagnostic.Contains("witness-closed=True"),
              "different-object evidence and private witness release retained");
        Console.WriteLine("READER_REUSE allocations=" + reader.Allocations + " exact-object-gone=True slot-valid=True");
      }
    });
    Case("old predicate rejected by the same real reuse", delegate {
      using (var pipe = new Pipe(reuse: true))
      using (var witness = new Witness(pipe.Raw)) {
        pipe.CompleteReader();
        Check(!LegacyClose(pipe.Reader, pipe.Handle), "legacy post-close numeric probe rejects a valid close");
        Check(((ReusingReader)pipe.Reader).Reused && Valid(pipe.Raw) && pipe.Handle.IsClosed &&
              witness.OriginalGone(), "legacy failure is actual value reuse, not an unclosed pipe");
      }
    });
    Case("managed closed but actual native pipe retained", delegate {
      using (var pipe = new Pipe(ownsHandle: false))
      using (var witness = new Witness(pipe.Raw)) {
        pipe.CompleteReader();
        Check(!GtestProcessNativeHandles.CloseOwnedReader(pipe.Reader, pipe.Handle), "must reject a non-owning handle's native leak");
        Check(pipe.Handle.IsClosed && Valid(pipe.Raw) && witness.OriginalStillOpen(),
              "independent native witness proves IsClosed alone is insufficient");
        Check(GtestProcessNativeHandles.LastDiagnostic.Contains("same-owner=True") &&
              GtestProcessNativeHandles.LastDiagnostic.Contains("safe-closed=True") &&
              GtestProcessNativeHandles.LastDiagnostic.Contains("same-after=True") &&
              GtestProcessNativeHandles.LastDiagnostic.Contains("witness-closed=True"),
              "real native leak failure remains identifiable");
      }
    });
    Case("leave-open reader cannot claim pipe release", delegate {
      using (var pipe = new Pipe(leaveOpen: true))
      using (var witness = new Witness(pipe.Raw)) {
        pipe.CompleteReader();
        Check(!GtestProcessNativeHandles.CloseOwnedReader(pipe.Reader, pipe.Handle), "must reject leave-open reader");
        Check(!pipe.Handle.IsClosed && witness.OriginalStillOpen(), "leave-open pipe remains actually owned");
      }
    });
    Case("mismatched owner leaves unrelated pipe intact", delegate {
      using (var first = new Pipe())
      using (var other = new Pipe())
      using (var firstWitness = new Witness(first.Raw))
      using (var otherWitness = new Witness(other.Raw)) {
        first.CompleteReader(); other.CompleteReader();
        Check(!GtestProcessNativeHandles.CloseOwnedReader(first.Reader, other.Handle), "must reject mismatched SafeFileHandle");
        Check(first.Handle.IsClosed && firstWitness.OriginalGone(), "still cleans up the actual reader");
        Check(!other.Handle.IsClosed && otherWitness.OriginalStillOpen(), "never disposes the unrelated owner");
        Check(GtestProcessNativeHandles.LastDiagnostic.Contains("same-owner=False") &&
              GtestProcessNativeHandles.LastDiagnostic.Contains("duplicate=False"),
              "identity mismatch fails before witness acquisition");
      }
    });
    Case("missing owner fails but actual reader is disposed", delegate {
      using (var pipe = new Pipe())
      using (var witness = new Witness(pipe.Raw)) {
        pipe.CompleteReader();
        Check(!GtestProcessNativeHandles.CloseOwnedReader(pipe.Reader, null), "missing owner rejected");
        Check(pipe.Handle.IsClosed && witness.OriginalGone(), "missing-owner failure still cleans actual reader");
      }
    });
    Case("already disposed reader cannot close a recycled event", delegate {
      using (var pipe = new Pipe(reuse: true)) {
        pipe.CompleteReader();
        pipe.Reader.Dispose();
        Check(((ReusingReader)pipe.Reader).Reused && Valid(pipe.Raw), "repeat-close control really holds a replacement event");
        Check(!GtestProcessNativeHandles.CloseOwnedReader(pipe.Reader, pipe.Handle), "already closed ownership rejected");
        Check(Valid(pipe.Raw), "replacement event survives failed repeated close");
      }
    });
    Check(timer.ElapsedMilliseconds < 30000, "all native controls finish within thirty seconds");
    Console.WriteLine("process reader handles: " + checks + " checks, " + failures + " failed");
    return failures == 0 ? 0 : 1;
  }
}
}
