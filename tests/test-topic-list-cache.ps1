[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Offline contract checks for the real TopicListRequests helper. The Android Uri double
# delegates parsing to java.net.URI and appends only a query parameter; it does not model
# Android networking, CDN caching, OkHttp, or the live API.
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = Join-Path $projectRoot ('build/topic-list-cache-tests/' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @{
    'android/net/Uri.java' = @'
package android.net;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class Uri {
    private final String value;
    private Uri(String value) { this.value = value; }
    public static Uri parse(String value) { return new Uri(value); }
    private URI parsed() {
        try { return new URI(value); }
        catch (URISyntaxException error) { throw new IllegalArgumentException(error); }
    }
    public String getScheme() { return parsed().getScheme(); }
    public String getHost() { return parsed().getHost(); }
    public String getPath() { return parsed().getPath(); }
    public Builder buildUpon() { return new Builder(value); }
    @Override public String toString() { return value; }

    public static final class Builder {
        private String value;
        private Builder(String value) { this.value = value; }
        public Builder appendQueryParameter(String key, String parameterValue) {
            int fragmentStart = value.indexOf('#');
            String fragment = fragmentStart < 0 ? "" : value.substring(fragmentStart);
            String base = fragmentStart < 0 ? value : value.substring(0, fragmentStart);
            String separator = !base.contains("?") ? "?" : (base.endsWith("?") || base.endsWith("&") ? "" : "&");
            String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
            String encodedValue = URLEncoder.encode(parameterValue, StandardCharsets.UTF_8);
            value = base + separator + encodedKey + "=" + encodedValue + fragment;
            return this;
        }
        public Uri build() { return new Uri(value); }
    }
}
'@
    'TopicListRequestsTest.java' = @'
import app.revanced.extension.redflagdeals.TopicListRequests;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TopicListRequestsTest {
    public static class Request {
        private final int method;
        private String url;
        public final Map<String, String> headers = new HashMap<>();
        public final Object body;
        public Request(int method, String url, Object body) {
            this.method = method; this.url = url; this.body = body;
            headers.put("X-Test", "unchanged");
        }
        public int getMethod() { return method; }
        public String getUrl() { return url; }
        public void setUrl(String value) { url = value; }
    }
    public static class MissingMethod {
        private String url = "https://forums.redflagdeals.com/api/topics";
        public String getUrl() { return url; }
        public void setUrl(String value) { url = value; }
    }
    public static class MissingUrl {
        public int getMethod() { return 0; }
    }
    public static class MissingSetter {
        public int getMethod() { return 0; }
        public String getUrl() { return "https://forums.redflagdeals.com/api/topics"; }
    }
    public static class FailingSetter {
        private final String url = "https://forums.redflagdeals.com/api/topics";
        public int getMethod() { return 0; }
        public String getUrl() { return url; }
        public void setUrl(String ignored) { throw new IllegalStateException("test"); }
    }

    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static String nonce(String url) {
        Matcher matcher = Pattern.compile("(?:[?&])rfd_unread_nonce=(-?\\d+)(?:&|#|$)").matcher(url);
        if (!matcher.find()) throw new AssertionError("nonce missing: " + url);
        return matcher.group(1);
    }
    private static void unchanged(Request request, String original, Map<String, String> headers, Object body, String label) {
        check(original.equals(request.getUrl()), label + " URL unchanged");
        check(request.headers == headers && request.headers.equals(Map.of("X-Test", "unchanged")), label + " headers untouched");
        check(request.body == body, label + " body untouched");
    }

    public static void main(String[] args) throws Exception {
        String original = "https://forums.redflagdeals.com/api/topics?sort_field=last_post_time&search=coffee%20table&category_id=2#page=3";
        Object body = new Object();
        Request target = new Request(0, original, body);
        Map<String, String> headers = target.headers;
        TopicListRequests.bypassSharedCache(target);
        String changed = target.getUrl();
        URI parsed = new URI(changed);
        check("https".equals(parsed.getScheme()) && "forums.redflagdeals.com".equals(parsed.getHost()), "target authority preserved");
        check("/api/topics".equals(parsed.getPath()), "target path preserved");
        check(parsed.getRawQuery().contains("sort_field=last_post_time"), "sort query preserved");
        check(parsed.getRawQuery().contains("search=coffee%20table"), "encoded existing parameter preserved byte-for-byte");
        check(parsed.getRawQuery().contains("category_id=2"), "other existing query preserved");
        check("page=3".equals(parsed.getRawFragment()), "fragment preserved");
        check(!changed.equals(original), "target URL changed");
        String firstNonce = nonce(changed);
        check(firstNonce.matches("-?\\d+"), "nonce is numeric (nanoTime may have either sign)");
        check(changed.indexOf("rfd_unread_nonce=") == changed.lastIndexOf("rfd_unread_nonce="), "one nonce parameter appended");
        check(target.headers == headers && target.headers.equals(Map.of("X-Test", "unchanged")), "headers untouched");
        check(target.body == body, "body untouched");

        Request repeated = new Request(0, original, body);
        TopicListRequests.bypassSharedCache(repeated);
        check(!firstNonce.equals(nonce(repeated.getUrl())), "repeated list request gets a fresh nonce");

        String[] ordinaryUrls = {
            "https://forums.redflagdeals.com/api/topics/123",
            "https://forums.redflagdeals.com/api/topics/123/posts",
            "https://forums.redflagdeals.com/api/users/me",
            "https://cdn.redflagdeals.com/api/topics?sort_field=last_post_time",
            "http://forums.redflagdeals.com/api/topics"
        };
        for (String url : ordinaryUrls) {
            Request ordinary = new Request(0, url, body);
            Map<String, String> ordinaryHeaders = ordinary.headers;
            TopicListRequests.bypassSharedCache(ordinary);
            unchanged(ordinary, url, ordinaryHeaders, body, "ordinary GET " + url);
        }
        String postUrl = "https://forums.redflagdeals.com/api/topics?sort_field=last_post_time";
        Request post = new Request(1, postUrl, body);
        Map<String, String> postHeaders = post.headers;
        TopicListRequests.bypassSharedCache(post);
        unchanged(post, postUrl, postHeaders, body, "POST topic-list URL");

        TopicListRequests.bypassSharedCache(null);
        TopicListRequests.bypassSharedCache(new MissingMethod());
        TopicListRequests.bypassSharedCache(new MissingUrl());
        TopicListRequests.bypassSharedCache(new MissingSetter());
        TopicListRequests.bypassSharedCache(new FailingSetter());
        check(true, "null/missing/failing reflection paths fail open without throwing");

        System.out.println("PASS: " + checks + " topic-list cache bypass contract checks (JVM Uri/request doubles; no CDN/API exercised).");
    }
}
'@
}
$javaFiles = @()
foreach ($entry in $sources.GetEnumerator()) {
    $path = Join-Path $work $entry.Key
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [System.IO.File]::WriteAllText($path, $entry.Value, [System.Text.UTF8Encoding]::new($false))
    $javaFiles += $path
}
$extension = Join-Path $projectRoot 'extensions/rfd/src/main/java/app/revanced/extension/redflagdeals/TopicListRequests.java'
if (-not (Test-Path -LiteralPath $extension)) {
    throw "Topic list helper not found: $extension"
}
& javac -encoding UTF-8 -d $classes @javaFiles $extension
if ($LASTEXITCODE -ne 0) { throw 'Topic-list cache JVM test compilation failed.' }
& java -cp $classes TopicListRequestsTest
if ($LASTEXITCODE -ne 0) { throw 'Topic-list cache JVM contract checks failed.' }
