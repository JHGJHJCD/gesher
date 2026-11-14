// Host-only attribution boundary; AudioEffect/Visualizer remain the real framework classes.
package android.content;
import android.os.Parcel;
public final class AttributionSource {
    public static AttributionSource myAttributionSource() { return new AttributionSource(); }
    public ScopedParcelState asScopedParcelState() { return new ScopedParcelState(); }
    public static final class ScopedParcelState implements AutoCloseable {
        public Parcel getParcel() { return null; }
        public void close() {}
    }
}
