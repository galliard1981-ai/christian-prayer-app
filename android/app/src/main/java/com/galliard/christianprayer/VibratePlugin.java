package com.galliard.christianprayer;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Native vibration plugin.
 *
 * This calls the Android Vibrator service directly, instead of going through
 * the WebView's navigator.vibrate() (which on some OEM builds, e.g. certain
 * Xiaomi/MIUI devices, silently succeeds at the Chrome/WebView layer while the
 * OS suppresses the actual hardware vibration). Native calls go straight to
 * the platform Vibrator API, which is the reliable path across OEM skins.
 */
@CapacitorPlugin(name = "Vibrate")
public class VibratePlugin extends Plugin {

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
        Vibrator vibrator = getVibrator();
        if (vibrator == null || !vibrator.hasVibrator()) {
            call.resolve();
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
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, -1));
                } else {
                    vibrator.vibrate(timings, -1);
                }
            } else {
                long d = duration != null ? duration : 40L;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(d);
                }
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("vibrate failed: " + e.getMessage());
        }
    }
}
