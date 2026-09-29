package com.galliard.christianprayer;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Native vibration plugin.
 *
 * This calls the Android Vibrator service directly, instead of going through
 * the WebView's navigator.vibrate() (which on some OEM builds, e.g. certain
 * Xiaomi/MIUI devices, silently succeeds at the Chrome/WebView layer while the
 * OS suppresses the actual hardware vibration). Native calls go straight to
 * the platform Vibrator API, which is the reliable path across OEM skins.
 *
 * CONFIRMED (via in-app diagnostics on a real Xiaomi/MIUI device, SDK 36):
 * a direct Vibrator.vibrate() call succeeds with no exception and
 * hasVibrator=true, yet the motor never actually turns on when the phone's
 * "Touch vibration" system toggle is off — regardless of the AudioAttributes
 * usage tag on the call. On this OEM skin, that toggle appears to gate the
 * Vibrator service itself for any direct app call, not just usage=touch.
 *
 * To route around that for the (infrequent) "remind me if I drift off"
 * feature, remindNotify() posts a real Android *notification* with a
 * vibration pattern attached to its channel instead of calling Vibrator
 * directly. Notification vibration goes through a different system pipeline
 * (the one governing calls/notifications, which many users — including on
 * this device — leave enabled even with touch vibration off). This is only
 * used for the drift-off reminder (fires at most once per idle pause), never
 * per-bead, so it can't spam the notification shade.
 */
@CapacitorPlugin(
    name = "Vibrate",
    permissions = {
        @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications")
    }
)
public class VibratePlugin extends Plugin {

    private static final AudioAttributes VIB_ATTRS = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .build();

    private static final String CHANNEL_ID = "prayer_reminder";
    private static final int NOTIF_ID = 4171;

    @Override
    public void load() {
        super.load();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Prayer reminder",
                    NotificationManager.IMPORTANCE_HIGH
                );
                ch.setDescription("Vibrating reminder if you pause mid-prayer");
                ch.enableVibration(true);
                ch.setVibrationPattern(new long[]{0, 150, 90, 150, 90, 260});
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Vibrator getVibrator() {
        Context ctx = getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        } else {
            return (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    /**
     * call.getData() expects either:
     *   { "duration": 40 }                     -> single pulse of 40ms
     *   { "pattern": [50, 80, 50, 80, 90] }     -> Web-Vibration-API-style pattern
     * Pattern semantics match navigator.vibrate(): alternating
     * [wait, vibrate, wait, vibrate, ...], starting with a wait of 0 if the
     * array has an even convention like the web API (we follow the same
     * convention used by the web app: first value is a vibrate duration).
     */
    @PluginMethod
    public void vibrate(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("sdkInt", Build.VERSION.SDK_INT);
        ret.put("manufacturer", Build.MANUFACTURER);

        Vibrator vibrator = getVibrator();
        boolean hasVibrator = vibrator != null && vibrator.hasVibrator();
        ret.put("hasVibrator", hasVibrator);
        if (vibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                ret.put("hasAmplitudeControl", vibrator.hasAmplitudeControl());
            } catch (Exception ignored) {}
        }

        if (!hasVibrator) {
            ret.put("ok", false);
            ret.put("reason", "no-vibrator-hardware");
            call.resolve(ret);
            return;
        }

        JSArray patternArr = call.getArray("pattern");
        Long duration = call.getLong("duration");

        try {
            if (patternArr != null && patternArr.length() > 0) {
                int len = patternArr.length();
                long[] timings = new long[len + 1];
                // Web Vibration API pattern = [vibrate, pause, vibrate, pause, ...]
                // Android waveform timings = [wait, vibrate, wait, vibrate, ...]
                timings[0] = 0;
                for (int i = 0; i < len; i++) {
                    timings[i + 1] = patternArr.getLong(i);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, -1), VIB_ATTRS);
                } else {
                    vibrator.vibrate(timings, -1, VIB_ATTRS);
                }
            } else {
                long d = duration != null ? duration : 40L;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE), VIB_ATTRS);
                } else {
                    vibrator.vibrate(d, VIB_ATTRS);
                }
            }
            ret.put("ok", true);
            ret.put("calledVibrate", true);
            call.resolve(ret);
        } catch (Exception e) {
            ret.put("ok", false);
            ret.put("calledVibrate", true);
            ret.put("error", String.valueOf(e.getMessage()));
            call.resolve(ret);
        }
    }

    /**
     * Ask for the runtime POST_NOTIFICATIONS permission (Android 13+ only;
     * a no-op resolve on older versions, where it's granted at install time).
     * The JS side calls this once, only when the user turns on the
     * "remind me if I drift off" setting, so we don't prompt on first launch
     * for a feature they may never use.
     */
    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        requestPermissionForAlias("notifications", call, "notificationPermCallback");
    }

    @PermissionCallback
    private void notificationPermCallback(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", getPermissionState("notifications") == PermissionState.GRANTED);
        call.resolve(ret);
    }

    /**
     * Fires the drift-off reminder as a real system notification with a
     * vibration pattern on its channel, instead of a direct Vibrator call.
     * See the class-level comment for why: this uses a different vibration
     * pipeline that, on at least one confirmed device, still works even when
     * the "touch vibration" toggle (which blocks direct Vibrator calls) is
     * off.
     */
    @PluginMethod
    public void remindNotify(PluginCall call) {
        JSObject ret = new JSObject();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            ret.put("ok", false);
            ret.put("reason", "no-notification-permission");
            call.resolve(ret);
            return;
        }

        String title = call.getString("title", "Christian Prayer");
        String text = call.getString("text", "");

        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(getContext(), CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setVibrate(new long[]{0, 150, 90, 150, 90, 260});
            NotificationManagerCompat.from(getContext()).notify(NOTIF_ID, builder.build());
            ret.put("ok", true);
        } catch (Exception e) {
            ret.put("ok", false);
            ret.put("error", String.valueOf(e.getMessage()));
        }
        call.resolve(ret);
    }
}
