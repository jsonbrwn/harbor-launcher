package dev.harbor.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Makes the remote's Home button open Harbor on TVs where the built-in Google TV home screen
 * can't be replaced (it's a system app that always wins the Home role, and disabling it breaks
 * the remote's app buttons).
 *
 * Two hooks, both narrow:
 *  1. The Home key itself is caught and Harbor is opened instead.
 *  2. If the built-in home screen still comes to the front (for example when an app exits to
 *     it), Harbor is brought up over it. Only its home activity triggers this, so its other
 *     screens and the remote's Netflix/Prime/YouTube buttons keep working.
 */
public class HomeButtonService extends AccessibilityService {

    private static final String STOCK_LAUNCHER = "com.google.android.apps.tv.launcherx";
    private long lastLaunch;

    // ---- Force quit: press "Force stop" then "OK" on one app's Settings page, then go home.
    private static HomeButtonService instance;
    private static String forcePkg;
    private static long forceDeadline;
    private static int forceStep;      // 0 = find Force stop, 1 = confirm
    private static long lastClick;

    /** Starts a force stop for {@code pkg}; returns false if the helper isn't running. */
    static boolean beginForceStop(String pkg) {
        if (instance == null) return false;
        forcePkg = pkg;
        forceStep = 0;
        forceDeadline = SystemClock.elapsedRealtime() + 12000;
        instance.main.removeCallbacks(instance.poll);
        instance.main.postDelayed(instance.poll, 600);
        return true;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    private void handleForceStop(AccessibilityEvent event) {
        step();
    }

    /** Polls while a force stop is pending, since some TCL screens send few accessibility events. */
    private final Runnable poll = new Runnable() {
        public void run() {
            if (forcePkg == null) return;
            step();
            if (forcePkg != null) main.postDelayed(this, 400);
        }
    };

    private void step() {
        if (forcePkg == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now > forceDeadline) { forcePkg = null; return; }
        if (now - lastClick < 700) return; // let the screen settle between presses
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence rootPkg = root.getPackageName();
        if (rootPkg != null && (rootPkg.toString().equals(getPackageName()) || rootPkg.toString().equals(forcePkg))) return;
        if (forceStep == 0) {
            AccessibilityNodeInfo n = findByText(root, "Force stop", false);
            if (n == null) return;
            AccessibilityNodeInfo target = clickable(n);
            if (target == null) target = n;
            if (!target.isEnabled() || !n.isEnabled()) { finishForceStop(); return; } // already stopped
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                forceStep = 1;
                lastClick = now;
            }
        } else {
            // TCL shows the confirmation as a full-screen page ("If you force stop an app..." OK / Cancel).
            AccessibilityNodeInfo ok = findByText(root, "OK", true);
            AccessibilityNodeInfo target = ok == null ? null : clickable(ok);
            if (target == null) target = ok;
            if (target == null && findByText(root, "force stop an app", false) != null) {
                target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT); // OK has focus by default
            }
            if (target == null) {
                // Still on the App info page and the button went grey: the app is stopped.
                AccessibilityNodeInfo fs = findByText(root, "Force stop", false);
                if (fs != null && !fs.isEnabled()) finishForceStop();
                return;
            }
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastClick = now;
                finishForceStop();
            }
        }
    }

    private void finishForceStop() {
        forcePkg = null;
        main.postDelayed(new Runnable() {
            public void run() { openHarbor(); }
        }, 600);
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    private static AccessibilityNodeInfo findByText(AccessibilityNodeInfo root, String text, boolean exact) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(text);
        if (list == null) return null;
        for (AccessibilityNodeInfo n : list) {
            CharSequence t = n.getText();
            if (t == null) t = n.getContentDescription();
            if (t == null) continue;
            String s = t.toString().trim();
            if (exact ? s.equalsIgnoreCase(text) : s.toLowerCase(java.util.Locale.US).contains(text.toLowerCase(java.util.Locale.US))) return n;
        }
        return null;
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        for (int i = 0; n != null && i < 6; i++) {
            if (n.isClickable()) return n;
            n = n.getParent();
        }
        return null;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHarbor();
        return true; // consume both down and up so the stock home screen never sees it
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        handleForceStop(event);
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null || !STOCK_LAUNCHER.contentEquals(pkg)) return;
        String c = cls.toString();
        // Only the stock home screen itself, not its other screens or trampolines.
        if (c.endsWith(".HomeActivity") || c.endsWith(".MainActivity") || c.endsWith(".LauncherActivity")) {
            openHarbor();
        }
    }

    private void openHarbor() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < 400) return; // key + window event can arrive together
        lastLaunch = now;
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(this, MainActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onInterrupt() { }

    static boolean isEnabled(Context c) {
        String flat = new ComponentName(c, HomeButtonService.class).flattenToString();
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(list)) return false;
        TextUtils.SimpleStringSplitter s = new TextUtils.SimpleStringSplitter(':');
        s.setString(list);
        while (s.hasNext()) {
            String item = s.next();
            if (item.equalsIgnoreCase(flat)) return true;
            ComponentName cn = ComponentName.unflattenFromString(item);
            if (cn != null && cn.equals(new ComponentName(c, HomeButtonService.class))) return true;
        }
        return false;
    }
}
