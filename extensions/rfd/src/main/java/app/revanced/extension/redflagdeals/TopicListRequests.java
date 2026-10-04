package app.revanced.extension.redflagdeals;

import android.net.Uri;

import java.lang.reflect.Method;

/** Adds a one-time cache-busting query parameter only to the forum topic-list GET request. */
public final class TopicListRequests {
    private TopicListRequests() {
    }

    /**
     * Appends a nonce to the exact topic-list URL so the origin receives the request.
     * Any mismatch or reflection error leaves the request unchanged.
     */
    public static void bypassSharedCache(Object request) {
        try {
            if (request == null) {
                return;
            }
            Method getMethod = request.getClass().getMethod("getMethod");
            if (!Integer.valueOf(0).equals(getMethod.invoke(request))) {
                return;
            }
            Method getUrl = request.getClass().getMethod("getUrl");
            Object value = getUrl.invoke(request);
            if (!(value instanceof String)) {
                return;
            }

            Uri uri = Uri.parse((String) value);
            if (!"https".equals(uri.getScheme()) ||
                    !"forums.redflagdeals.com".equals(uri.getHost()) ||
                    !"/api/topics".equals(uri.getPath())) {
                return;
            }

            String freshUrl = uri.buildUpon()
                    .appendQueryParameter("rfd_unread_nonce", Long.toString(System.nanoTime()))
                    .build()
                    .toString();
            request.getClass().getMethod("setUrl", String.class).invoke(request, freshUrl);
        } catch (Exception ignored) {
            // Cache bypass must never interfere with the app's original request path.
        }
    }
}
