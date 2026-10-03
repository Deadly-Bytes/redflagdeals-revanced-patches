[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Isolated JVM checks of the real extension against small Android view doubles.
# These exercise recycling/callback/state handling, not Android rendering or the server.
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = Join-Path $projectRoot ('build/reply-voting-tests/' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @{
    'android/util/DisplayMetrics.java' = @'
package android.util;
public class DisplayMetrics { public float density = 1; }
'@
    'android/util/Log.java' = @'
package android.util;
public class Log { public static int i(String tag, String message) { return 0; } }
'@
    'android/os/Build.java' = @'
package android.os;
public class Build {
    public static class VERSION { public static int SDK_INT = 34; }
    public static class VERSION_CODES { public static final int LOLLIPOP = 21; }
}
'@
    'android/content/res/ColorStateList.java' = @'
package android.content.res;
public class ColorStateList {
    public int color;
    public static ColorStateList valueOf(int color) {
        ColorStateList value = new ColorStateList(); value.color = color; return value;
    }
}
'@
    'android/content/res/Resources.java' = @'
package android.content.res;
import android.util.DisplayMetrics;
public class Resources {
    public boolean missing;
    public int getIdentifier(String name, String type, String packageName) {
        if (missing) return 0;
        switch (name) {
            case "ic_thumb_down": return 1;
            case "ic_thumb_down_outline": return 2;
            case "vote_active": return 3;
            case "vote_inactive": return 4;
            case "vote_down": return 5;
            default: throw new AssertionError("Unexpected resource: " + name);
        }
    }
    public int getColor(int id) { return id; }
    public String getString(int id) { return "Vote down"; }
    public DisplayMetrics getDisplayMetrics() { return new DisplayMetrics(); }
}
'@
    'android/content/Context.java' = @'
package android.content;
import android.content.res.Resources;
public class Context {
    public final Resources resources = new Resources();
    public Resources getResources() { return resources; }
    public String getPackageName() { return "com.ypg.rfdforums"; }
}
'@
    'android/view/Gravity.java' = @'
package android.view;
public class Gravity { public static final int CENTER_VERTICAL = 16; }
'@
    'android/view/View.java' = @'
package android.view;
import android.content.Context;
import android.content.res.Resources;
public class View {
    public static final int VISIBLE = 0, GONE = 8;
    public interface OnClickListener { void onClick(View view); }
    private final Context context;
    private Object tag;
    public OnClickListener listener;
    public boolean enabled = true, selected, clickable, focusable;
    public int visibility = VISIBLE;
    public CharSequence description;
    public ViewGroup.LayoutParams layout;
    public View(Context context) { this.context = context; }
    public Context getContext() { return context; }
    public Resources getResources() { return context.getResources(); }
    public Object getTag() { return tag; }
    public void setTag(Object value) { tag = value; }
    public void setOnClickListener(OnClickListener value) { listener = value; }
    public void setEnabled(boolean value) { enabled = value; }
    public void setSelected(boolean value) { selected = value; }
    public void setVisibility(int value) { visibility = value; }
    public void setClickable(boolean value) { clickable = value; }
    public void setFocusable(boolean value) { focusable = value; }
    public void setContentDescription(CharSequence value) { description = value; }
    public void setLayoutParams(ViewGroup.LayoutParams value) { layout = value; }
    public void setPadding(int left, int top, int right, int bottom) {}
    public void click() { if (enabled && visibility == VISIBLE && listener != null) listener.onClick(this); }
}
'@
    'android/view/ViewGroup.java' = @'
package android.view;
import android.content.Context;
import java.util.ArrayList;
public class ViewGroup extends View {
    private final ArrayList<View> children = new ArrayList<>();
    public ViewGroup(Context context) { super(context); }
    public int getChildCount() { return children.size(); }
    public View getChildAt(int index) { return children.get(index); }
    public void addView(View child) { children.add(child); }
    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public int width, height;
        public LayoutParams(int width, int height) { this.width = width; this.height = height; }
    }
}
'@
    'android/widget/LinearLayout.java' = @'
package android.widget;
import android.content.Context;
import android.view.ViewGroup;
public class LinearLayout extends ViewGroup {
    public LinearLayout(Context context) { super(context); }
    public static class LayoutParams extends ViewGroup.LayoutParams {
        public int gravity;
        public LayoutParams(int width, int height) { super(width, height); }
    }
}
'@
    'android/widget/ImageView.java' = @'
package android.widget;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
public class ImageView extends View {
    public int image;
    public ColorStateList tint;
    public ImageView(Context context) { super(context); }
    public void setImageResource(int value) { image = value; }
    public void setImageTintList(ColorStateList value) { tint = value; }
}
'@
    'ReplyVotingTest.java' = @'
import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import app.revanced.extension.redflagdeals.ReplyVoting;

public class ReplyVotingTest {
    public static class Model {
        public int vote, calls;
        public boolean available = true, allowed = true;
        public View clicked;
        public int getVote() { return vote; }
        public boolean isVotesEnabled() { return available; }
        public void onVoteDown(View view) { calls++; clicked = view; }
    }
    public static class Binding {
        public final LinearLayout votes = new LinearLayout(new Context());
        public Object model = new Model();
        public Object getVoteViewModel() { return model; }
    }
    public static class BrokenModel extends Model {
        @Override public int getVote() { throw new IllegalStateException("test"); }
    }
    public static class BrokenAction extends Model {
        @Override public void onVoteDown(View view) { throw new IllegalStateException("test"); }
    }
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static void hidden(ImageView view, String reason) {
        check(view.visibility == View.GONE && !view.enabled && view.listener == null, reason);
    }
    public static void main(String[] args) {
        Binding binding = new Binding();
        // Retain existing score and thumbs-up children untouched.
        View score = new View(binding.votes.getContext());
        ImageView up = new ImageView(binding.votes.getContext());
        binding.votes.addView(score); binding.votes.addView(up);
        Model first = (Model) binding.model;
        ReplyVoting.bind(binding);
        check(binding.votes.getChildCount() == 3, "one control appended");
        ImageView down = (ImageView) binding.votes.getChildAt(2);
        check(down.enabled && down.visibility == View.VISIBLE, "available control visible");
        check(down.image == 2 && down.tint.color == 4 && !down.selected, "neutral outline/tint");
        check("Vote down".contentEquals(down.description) && down.focusable, "accessible button");
        down.click(); check(first.calls == 1 && first.clicked == down, "delegates native downvote with view");
        for (int i = 0; i < 20; i++) ReplyVoting.bind(binding);
        check(binding.votes.getChildCount() == 3, "rebind must not duplicate controls");
        check(binding.votes.getChildAt(0) == score && binding.votes.getChildAt(1) == up, "stock children preserved");

        first.vote = -1; ReplyVoting.bind(binding);
        check(down.image == 1 && down.tint.color == 3 && down.selected, "selected downvote renders");
        down.click(); check(first.calls == 2, "selected downvote still delegates native undo");
        first.vote = 0; ReplyVoting.bind(binding);
        check(down.image == 2 && !down.selected, "rollback/undo returns to outline");
        first.vote = 1; ReplyVoting.bind(binding);
        check(down.image == 2 && down.tint.color == 4, "upvote does not select downvote");

        Model second = new Model(); binding.model = second;
        down.click(); check(second.calls == 1 && first.calls == 2, "click resolves current model even before rebind");
        ReplyVoting.bind(binding); down.click();
        check(second.calls == 2 && first.calls == 2, "recycled row uses current callback");
        second.allowed = false; down.click();
        check(second.calls == 3, "permission/login decision is delegated to stock handler");

        binding.model = null; ReplyVoting.bind(binding); hidden(down, "null model clears previous action");
        binding.model = second; second.available = false;
        ReplyVoting.bind(binding); hidden(down, "no vote data hides control");
        second.available = true; ReplyVoting.bind(binding);
        binding.model = null; down.click(); hidden(down, "null model at click time safe");
        binding.model = new BrokenModel(); ReplyVoting.bind(binding); hidden(down, "reflective render failure fails closed");
        binding.model = new Object(); ReplyVoting.bind(binding); hidden(down, "missing reflective methods fail closed");
        binding.model = new BrokenAction(); ReplyVoting.bind(binding); down.click(); hidden(down, "action failure clears listener");

        binding.model = second;
        binding.votes.setVisibility(View.GONE);
        ReplyVoting.bind(binding);
        check(binding.votes.visibility == View.GONE, "deleted/multiquote parent visibility untouched");
        binding.votes.getContext().resources.missing = true;
        ReplyVoting.bind(binding); hidden(down, "missing resources hide existing control");
        binding.votes.getContext().resources.missing = false;
        ReplyVoting.bind(binding); check(down.enabled && down.image == 2, "rebind recovers after unavailable resources");
        Binding missingAtCreation = new Binding();
        missingAtCreation.votes.getContext().resources.missing = true;
        ReplyVoting.bind(missingAtCreation);
        check(missingAtCreation.votes.getChildCount() == 0, "creation failure leaves no interactive partial view");
        ReplyVoting.bind(null);
        System.out.println("PASS: " + checks + " reply voting lifecycle/delegation checks (JVM view doubles).");
    }
}
'@
}
$javaFiles = @()
foreach ($entry in $sources.GetEnumerator()) {
    $path = Join-Path $work $entry.Key
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    Set-Content -LiteralPath $path -Value $entry.Value -Encoding utf8NoBOM
    $javaFiles += $path
}
$extension = Join-Path $projectRoot 'extensions/rfd/src/main/java/app/revanced/extension/redflagdeals/ReplyVoting.java'
& javac -encoding UTF-8 -d $classes @javaFiles $extension
if ($LASTEXITCODE -ne 0) { throw 'Reply voting JVM test compilation failed.' }
& java -cp $classes ReplyVotingTest
if ($LASTEXITCODE -ne 0) { throw 'Reply voting JVM tests failed.' }
