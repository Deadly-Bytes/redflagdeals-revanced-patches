package app.revanced.extension.redflagdeals;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Adds the stock down-vote action to reply rows after their data binding completes. */
public final class ReplyVoting {
    private static final String TAG = "RFDReplyVote";
    private static final String VIEW_TAG = "app.revanced.rfd.reply-vote-down";
    private static final int TAP_TARGET_DP = 40;
    // Stock reply thumbs-up has 35dp width with 9dp padding on each side.
    private static final int ICON_DP = 17;

    private ReplyVoting() {
    }

    /**
     * Rebinds the injected reply down-vote control.
     *
     * <p>This method deliberately accepts {@link Object}: the patch calls it from the app's
     * generated binding without adding a compile-time dependency on the target APK.</p>
     */
    public static void bind(final Object binding) {
        ImageView voteDown = null;
        try {
            if (binding == null) {
                return;
            }

            LinearLayout votes = getVotes(binding);
            voteDown = findOrCreateVoteDown(votes);

            // A recycled binding must never retain its previous row's action or appearance.
            voteDown.setOnClickListener(null);
            voteDown.setEnabled(false);
            voteDown.setVisibility(View.GONE);

            Object voteViewModel = getVoteViewModel(binding);
            if (voteViewModel == null || !isVotingAvailable(voteViewModel)) {
                return;
            }

            render(voteDown, voteViewModel);
            voteDown.setEnabled(true);
            voteDown.setVisibility(View.VISIBLE);
            voteDown.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    voteDown(binding, view);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            hide(voteDown);
            Log.i(TAG, "reply down-vote unavailable");
        }
    }

    private static LinearLayout getVotes(Object binding) throws ReflectiveOperationException {
        Field votes = binding.getClass().getField("votes");
        Object value = votes.get(binding);
        if (!(value instanceof LinearLayout)) {
            throw new IllegalStateException("reply vote container missing");
        }
        return (LinearLayout) value;
    }

    private static Object getVoteViewModel(Object binding) throws ReflectiveOperationException {
        Method getter = binding.getClass().getMethod("getVoteViewModel");
        return getter.invoke(binding);
    }

    private static boolean isVotingAvailable(Object voteViewModel)
            throws ReflectiveOperationException {
        Object available = voteViewModel.getClass().getMethod("isVotesEnabled").invoke(voteViewModel);
        if (!(available instanceof Boolean)) {
            throw new IllegalStateException("reply vote availability missing");
        }

        // Match the stock up button's availability (a non-null PostVote). The native
        // onPrepareVote handler remains responsible for login and down-vote permission.
        return (Boolean) available;
    }

    private static ImageView findOrCreateVoteDown(LinearLayout votes) {
        for (int index = 0; index < votes.getChildCount(); index++) {
            View child = votes.getChildAt(index);
            if (VIEW_TAG.equals(child.getTag())) {
                if (child instanceof ImageView) {
                    return (ImageView) child;
                }
                throw new IllegalStateException("reply vote tag collision");
            }
        }

        ImageView voteDown = new ImageView(votes.getContext());
        voteDown.setTag(VIEW_TAG);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(votes, TAP_TARGET_DP), ViewGroup.LayoutParams.MATCH_PARENT);
        params.gravity = Gravity.CENTER_VERTICAL;
        voteDown.setLayoutParams(params);
        int inset = dp(votes, (TAP_TARGET_DP - ICON_DP) / 2f);
        voteDown.setPadding(inset, inset, inset, inset);
        voteDown.setClickable(true);
        voteDown.setFocusable(true);
        voteDown.setContentDescription(stringResource(voteDown, "vote_down"));
        votes.addView(voteDown);
        return voteDown;
    }

    private static void render(ImageView voteDown, Object voteViewModel)
            throws ReflectiveOperationException {
        Method getVote = voteViewModel.getClass().getMethod("getVote");
        Object result = getVote.invoke(voteViewModel);
        if (!(result instanceof Integer)) {
            throw new IllegalStateException("reply vote state missing");
        }

        boolean selected = ((Integer) result) == -1;
        voteDown.setSelected(selected);
        voteDown.setImageResource(drawableResource(
                voteDown, selected ? "ic_thumb_down" : "ic_thumb_down_outline"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            voteDown.setImageTintList(ColorStateList.valueOf(colorResource(
                    voteDown, selected ? "vote_active" : "vote_inactive")));
        }
    }

    private static void voteDown(Object binding, View view) {
        try {
            // Look up the model at click time so recycled rows cannot vote for an old post.
            Object voteViewModel = getVoteViewModel(binding);
            if (voteViewModel == null || !isVotingAvailable(voteViewModel)) {
                hide(view);
                return;
            }
            Method onVoteDown = voteViewModel.getClass().getMethod("onVoteDown", View.class);
            onVoteDown.invoke(voteViewModel, view);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            hide(view);
            Log.i(TAG, "reply down-vote action unavailable");
        }
    }

    private static int drawableResource(View view, String name) {
        return requiredResource(view, name, "drawable");
    }

    private static int colorResource(View view, String name) {
        int id = requiredResource(view, name, "color");
        return view.getResources().getColor(id);
    }

    private static String stringResource(View view, String name) {
        return view.getResources().getString(requiredResource(view, name, "string"));
    }

    private static int requiredResource(View view, String name, String type) {
        Resources resources = view.getResources();
        int id = resources.getIdentifier(name, type, view.getContext().getPackageName());
        if (id == 0) {
            throw new IllegalStateException("reply vote resource missing");
        }
        return id;
    }

    private static int dp(View view, float value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static void hide(View view) {
        if (view != null) {
            view.setOnClickListener(null);
            view.setEnabled(false);
            view.setVisibility(View.GONE);
        }
    }
}
