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

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHarbor();
        return true; // consume both down and up so the stock home screen never sees it
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
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
