/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import java.util.List;

/** Compile separately against the public SDK, then execute against the runtime facade. */
public final class MediaStoreAccessSdkCalls {
    public static boolean manage(Context context) { return MediaStore.canManageMedia(context); }
    public static Uri document(Context context, Uri uri) { return MediaStore.getDocumentUri(context, uri); }
    public static Uri media(Context context, Uri uri) { return MediaStore.getMediaUri(context, uri); }
    public static Uri redacted(ContentResolver resolver, Uri uri) {
        return MediaStore.getRedactedUri(resolver, uri);
    }
    public static List<Uri> redactedList(ContentResolver resolver, List<Uri> uris) {
        return MediaStore.getRedactedUri(resolver, uris);
    }
}
