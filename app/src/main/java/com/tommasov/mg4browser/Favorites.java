package com.tommasov.mg4browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The favourites shown on the home screen, kept in {@link SharedPreferences}.
 *
 * <p>A JSON array in one preference rather than a database: this is a handful of rows that are
 * only ever read all at once, and a schema to migrate would be more machinery than the data
 * deserves. If it ever grows past what a car's screen can show, that is the moment to
 * reconsider, not before.
 */
public final class Favorites {

    private static final String TAG = "Favorites";
    private static final String PREFS = "mg4browser";
    private static final String KEY = "favorites";

    /** One saved page. */
    public static final class Entry {
        @NonNull public final String url;
        @NonNull public final String title;

        Entry(@NonNull String url, @NonNull String title) {
            this.url = url;
            this.title = title;
        }
    }

    private final SharedPreferences prefs;

    public Favorites(@NonNull Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Every favourite, in the order they were added. */
    @NonNull
    public List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        String raw = prefs.getString(KEY, null);
        if (raw == null) {
            return out;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                String url = o.optString("url", "");
                if (!url.isEmpty()) {
                    out.add(new Entry(url, o.optString("title", "")));
                }
            }
        } catch (Exception e) {
            // Corrupt preferences should cost the user their favourites, not the app: an
            // empty home screen they can rebuild beats a browser that will not open.
            Log.w(TAG, "could not read favourites", e);
        }
        return out;
    }

    /** True if {@code url} is already saved. */
    public boolean contains(@Nullable String url) {
        if (url == null) {
            return false;
        }
        for (Entry e : all()) {
            if (samePage(e.url, url)) {
                return true;
            }
        }
        return false;
    }

    /** Adds a favourite. Returns false if that page was already there. */
    public boolean add(@NonNull String url, @Nullable String title) {
        List<Entry> current = all();
        for (Entry e : current) {
            if (samePage(e.url, url)) {
                return false;
            }
        }
        String label = title == null ? "" : title.trim();
        if (label.isEmpty()) {
            label = Urls.host(url);
        }
        current.add(new Entry(url, label));
        save(current);
        return true;
    }

    /** Removes whatever is saved for {@code url}. */
    public void remove(@NonNull String url) {
        List<Entry> current = all();
        List<Entry> kept = new ArrayList<>(current.size());
        for (Entry e : current) {
            if (!samePage(e.url, url)) {
                kept.add(e);
            }
        }
        save(kept);
    }

    private void save(@NonNull List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("url", e.url);
                o.put("title", e.title);
                array.put(o);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not write favourites", e);
            return;
        }
        prefs.edit().putString(KEY, array.toString()).apply();
    }

    /**
     * Whether two addresses are the same page for the purpose of the home screen. A trailing
     * slash is the difference between what a site links to and what it redirects to, and
     * seeing the same site twice on the grid because of one would be baffling.
     */
    private static boolean samePage(@NonNull String a, @NonNull String b) {
        return stripTrailingSlash(a).equalsIgnoreCase(stripTrailingSlash(b));
    }

    @NonNull
    private static String stripTrailingSlash(@NonNull String url) {
        String s = url.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
