package app.revanced.extension.redflagdeals;

import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Credential-safe runtime diagnostics for the RedFlagDeals compatibility patch. */
public final class Diagnostics {
    public static final String BUILD_MARKER = "RFDLoginFix-20260820-4";
    private static final String TAG = "RFDSession";

    private Diagnostics() {
    }

    public static void logAuthRequest(Object request, Object accountManager, String cookies) {
        try {
            Method getUrl = request.getClass().getMethod("getUrl");
            String url = (String) getUrl.invoke(request);
            if (url == null || (!url.contains("/topics") && !url.contains("/users/me"))) {
                return;
            }

            Method getYidToken = accountManager.getClass().getMethod("getYidToken");
            Object yid = getYidToken.invoke(accountManager);
            boolean hasYid = yid instanceof String && !((String) yid).isEmpty();
            boolean hasUser = cookies != null && cookies.contains("_u=");
            boolean hasSid = cookies != null && cookies.contains("_sid=");

            Log.i(TAG, BUILD_MARKER + " request endpoint=" + url
                    + " auth_yid=" + hasYid
                    + " auth_phpbb_u=" + hasUser
                    + " auth_phpbb_sid=" + hasSid);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            Log.i(TAG, BUILD_MARKER + " request diagnostic unavailable");
        }
    }

    public static void logCookieParse(Object cookieName, Object sid, Object user) {
        if (cookieName == null) {
            Log.i(TAG, "response cookie parse missing cookie name");
        } else if (sid == null) {
            Log.i(TAG, "response cookie parse missing SID");
        } else if (user == null) {
            Log.i(TAG, "response cookie parse missing user marker");
        } else {
            Log.i(TAG, "response cookie parse complete");
        }
    }

    public static void logCookieTupleInput(Object cookieName, Object sid, Object user) {
        if (cookieName == null || sid == null || user == null) {
            Log.i(TAG, "phpBB cookie tuple incomplete");
        } else if ("1".equals(user)) {
            Log.i(TAG, "phpBB cookie tuple rejected: guest user marker");
        }
    }

    public static void logCookieTupleAccepted() {
        Log.i(TAG, "phpBB cookie tuple accepted");
    }

    public static void logLogout() {
        Log.i(TAG, "logout invoked; stack follows", new Throwable());
    }

    public static void hideQuickReply(Object fragment) {
        try {
            Field loginField = fragment.getClass().getDeclaredField("mQuickReplyLogin");
            Field guestField = fragment.getClass().getDeclaredField("mQuickReplyGuest");
            loginField.setAccessible(true);
            guestField.setAccessible(true);
            Object login = loginField.get(fragment);
            Object guest = guestField.get(fragment);
            if (login instanceof View) {
                ((View) login).setVisibility(View.GONE);
            }
            if (guest instanceof View) {
                ((View) guest).setVisibility(View.GONE);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            Log.i(TAG, BUILD_MARKER + " quick-reply hide unavailable");
        }
    }

    public static boolean isReplyAllowed(Object topic) {
        try {
            Class<?> type = topic.getClass();
            boolean canReply = (Boolean) type.getMethod("getCanReply").invoke(topic);
            boolean locked = (Boolean) type.getMethod("isLocked").invoke(topic);
            boolean sticky = (Boolean) type.getMethod("isSticky").invoke(topic);
            boolean moved = (Boolean) type.getMethod("isMoved").invoke(topic);
            return canReply && !locked && !sticky && !moved;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    public static void logTopicState(Object topic, String source) {
        try {
            Class<?> type = topic.getClass();
            int topicId = (Integer) type.getMethod("getTopicId").invoke(topic);
            int forumId = (Integer) type.getMethod("getForumId").invoke(topic);
            boolean canReply = (Boolean) type.getMethod("getCanReply").invoke(topic);
            boolean locked = (Boolean) type.getMethod("isLocked").invoke(topic);
            boolean sticky = (Boolean) type.getMethod("isSticky").invoke(topic);
            boolean moved = (Boolean) type.getMethod("isMoved").invoke(topic);
            Log.i(TAG, BUILD_MARKER + " topic source=" + source
                    + " topic_id=" + topicId
                    + " forum_id=" + forumId
                    + " canReply=" + canReply
                    + " isLocked=" + locked
                    + " isSticky=" + sticky
                    + " isMoved=" + moved);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            Log.i(TAG, BUILD_MARKER + " topic diagnostic unavailable source=" + source);
        }
    }

    public static void logExactRefreshFailed() {
        Log.i(TAG, BUILD_MARKER + " exact topic refresh failed; reply unavailable");
    }
}
