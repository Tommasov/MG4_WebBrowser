package com.tommasov.mg4browser.download;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.Locale;

/**
 * Hands a downloaded APK to the system package installer.
 *
 * <p>Carried over from MG4 Simple Launcher, minus its checksum step: the launcher installs
 * from a catalogue that publishes a SHA-256 for every entry, and there is nothing to check a
 * file the user chose to download off the open web against.
 */
public final class ApkInstaller {

    private ApkInstaller() {
    }

    /** Whether {@code file} is an Android package, by declared type or by name. */
    public static boolean isApk(@NonNull File file, String mimeType) {
        if ("application/vnd.android.package-archive".equalsIgnoreCase(mimeType)) {
            return true;
        }
        // Servers that hand out APKs are not reliable about the content type — plenty send
        // application/octet-stream — so the file name gets a vote too.
        return file.getName().toLowerCase(Locale.US).endsWith(".apk");
    }

    /** True once the user has granted this app permission to install packages. */
    public static boolean canInstall(@NonNull Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * Sends the user to the system screen where they can allow this app to install unknown
     * apps. They return here afterwards; re-check {@link #canInstall}.
     */
    public static void requestInstallPermission(@NonNull Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:" + context.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * Launches the system installer for {@code apk}. Caller must have confirmed
     * {@link #canInstall} first. Returns false if this system has no installer to hand it to,
     * which a stripped head unit is exactly the sort of place to be missing.
     */
    public static boolean install(@NonNull Context context, @NonNull File apk) {
        Uri apkUri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            return false;
        }
        context.startActivity(intent);
        return true;
    }
}
