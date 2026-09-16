package com.tommasov.mg4browser.download;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.File;

/**
 * Downloads a file with the system {@link DownloadManager} and reports progress.
 *
 * <p>Adapted from MG4 Simple Launcher's {@code ApkDownloader}. The two bugs worked around
 * here — the completion broadcast that goes missing, and the stale download record that
 * poisons the next attempt at the same path — were both found on the car, and the comments on
 * each say what they cost before they were understood. They are not speculative hardening.
 *
 * <p>What is new here is the request headers: the launcher fetches from a public catalogue,
 * while a browser is often downloading from behind a login, and {@link DownloadManager} runs
 * outside the WebView with none of its cookies.
 */
public class FileDownloader {

    private static final String TAG = "FileDownloader";
    /** Sub-directory of getExternalFilesDir(null); must match res/xml/file_paths.xml. */
    static final String SUBDIR = "downloads";
    private static final long POLL_INTERVAL_MS = 500;
    /** One retry after a dropped connection, then the user deserves to be told. */
    private static final int MAX_ATTEMPTS = 2;

    public interface Callback {
        void onProgress(int percent);

        void onComplete(@NonNull File file);

        void onFailed(@NonNull String reason);
    }

    private final Context appContext;
    private final DownloadManager downloadManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private long downloadId = -1;
    /** Kept so a failed attempt can be started again from scratch. */
    private String url;
    private String fileName;
    private String userAgent;
    private int attempt;
    /** Guards the one-shot ending: the poll and the broadcast both race to report it. */
    private boolean finished;
    private boolean running;
    private File targetFile;
    private Callback callback;
    private BroadcastReceiver completeReceiver;
    private Runnable poller;

