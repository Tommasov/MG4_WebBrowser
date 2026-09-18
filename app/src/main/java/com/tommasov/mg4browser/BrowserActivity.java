package com.tommasov.mg4browser;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.tommasov.mg4browser.download.ApkInstaller;
import com.tommasov.mg4browser.download.FileDownloader;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * The whole browser: one activity, one WebView, one toolbar, and a home screen of favourites.
 *
 * <p>There are deliberately no tabs, no settings, no profile and no engine picker. The engine
 * underneath is the head unit's system WebView, which on this car is a Chromium 69 from 2018
 * and will never be updated — it ships with the firmware and there are no Play Services to
 * replace it. A browser over that is a service tool: open a page, read it, get through a Wi-Fi
 * login, fetch an APK and install it. Anything built on top of it would be built on sand.
 */
public class BrowserActivity extends AppCompatActivity {

    private static final String TAG = "BrowserActivity";

    /**
     * Web text, enlarged. The head unit is 1920x720 at a low density, so a page gets a
     * desktop-width viewport and renders at roughly 1:1 — which is fine for layout and far
     * too small to read from a metre back. 125 was picked on the emulator as the point where
     * body text becomes comfortable without pushing common layouts into overflow. Like
     * {@code Dialogs.FONT_SCALE} it is one number in one place, because that is how it will
     * be retuned once someone reads a page in the actual car.
     */
    private static final int TEXT_ZOOM_PERCENT = 125;

    private static final String STATE_WEBVIEW = "webview";

    private WebView web;
    private EditText address;
    private ImageButton buttonBack;
    private ImageButton buttonForward;
    private ImageButton buttonReload;
    private ProgressBar progress;
    private ScrollView home;
    private GridLayout favoritesGrid;
    private TextView favoritesEmpty;

    private Favorites favorites;
    private FileDownloader downloader;

    private AlertDialog downloadDialog;
    private String downloadName;
    /** Set while the certificate prompt is up, so one bad page cannot stack a dozen. */
    private boolean sslPromptShowing;
    /** An APK waiting for the user to come back from the install-permission screen. */
    private File pendingApk;
    private boolean loading;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        favorites = new Favorites(this);
        downloader = new FileDownloader(this);

        web = findViewById(R.id.web);
        address = findViewById(R.id.address);
        buttonBack = findViewById(R.id.button_back);
        buttonForward = findViewById(R.id.button_forward);
        buttonReload = findViewById(R.id.button_reload);
        progress = findViewById(R.id.progress);
        home = findViewById(R.id.home);
        favoritesGrid = findViewById(R.id.favorites_grid);
        favoritesEmpty = findViewById(R.id.favorites_empty);

        configureWebView();
        wireToolbar();
        showEngineNotice();

