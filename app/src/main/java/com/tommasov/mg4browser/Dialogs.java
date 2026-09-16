package com.tommasov.mg4browser;

import android.content.Context;
import android.content.res.Configuration;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;

/**
 * Builds this app's dialogs and toasts at a size that can be read from the driver's seat.
 *
 * <p>The mechanism is lifted from MG4 Simple Launcher, where it was arrived at the hard way,
 * and the reasoning carries over unchanged. Android's dialogs are sized for a phone held at
 * arm's length; this screen is a metre away, behind a steering wheel. At the stock size the
 * install-permission explanation and the certificate warning — the two messages in this app
 * that actually ask the user to decide something — are legible only if you lean in.
 *
 * <p>The whole dialog is scaled through a {@link Configuration} rather than restyled piece by
 * piece, because one of the pieces cannot be restyled at all: AppCompat writes the message's
 * appearance into its own layout ({@code TextAppearance.AppCompat.Subhead}) instead of reading
 * it from a theme attribute, so a theme can enlarge the title and the buttons but never the
 * paragraph in the middle — the part that most needs it.
 *
 * <p>Only dialogs and toasts are scaled. The browser chrome is laid out by hand for this
 * screen and would not survive having its text grown underneath it.
 */
public final class Dialogs {

    /**
     * How much larger than the system's own setting. One number, in one place, because that
     * is how it will be retuned once someone reads a dialog in the actual car.
     */
    private static final float FONT_SCALE = 1.4f;

    private Dialogs() {
    }

    /** A toast at the same enlarged size as the dialogs. */
    public static void toast(@NonNull Context context, @StringRes int messageRes) {
        Toast.makeText(scaled(context), messageRes, Toast.LENGTH_LONG).show();
    }

    /** As above, for a message that is not a resource. */
    public static void toast(@NonNull Context context, @NonNull CharSequence message) {
        Toast.makeText(scaled(context), message, Toast.LENGTH_LONG).show();
    }

    /** An {@link AlertDialog.Builder} whose text is sized for the car. */
    @NonNull
    public static AlertDialog.Builder builder(@NonNull Context context) {
        return new AlertDialog.Builder(scaled(context));
    }

    /**
     * The scaled context to build dialog contents with.
     *
     * <p>The activity stays underneath: a dialog needs its window token, and a context made
     * with {@code createConfigurationContext} has none — it throws
     * {@code BadTokenException: token null is not valid} the moment it is shown. Overriding
     * the configuration on a {@link ContextThemeWrapper} keeps the activity and changes only
     * what is asked for: the override carries the font scale alone.
     *
     * <p>Multiplies whatever the system is set to rather than replacing it: a driver who has
     * already enlarged the head unit's font is asking for larger text, not for ours.
     */
    @NonNull
    public static Context scaled(@NonNull Context context) {
        Configuration override = new Configuration();
        override.fontScale = context.getResources().getConfiguration().fontScale * FONT_SCALE;
        ContextThemeWrapper wrapper = new ContextThemeWrapper(context, R.style.Theme_MG4Browser);
        // Must happen before anything reads resources from the wrapper.
        wrapper.applyOverrideConfiguration(override);
        return wrapper;
    }
}