    public FileDownloader(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.downloadManager =
                (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    /** True while a download is in flight. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Starts downloading {@code url} into the app's private downloads directory. Callbacks
     * run on the main thread.
     *
     * @param fileName what to call it on disk
     * @param userAgent the WebView's user agent, so a server that varies its answer by client
     *                  hands DownloadManager the same file it would have handed the page
     */
    public void start(@NonNull String url, @NonNull String fileName,
                      @Nullable String userAgent, @NonNull Callback callback) {
        this.callback = callback;
        this.url = url;
        this.fileName = fileName;
        this.userAgent = userAgent;
        this.running = true;
        attempt = 1;
        enqueue();
    }

    private void enqueue() {
        finished = false;
        File dir = new File(appContext.getExternalFilesDir(null), SUBDIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        targetFile = new File(dir, fileName);
        // A stale partial/previous download would shadow the fresh one.
        if (targetFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetFile.delete();
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDestinationInExternalFilesDir(appContext, null, SUBDIR + "/" + fileName)
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        // DownloadManager is a separate process with its own empty cookie jar. Without the
        // page's cookies, a file behind a login downloads as the login page itself — a few
        // kilobytes of HTML with an .apk name, which then fails to install for reasons that
        // look nothing like the actual cause.
        String cookie = CookieManager.getInstance().getCookie(url);
        if (!TextUtils.isEmpty(cookie)) {
            request.addRequestHeader("Cookie", cookie);
        }
        if (!TextUtils.isEmpty(userAgent)) {
            request.addRequestHeader("User-Agent", userAgent);
        }

        // Kept out of the system Downloads list: these land in app-private storage, which the
        // system Downloads app cannot open anyway, so listing them there offers the user a
        // row that does nothing when tapped.
        //noinspection deprecation
        request.setVisibleInDownloadsUi(false);

        // Sweep before fetching rather than only at startup: the app can sit open for a long
        // time on a head unit, and every install leaves its APK behind.
        clearDownloads(appContext);
        forgetPreviousDownloadsOf(targetFile);

        registerCompleteReceiver();
        downloadId = downloadManager.enqueue(request);
        Log.i(TAG, "downloading " + url + " to " + targetFile.getAbsolutePath()
                + ", free space " + (dir.getUsableSpace() / (1024 * 1024)) + " MB");
        startPolling();
    }

    /**
     * Drops any earlier download record aimed at the same file.
     *
     * <p>DownloadManager keeps finished downloads in its own database, destination and all.
     * Enqueue a new one onto a path an old record still claims and it decides it is resuming
     * that one: it asks the server to continue from where the old file ended, the server
     * answers with the whole thing, and the download dies as ERROR_CANNOT_RESUME (1008)
     * before a byte is kept. In a browser this bites the second time the same file name is
     * fetched — which, for someone reinstalling an APK to test it, is most of the time.
     */
    private void forgetPreviousDownloadsOf(@NonNull File file) {
        String wanted = Uri.fromFile(file).toString();
        try (Cursor c = downloadManager.query(new DownloadManager.Query())) {
            if (c == null) {
                return;
            }
            int idColumn = c.getColumnIndex(DownloadManager.COLUMN_ID);
            int uriColumn = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            while (c.moveToNext()) {
                String local = uriColumn >= 0 ? c.getString(uriColumn) : null;
                if (local != null && local.equals(wanted)) {
                    // Removes the record; the file itself was deleted just above.
                    downloadManager.remove(c.getLong(idColumn));
                }
            }
        } catch (Exception e) {
            // A query that fails leaves the old behaviour, which is no worse than before.
            Log.w(TAG, "could not clear previous downloads", e);
        }
    }

    private void registerCompleteReceiver() {
        completeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    onDownloadFinished();
                }
            }
        };
        ContextCompat.registerReceiver(appContext, completeReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED);
    }

    private void startPolling() {
        poller = new Runnable() {
            @Override
            public void run() {
                if (pollOnce()) {
                    return;
                }
                mainHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        mainHandler.post(poller);
    }

    /**
     * Reads progress and status in one query. Returns true when the download has ended, in
     * which case the ending has already been reported.
     *
     * <p>The end is detected here rather than left to {@code ACTION_DOWNLOAD_COMPLETE} alone.
     * That broadcast has been seen to go missing — on a download the manager retried after a
     * dropped connection, the file arrived complete and the notice never did, leaving the
     * dialog at 99% for ever. The poll is already running and holds the same truth.
     */
    private boolean pollOnce() {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor c = downloadManager.query(query)) {
            if (c == null || !c.moveToFirst()) {
                return false;
            }
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long total = c.getLong(c.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            long done = c.getLong(c.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            if (total > 0) {
                callback.onProgress((int) (done * 100L / total));
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL
                    || status == DownloadManager.STATUS_FAILED) {
                onDownloadFinished();
                return true;
            }
        }
        return false;
    }

    private void onDownloadFinished() {
        if (finished) {
            return;
        }
        finished = true;
        stopPolling();
        int status;
        int reason = 0;
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor c = downloadManager.query(query)) {
            if (c == null || !c.moveToFirst()) {
                fail("download record not found");
                return;
            }
            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_FAILED) {
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            }
        }
        unregisterReceiver();

        if (status == DownloadManager.STATUS_SUCCESSFUL && targetFile.exists()) {
            Log.i(TAG, "downloaded " + targetFile.length() + " bytes");
            running = false;
            callback.onProgress(100);
            callback.onComplete(targetFile);
        } else if (attempt < MAX_ATTEMPTS && isWorthRetrying(reason)) {
            // A dropped connection is the normal weather here: the car is often on a phone
            // hotspot, and a transfer of ten megabytes has plenty of time to be interrupted.
            // One clean retry costs nothing and saves the common case; anything beyond that
            // is a problem no amount of retrying will fix.
            attempt++;
            Log.i(TAG, "download failed (reason " + reason + "), attempt " + attempt
                    + " of " + MAX_ATTEMPTS);
            enqueue();
        } else {
            // Both halves matter: a successful status with no file at the expected path means
            // DownloadManager renamed it — it appends a suffix rather than overwriting —
            // while a failed status carries a reason code worth reading (1006 is out of
            // space, 1004 a truncated transfer, 1009 the destination already existing).
            fail("status=" + status + " reason=" + reason
                    + ", file " + (targetFile.exists() ? "present" : "missing")
                    + ", free space " + (targetFile.getParentFile() == null ? -1
                            : targetFile.getParentFile().getUsableSpace() / (1024 * 1024))
                    + " MB");
        }
    }

    /**
     * Whether a failure is the kind a second attempt can beat: a connection that dropped or a
     * resume the manager could not make sense of. Out of space or a missing file will fail
     * again just as surely, and retrying only makes the user wait twice for the same news.
     */
    private static boolean isWorthRetrying(int reason) {
        return reason == DownloadManager.ERROR_HTTP_DATA_ERROR
                || reason == DownloadManager.ERROR_CANNOT_RESUME
                || reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS
                || reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE;
    }

    private void fail(@NonNull String reason) {
        Log.w(TAG, "failed: " + reason);
        running = false;
        stopPolling();
        unregisterReceiver();
        callback.onFailed(reason);
    }

    private void stopPolling() {
        if (poller != null) {
            mainHandler.removeCallbacks(poller);
            poller = null;
        }
    }

    private void unregisterReceiver() {
        if (completeReceiver != null) {
            try {
                appContext.unregisterReceiver(completeReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
            completeReceiver = null;
        }
    }

    /** Downloads younger than this are assumed to belong to an in-progress install. */
    private static final long STALE_AGE_MS = 60 * 60 * 1000L;

    /**
     * Deletes stale files from the downloads directory to reclaim space. Files modified
     * within the last hour are left alone: the system installer reads the APK in its own task
     * after we hand it off, so a just-downloaded file may still be in use even across a
     * relaunch. Leftovers from earlier sessions are cleaned on a later run.
     */
    public static void clearDownloads(@NonNull Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            return;
        }
        File[] files = new File(base, SUBDIR).listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - STALE_AGE_MS;
        for (File file : files) {
            if (file.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    /** Cancels an in-flight download and releases resources. */
    public void cancel() {
        if (downloadId != -1) {
            downloadManager.remove(downloadId);
        }
        running = false;
        finished = true;
        stopPolling();
        unregisterReceiver();
    }
}
