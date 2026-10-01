package app.so7o.ztrackpad.lite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Lets the pad be driven and inspected from a script instead of by tapping:
 *
 *   adb shell am broadcast -n app.so7o.ztrackpad.lite/.ControlReceiver \
 *       -a app.so7o.ztrackpad.lite.CONTROL --es op status
 *   adb shell am broadcast -n app.so7o.ztrackpad.lite/.ControlReceiver \
 *       -a app.so7o.ztrackpad.lite.CONTROL --es op lock --es arg on
 *
 * `arg` carries an op's argument (op=lock takes on|off|toggle).
 *
 * All the real work belongs to the running accessibility service, since it owns the
 * overlay windows, so this is just a thin entry point. If the service is not connected
 * the broadcast fails with a clear message rather than appearing to succeed.
 *
 * The `-n` component is mandatory: an implicit broadcast never reaches a manifest-declared
 * receiver on API 26+, and the failure is silent.
 *
 * SECURITY: exported with no permission, so any app on the device can read the pad's
 * state and toggle the lock. The worst case is a pad that will not move, and gating it
 * would make the adb one-liner above awkward.
 */
public class ControlReceiver extends BroadcastReceiver {

    public static final String ACTION = "app.so7o.ztrackpad.lite.CONTROL";

    /** Set by TrackpadService while it is connected, cleared when it goes away. */
    static volatile TrackpadService service;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;

        String op = intent.getStringExtra("op");
        // an op's argument, for op=lock (on|off|toggle)
        String arg = intent.getStringExtra("arg");

        // onReceive for a manifest receiver runs on the main thread, which is required:
        // the lock path touches the pad's windows.
        TrackpadService s = service;
        if (s == null) {
            setResultCode(1);
            setResultData("error: accessibility service not connected");
            return;
        }

        String out;
        try {
            out = s.controlCommand(op, arg);
        } catch (Throwable t) {
            out = "error: " + t;
        }
        setResultCode(out.startsWith("error") ? 1 : 0);
        setResultData(out);
    }
}