        if (savedInstanceState != null) {
            Bundle state = savedInstanceState.getBundle(STATE_WEBVIEW);
            if (state != null) {
                web.restoreState(state);
                hideHome();
            }
        }
        if (home.getVisibility() == View.VISIBLE) {
            renderFavorites();
        }
        updateNavigationButtons();
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = web.getSettings();
        // JavaScript is on. It has to be: a captive portal's "accept and connect" button is
        // JavaScript more often than not, and that is one of the jobs this app exists for.
        // It is also the largest part of the attack surface of an unpatched engine, which is
        // why the home screen says plainly what engine this is.
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        // Pinch to zoom, but no floating +/- widget: it would sit on top of the page and is
        // a small target on a screen where nothing else is.
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(TEXT_ZOOM_PERCENT);
        // Compatibility, not ALWAYS_ALLOW: a https page may pull in http images, which is
        // what old sites actually do, but an http script inside a https page stays blocked.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        // Third-party cookies are needed more often than one would like: a captive portal
        // typically bounces through the operator's own domain before letting you out.
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                loading = true;
                setAddressText(url);
                updateNavigationButtons();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loading = false;
                setAddressText(url);
                progress.setVisibility(View.INVISIBLE);
                updateNavigationButtons();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // Only the main document. A page that fails to fetch one tracking script is
                // not a page the user needs to be told about.
                if (!request.isForMainFrame()) {
                    return;
                }
                CharSequence description = error.getDescription();
                Log.w(TAG, "page error " + description + " for " + request.getUrl());
                Dialogs.toast(BrowserActivity.this,
                        getString(R.string.page_error, description));
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                           SslError error) {
                promptForSslError(handler, error);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.INVISIBLE);
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                startDownload(url, userAgent, contentDisposition, mimetype);
            }
        });
    }

    /**
     * Whether the WebView should hand {@code uri} off rather than load it. Web pages stay in
     * the app; everything else — {@code mailto:}, {@code tel:}, {@code intent:} — goes to the
     * system, which on this car will usually have nothing to answer with, and saying so is
     * better than a blank page.
     */
    private boolean handleUrl(@Nullable Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Dialogs.toast(this, R.string.no_app_for_link);
        }
        return true;
    }

    /**
     * Asks before continuing past a certificate that would not verify.
     *
     * <p>Not silently proceeding, and not silently refusing. Refusing outright would break the
     * Wi-Fi and charger login pages this browser is partly for — they intercept traffic, so
     * their certificate is wrong by construction. Proceeding silently would make the one
     * warning that matters invisible. So the user is told which it might be and decides.
     */
    private void promptForSslError(@NonNull SslErrorHandler handler, @NonNull SslError error) {
        if (sslPromptShowing) {
            // A page can raise this once per subresource. One question, not twenty.
            handler.cancel();
            return;
        }
        sslPromptShowing = true;
        String host = Urls.host(error.getUrl());
        Dialogs.builder(this)
                .setTitle(R.string.ssl_error_title)
                .setMessage(getString(R.string.ssl_error_message, host))
                .setPositiveButton(R.string.continue_anyway, (d, w) -> handler.proceed())
                .setNegativeButton(R.string.cancel, (d, w) -> handler.cancel())
                .setOnCancelListener(d -> handler.cancel())
                .setOnDismissListener(d -> sslPromptShowing = false)
                .show();
    }

    // ---------------------------------------------------------------- Toolbar

    private void wireToolbar() {
        buttonBack.setOnClickListener(v -> goBack());
        buttonForward.setOnClickListener(v -> {
            if (web.canGoForward()) {
                web.goForward();
                hideHome();
            }
        });
        buttonReload.setOnClickListener(v -> {
            if (home.getVisibility() == View.VISIBLE) {
                return;
            }
            if (loading) {
                web.stopLoading();
            } else {
                web.reload();
            }
        });
        findViewById(R.id.button_home).setOnClickListener(v -> showHome());
        findViewById(R.id.button_add_favorite).setOnClickListener(v -> addCurrentPage());

        address.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN);
            if (!go) {
                return false;
            }
            navigateTo(address.getText().toString());
            return true;
        });
    }

    private void navigateTo(@Nullable String input) {
        String url = Urls.toUrlOrSearch(input);
        if (url == null) {
            return;
        }
        hideKeyboard();
        address.clearFocus();
        hideHome();
        web.loadUrl(url);
    }

    private void goBack() {
        if (home.getVisibility() == View.VISIBLE) {
            // Already at the top of this app's own history.
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
        } else {
            showHome();
        }
    }

    @Override
    public void onBackPressed() {
        if (home.getVisibility() == View.VISIBLE) {
            super.onBackPressed();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            showHome();
        }
    }

    private void setAddressText(@Nullable String url) {
        // Leave it alone while it is being edited, or a page that finishes loading mid-type
        // would wipe what the user has written.
        if (address.hasFocus()) {
            return;
        }
        address.setText(url == null ? "" : url);
    }

    private void updateNavigationButtons() {
        boolean onHome = home.getVisibility() == View.VISIBLE;
        setEnabled(buttonForward, !onHome && web.canGoForward());
        setEnabled(buttonBack, !onHome);
        setEnabled(buttonReload, !onHome);
        buttonReload.setImageResource(loading ? R.drawable.ic_stop : R.drawable.ic_reload);
        buttonReload.setContentDescription(
                getString(loading ? R.string.cd_stop : R.string.cd_reload));
    }

    /** Dims a button that would do nothing, rather than letting it look live and not react. */
    private static void setEnabled(@NonNull View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.35f);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
        }
    }

    // ---------------------------------------------------------------- Home screen

    private void showHome() {
        renderFavorites();
        home.setVisibility(View.VISIBLE);
        // The WebView keeps its page and stays where it is, simply covered: coming back from
        // the home screen should not cost a reload on a connection this slow.
        address.setText("");
        hideKeyboard();
        address.clearFocus();
        updateNavigationButtons();
    }

    private void hideHome() {
        home.setVisibility(View.GONE);
        updateNavigationButtons();
    }

    private void addCurrentPage() {
        String url = web.getUrl();
        if (home.getVisibility() == View.VISIBLE || TextUtils.isEmpty(url)) {
            Dialogs.toast(this, R.string.favorite_nothing_to_add);
            return;
        }
        if (favorites.add(url, web.getTitle())) {
            Dialogs.toast(this, R.string.favorite_added);
        } else {
            Dialogs.toast(this, R.string.favorite_exists);
        }
    }

    private void renderFavorites() {
        favoritesGrid.removeAllViews();
        List<Favorites.Entry> entries = favorites.all();
        favoritesEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        int gap = getResources().getDimensionPixelSize(R.dimen.tile_gap);
        int columns = columnsThatFit(gap);
        int tileWidth = tileWidthFor(columns, gap);
        favoritesGrid.setColumnCount(columns);
        for (Favorites.Entry entry : entries) {
            View tile = inflater.inflate(R.layout.item_favorite, favoritesGrid, false);
            TextView badge = tile.findViewById(R.id.tile_badge);
            TextView title = tile.findViewById(R.id.tile_title);
            TextView host = tile.findViewById(R.id.tile_host);

            String hostName = Urls.host(entry.url);
            badge.setText(initialOf(hostName.isEmpty() ? entry.title : hostName));
            tintBadge(badge, hostName);
            title.setText(entry.title);
            host.setText(hostName);

            tile.setOnClickListener(v -> navigateTo(entry.url));
            tile.setOnLongClickListener(v -> {
                confirmRemove(entry);
                return true;
            });

            GridLayout.LayoutParams params = (GridLayout.LayoutParams) tile.getLayoutParams();
            params.width = tileWidth;
            params.setMargins(0, 0, gap, gap);
            tile.setLayoutParams(params);
            favoritesGrid.addView(tile);
        }
    }

    /**
     * How many tiles fit across this screen, rather than across the screen this was written on.
     *
     * <p>Measured from the display rather than from a laid-out view, because it is needed
     * before the first tile is inflated and the answer does not depend on layout: the row
     * lives inside the home screen's own padding, and that is a constant.
     *
     * <p>Every tile carries a trailing gap, including the last in a row, so a column costs a
     * tile plus a gap. The trailing one on the right-hand tile falls in the padding where
     * nobody sees it — which is why it was possible to overlook that it still takes up room,
     * and why the fifth tile was clipped on the car for a month.
     */
    private int columnsThatFit(int gap) {
        int minimum = getResources().getDimensionPixelSize(R.dimen.tile_width_min);
        return Math.max(1, availableWidth() / (minimum + gap));
    }

    /**
     * The width to give each tile so that the row fills the screen exactly.
     *
     * <p>Stretching them beats leaving a gap at the right: at 1778 a five-column row of the
     * old fixed 340dp tiles would have left most of a sixth tile of dead space, and dropping
     * to four columns would have left more still.
     */
    private int tileWidthFor(int columns, int gap) {
        return availableWidth() / columns - gap;
    }

    private int availableWidth() {
        int padding = getResources().getDimensionPixelSize(R.dimen.screen_padding);
        return getResources().getDisplayMetrics().widthPixels - 2 * padding;
    }

    /** Long press is the only way to remove one: there is no menu to put a Delete in. */
    private void confirmRemove(@NonNull Favorites.Entry entry) {
        Dialogs.builder(this)
                .setTitle(R.string.remove_favorite_title)
                .setMessage(getString(R.string.remove_favorite_message,
                        entry.title.isEmpty() ? Urls.host(entry.url) : entry.title))
                .setPositiveButton(R.string.remove, (d, w) -> {
                    favorites.remove(entry.url);
                    renderFavorites();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @NonNull
    private static String initialOf(@NonNull String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return "?";
    }

    /**
     * Colours a tile's badge from its host name, so a site keeps the same colour every time
     * and the grid becomes something you can find your way around by shape rather than by
     * reading five labels.
     */
    private static void tintBadge(@NonNull TextView badge, @NonNull String host) {
        // Fixed saturation and value, hue from the name: every badge comes out equally
        // readable against white text, which picking random RGB would not guarantee.
        float hue = Math.abs(host.toLowerCase(Locale.US).hashCode() % 360);
        int color = Color.HSVToColor(new float[]{hue, 0.55f, 0.75f});
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        badge.setBackground(shape);
    }

    /**
     * Puts the engine's real version on the home screen.
     *
     * <p>Two reasons. It is the honest thing to show — this engine has not had a security
     * patch in years and the person using it should not have to read a store description to
     * find that out. And it answers, from the car itself, the one question nobody could
     * answer from a desk: which WebView this head unit actually has.
     */
    private void showEngineNotice() {
        TextView notice = findViewById(R.id.engine_notice);
        PackageInfo info = WebView.getCurrentWebViewPackage();
        String version = info == null ? null : info.versionName;
        notice.setText(TextUtils.isEmpty(version)
                ? getString(R.string.webview_version_unknown)
                : getString(R.string.webview_version, version));
    }

    // ---------------------------------------------------------------- Downloads

    /**
     * The reason this app exists: getting a file — usually an APK — onto a car that has
     * neither Play Services nor any other way to receive one.
     */
    private void startDownload(@NonNull String url, @Nullable String userAgent,
                               @Nullable String contentDisposition, @Nullable String mimeType) {
        if (downloader.isRunning()) {
            Dialogs.toast(this, R.string.download_busy);
            return;
        }
        // DownloadManager fetches over the network in its own process, so it can only be
        // given something with a network address. blob: and data: URLs exist only inside the
        // page that made them; saying so beats a failure with an opaque reason code.
        if (!URLUtil.isNetworkUrl(url)) {
            Dialogs.toast(this, getString(R.string.download_failed, url));
            return;
        }

        downloadName = sanitize(URLUtil.guessFileName(url, contentDisposition, mimeType));
        final String mime = mimeType;
        showDownloadDialog();

        downloader.start(url, downloadName, userAgent, new FileDownloader.Callback() {
            @Override
            public void onProgress(int percent) {
                if (downloadDialog != null && downloadDialog.isShowing()) {
                    downloadDialog.setMessage(
                            getString(R.string.download_progress, downloadName, percent));
                }
            }

            @Override
            public void onComplete(@NonNull File file) {
                dismissDownloadDialog();
                if (ApkInstaller.isApk(file, mime)) {
                    offerInstall(file);
                } else {
                    Dialogs.toast(BrowserActivity.this,
                            getString(R.string.download_saved, file.getName()));
                }
            }

            @Override
            public void onFailed(@NonNull String reason) {
                dismissDownloadDialog();
                Dialogs.toast(BrowserActivity.this,
                        getString(R.string.download_failed, reason));
            }
        });
    }

    private void showDownloadDialog() {
        downloadDialog = Dialogs.builder(this)
                .setTitle(R.string.download_title)
                .setMessage(getString(R.string.download_progress, downloadName, 0))
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> downloader.cancel())
                .create();
        downloadDialog.show();
    }

    private void dismissDownloadDialog() {
        if (downloadDialog != null) {
            downloadDialog.dismiss();
            downloadDialog = null;
        }
    }

    /**
     * Offers to install a downloaded APK, sending the user to grant the permission first if
     * Android has not been told this browser may install apps.
     */
    private void offerInstall(@NonNull File apk) {
        if (!ApkInstaller.canInstall(this)) {
            // Remembered so onResume can pick the flow back up: the user is about to leave
            // for a system screen, and having to download the file again on their return
            // would be a poor thanks for granting the permission.
            pendingApk = apk;
            Dialogs.builder(this)
                    .setTitle(R.string.install_permission_title)
                    .setMessage(R.string.install_permission_message)
                    .setPositiveButton(R.string.open_settings,
                            (d, w) -> ApkInstaller.requestInstallPermission(this))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        Dialogs.builder(this)
                .setTitle(R.string.install_title)
                .setMessage(getString(R.string.install_message, apk.getName()))
                .setPositiveButton(R.string.install, (d, w) -> {
                    if (!ApkInstaller.install(this, apk)) {
                        Dialogs.toast(this, R.string.install_no_handler);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Keeps a server-supplied name from becoming a path. */
    @NonNull
    private static String sanitize(@Nullable String name) {
        if (TextUtils.isEmpty(name)) {
            return "download.bin";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_'
                    ? c : '_');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- Lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (pendingApk != null && ApkInstaller.canInstall(this)) {
            File apk = pendingApk;
            pendingApk = null;
            offerInstall(apk);
        }
    }

    @Override
    protected void onPause() {
        // Stops timers and any media the page started: a car that plays a page's video from a
        // backgrounded app is a car with a mystery noise.
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (home.getVisibility() != View.VISIBLE) {
            Bundle state = new Bundle();
            web.saveState(state);
            outState.putBundle(STATE_WEBVIEW, state);
        }
    }

    @Override
    protected void onDestroy() {
        dismissDownloadDialog();
        downloader.cancel();
        // Detach before destroying, or the WebView takes its parent's layout pass with it.
        web.setWebChromeClient(null);
        ((android.view.ViewGroup) web.getParent()).removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
