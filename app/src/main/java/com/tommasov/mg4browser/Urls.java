package com.tommasov.mg4browser;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.regex.Pattern;

/** Turns what was typed in the address bar into something to load. */
public final class Urls {

    /**
     * The search engine is fixed and not configurable, which was a deliberate decision: this
     * is a service browser, and a settings screen nobody opens is a settings screen that
     * should not exist.
     */
    private static final String SEARCH_PREFIX = "https://www.google.com/search?q=";

    /** Anything of the form {@code scheme:...} is taken at its word. */
    private static final Pattern HAS_SCHEME =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*:.*", Pattern.DOTALL);

    /** Characters a bare host (plus optional port) may contain. */
    private static final Pattern HOST_CHARS =
            Pattern.compile("^[a-zA-Z0-9\\-._~%]+$");

    /** A top-level domain is letters. Digits after the last dot mean a number, not a site. */
    private static final Pattern TLD = Pattern.compile("^[a-zA-Z]{2,}$");

    private static final Pattern IPV4 =
            Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");

    private Urls() {
    }

    /**
     * What to load for {@code input}, or null if there is nothing to load.
     *
     * <p>Everything that is not recognisably an address becomes a search. That is the right
     * default on this screen: typing a full URL on a car's on-screen keyboard is unpleasant
     * enough that most entries will be a site's name rather than its address.
     */
    @Nullable
    public static String toUrlOrSearch(@Nullable String input) {
        if (input == null) {
            return null;
        }
        String text = input.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (HAS_SCHEME.matcher(text).matches()) {
            return text;
        }
        if (looksLikeHost(text)) {
            return "https://" + text;
        }
        return search(text);
    }

    /** A Google search for {@code query}. */
    @NonNull
    public static String search(@NonNull String query) {
        try {
            return SEARCH_PREFIX + URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is required of every JVM; this branch cannot be reached.
            throw new AssertionError(e);
        }
    }

    /**
     * Whether the text is an address rather than something to search for.
     *
     * <p>The awkward cases are the ones that look numeric. "192.168.1.1" is a router login
     * page and has to load; "3.14" and "1.5" are things somebody is looking up, and sending
     * them to https://3.14 would be a dead end with no way back except retyping. So the part
     * after the last dot has to be letters, unless the whole thing is an IPv4 address.
     */
    private static boolean looksLikeHost(@NonNull String text) {
        if (text.indexOf(' ') >= 0 || text.indexOf('\t') >= 0) {
            return false;
        }
        // Strip anything the host is followed by: /path, ?query, #fragment.
        int end = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String hostAndPort = text.substring(0, end);
        // A port is a strong hint on its own: "localhost:8080" is not a search.
        int colon = hostAndPort.lastIndexOf(':');
        String host = colon >= 0 ? hostAndPort.substring(0, colon) : hostAndPort;
        boolean hasPort = colon >= 0 && isDigits(hostAndPort.substring(colon + 1));
        if (host.isEmpty() || !HOST_CHARS.matcher(host).matches()) {
            return false;
        }
        if (host.equalsIgnoreCase("localhost") || IPV4.matcher(host).matches()) {
            return true;
        }
        int dot = host.lastIndexOf('.');
        if (dot < 0) {
            // A bare word with a port ("nas:5000") is a host on the local network; a bare
            // word on its own is something to search for.
            return hasPort;
        }
        return TLD.matcher(host.substring(dot + 1)).matches();
    }

    private static boolean isDigits(@NonNull String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** The host of {@code url}, for showing under a favourite's title. Never null. */
    @NonNull
    public static String host(@Nullable String url) {
        if (url == null) {
            return "";
        }
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) {
                return url;
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return url;
        }
    }
}
