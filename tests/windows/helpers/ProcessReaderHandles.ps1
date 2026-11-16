# Shared CI-only exact owned-reader/native pipe close primitive.
if (-not ('GtestProcessNativeHandles' -as [type])) {
  # This test infrastructure is compiled only when the CI scripts execute.
  Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class GtestProcessNativeHandles {
  const int ErrorInvalidHandle = 6;
  const int ErrorNotSameObject = 1656;
  const uint DuplicateSameAccess = 2;
  [ThreadStatic] static string lastDiagnostic;
  // CI controls can retain an independent native witness before cleanup. The
  // observer never supplies the production close verdict and is normally null.
  [ThreadStatic] public static Action<SafeFileHandle> BeforeCloseObserver;
  public static string LastDiagnostic { get { return lastDiagnostic ?? "no close observation"; } }

  [DllImport("kernel32.dll")]
  static extern IntPtr GetCurrentProcess();
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool DuplicateHandle(IntPtr process, SafeFileHandle source,
      IntPtr target, out IntPtr duplicate, uint access, bool inherit, uint options);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool GetHandleInformation(IntPtr handle, out uint flags);
  [DllImport("kernelbase.dll", SetLastError = true)]
  static extern bool CompareObjectHandles(IntPtr first, IntPtr second);
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool CloseHandle(IntPtr handle);

  public static bool CloseOwnedReader(StreamReader reader, SafeFileHandle owned) {
    bool sameOwner = false, sourceOpen = false, duplicated = false, witnessValid = false;
    bool identityBefore = false, disposed = false, safeClosed = false, sameAfter = true;
    bool compared = false, witnessClosed = false;
    int duplicateError = 0, witnessError = 0, identityError = 0, compareError = 0, closeError = 0;
    IntPtr value = IntPtr.Zero, witness = IntPtr.Zero;
    string problem = "";
    try {
      var file = reader == null ? null : reader.BaseStream as FileStream;
      sameOwner = file != null && owned != null && Object.ReferenceEquals(file.SafeFileHandle, owned);
      sourceOpen = sameOwner && !owned.IsClosed && !owned.IsInvalid;
      if (sourceOpen) {
        var observer = BeforeCloseObserver;
        if (observer != null) observer(owned);
        value = owned.DangerousGetHandle();
        // The SafeFileHandle argument pins the live source during duplication.
        // Keep the witness private: it names the exact kernel object even if
        // another thread reuses the original numeric slot after Dispose.
        IntPtr process = GetCurrentProcess();
        duplicated = DuplicateHandle(process, owned, process, out witness, 0, false, DuplicateSameAccess);
        duplicateError = Marshal.GetLastWin32Error();
        if (duplicated) {
          uint flags;
          witnessValid = witness != IntPtr.Zero && witness != new IntPtr(-1) &&
              GetHandleInformation(witness, out flags);
          witnessError = Marshal.GetLastWin32Error();
          if (witnessValid) {
            identityBefore = CompareObjectHandles(value, witness);
            identityError = Marshal.GetLastWin32Error();
          }
        }
      }
    } catch (Exception e) { problem = "capture: " + e.GetType().Name + ": " + e.Message; }
    finally {
      // Always attempt the actual reader cleanup, including a failed/mismatched
      // capture. Its SafeFileHandle keeps ownership and pending-reference rules.
      try {
        if (reader != null) { reader.Dispose(); disposed = true; }
      } catch (Exception e) { problem += " dispose: " + e.GetType().Name + ": " + e.Message; }
      safeClosed = owned != null && owned.IsClosed;
      try {
        if (sameOwner && sourceOpen && duplicated && witnessValid && identityBefore && disposed && safeClosed) {
          sameAfter = CompareObjectHandles(value, witness);
          compareError = Marshal.GetLastWin32Error();
          compared = true;
        }
      } catch (Exception e) { problem += " compare: " + e.GetType().Name + ": " + e.Message; }
      finally {
        if (duplicated && witness != IntPtr.Zero && witness != new IntPtr(-1)) {
          // This raw duplicate is exclusively ours, with no pending I/O or
          // SafeHandle owner. The native close result proves its release; never
          // probe its freed numeric value or retry an uncertain close.
          witnessClosed = CloseHandle(witness);
          closeError = Marshal.GetLastWin32Error();
        }
      }
    }
    bool gone = compared && !sameAfter &&
        (compareError == ErrorInvalidHandle || compareError == ErrorNotSameObject);
    bool success = sameOwner && sourceOpen && duplicated && witnessValid && identityBefore &&
        disposed && safeClosed && gone && witnessClosed && problem.Length == 0;
    lastDiagnostic = "same-owner=" + sameOwner + " source-open=" + sourceOpen +
        " duplicate=" + duplicated + "/" + duplicateError + " witness-valid=" + witnessValid + "/" + witnessError +
        " identity-before=" + identityBefore + "/" + identityError + " disposed=" + disposed +
        " safe-closed=" + safeClosed + " compared=" + compared + " same-after=" + sameAfter +
        " compare-error=" + compareError + " witness-closed=" + witnessClosed + "/" + closeError +
        (problem.Length == 0 ? "" : " problem=" + problem);
    return success;
  }
}
'@
}
