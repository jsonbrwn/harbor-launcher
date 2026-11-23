package dev.harbor.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Harbor: a minimal, D-pad friendly home screen for Android TV / Google TV with a desktop-style
 * layout: a translucent menu bar with a clock at the top, a soft blurred wallpaper, and a
 * magnifying dock of apps at the bottom. Apps that are not in the dock live in the Apps grid.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "harbor";
    private static final String KEY_PINS = "pins";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_HIDDEN_SEEDED = "hidden_seeded";
    /** Apps hidden from Harbor on first run, matched by their label. */
    private static final String[] DEFAULT_HIDDEN_LABELS = {"TCL Home", "TV", "Live TV"};
    private static final String KEY_WALLPAPER = "wallpaper_index";
    private static final String KEY_WALLPAPER_AT = "wallpaper_changed_at";
    private static final String KEY_ROTATE = "wallpaper_rotate";
    private static final long ROTATE_MS = 10 * 60 * 1000L;
    private final android.os.Handler rotationHandler = new android.os.Handler();
    private boolean resumed;

    /** Apps pinned to the dock on first run, when installed. */
    private static final String[] DEFAULT_PINS = {
            "com.netflix.ninja",                    // Netflix
            "com.amazon.amazonvideo.livingroom",    // Prime Video
            "com.google.android.youtube.tv",        // YouTube
    };

    /** Packages that commonly host the antenna / Live TV experience, tried in order. */
    private static final String[] LIVE_TV_PACKAGES = {
            "com.tcl.tv",
            "com.google.android.tv",
            "com.android.tv",
            "com.google.android.apps.tv.launcherx",
    };

    private static final int ACCENT = 0xFF3D8BFF;

    private SharedPreferences prefs;
    private final Map<String, AppEntry> installed = new LinkedHashMap<>();
    private final List<String> pins = new ArrayList<>();

    private FrameLayout root;
    private WallpaperView wallpaper;
    private LinearLayout menuBar;
    private View desktopClock;
    private FrameLayout dockFrame;
    private LinearLayout dockRow;
    private TextView tooltip;
    private TextView inputsMenuItem;
    private TextView homeSetupItem;

    private View overlay;           // open panel or Apps grid, if any
    private boolean overlayIsGrid;
    private View focusBeforeOverlay;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadApps();
            rebuildDock(-1);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.contains(KEY_WALLPAPER_AT)) {
            prefs.edit().putLong(KEY_WALLPAPER_AT, System.currentTimeMillis()).apply();
        }
        buildUi();
        reloadApps();
        loadPins();
        rebuildDock(0);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        scheduleRotation();
        hideSystemUi();
        if (homeSetupItem != null) {
            boolean needed = !HomeButtonService.isEnabled(this) && !isDefaultHome();
            homeSetupItem.setVisibility(needed ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        rotationHandler.removeCallbacks(rotateTick);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pressing Home while already home: close whatever is open and go back to the dock.
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            closeOverlay();
            focusDock(0);
        }
    }

    @Override
    public void onBackPressed() {
        // A launcher never finishes on Back; it only closes panels.
        if (overlay != null) closeOverlay();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The Menu / Options key acts like a long press on the focused tile.
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
            View f = getCurrentFocus();
            if (f instanceof TileView) {
                f.performLongClick();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) { return Ui.dp(this, v); }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        wallpaper = new WallpaperView(this, prefs.getInt(KEY_WALLPAPER, 0));
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));

        desktopClock = buildDesktopClock();
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        clp.topMargin = dp(96);
        root.addView(desktopClock, clp);

        menuBar = buildMenuBar();
        root.addView(menuBar, new FrameLayout.LayoutParams(-1, dp(30), Gravity.TOP));
        menuBar.setVisibility(View.GONE); // removed by request; wallpaper options live on the Settings tile

        dockFrame = new FrameLayout(this);
        dockFrame.setClipChildren(false);
        dockFrame.setClipToPadding(false);
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.BOTTOM);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(10), dp(8), dp(10), dp(2));
        dockRow.setBackground(Ui.frosted(this, 0x38FFFFFF, 0x59FFFFFF, 20));
        dockFrame.addView(dockRow, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = dp(18);
        root.addView(dockFrame, dlp);

        tooltip = new TextView(this);
        tooltip.setTextColor(0xFFFFFFFF);
        tooltip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tooltip.setPadding(dp(10), dp(3), dp(10), dp(4));
        tooltip.setBackground(Ui.frosted(this, 0xCC26262C, 0x40FFFFFF, 7));
        tooltip.setAlpha(0f);
        root.addView(tooltip, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));

        setContentView(root);
    }

    private View buildDesktopClock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setTextColor(0xF2FFFFFF);
        time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 76);
        time.setTypeface(assetFont("fonts/clock.ttf", Typeface.create("sans-serif-thin", Typeface.NORMAL)));
        time.getPaint().setTextSkewX(CLOCK_SKEW);
        time.setShadowLayer(dp(10), 0, dp(2), 0x55000000);
        box.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        date.setTextColor(0xD9FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        date.setLetterSpacing(0.04f);
        date.setTypeface(assetFont("fonts/date.ttf", Typeface.create("sans-serif-light", Typeface.NORMAL)));
        date.getPaint().setTextSkewX(CLOCK_SKEW);
        if (CLOCK_UPPERCASE_DATE) date.setAllCaps(true);
        date.setShadowLayer(dp(6), 0, dp(1), 0x55000000);
        box.addView(date);
        return box;
    }

    /** Clock style: fonts come from assets/fonts (SIL Open Font License). */
    private static final float CLOCK_SKEW = 0f;
    private static final boolean CLOCK_UPPERCASE_DATE = true;

    private Typeface assetFont(String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(getAssets(), path);
        } catch (Exception e) {
            return fallback;
        }
    }

    private LinearLayout buildMenuBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), 0, dp(16), 0);
        bar.setBackground(new ColorDrawable(0x52121218));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(17), dp(17));
        llp.rightMargin = dp(8);
        bar.addView(logo, llp);

        TextView name = barText("Harbor", true);
        bar.addView(name);

        bar.addView(menuItem("Apps", new Runnable() { public void run() { openAppsGrid(); } }));
        inputsMenuItem = menuItem("Inputs", new Runnable() { public void run() { openInputs(inputsMenuItem, false); } });
        bar.addView(inputsMenuItem);
        final TextView[] wp = new TextView[1];
        wp[0] = menuItem("Wallpaper", new Runnable() { public void run() { openWallpaperMenu(wp[0]); } });
        bar.addView(wp[0]);
        bar.addView(menuItem("Settings", new Runnable() { public void run() { openSettings(); } }));
        homeSetupItem = menuItem("Set up Home button", new Runnable() { public void run() { openHomeButtonSetup(); } });
        homeSetupItem.setTextColor(0xFFFFD27A);
        bar.addView(homeSetupItem);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("EEE MMM d   h:mm a");
        clock.setFormat24Hour("EEE MMM d   H:mm");
        clock.setTextColor(0xF2FFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        bar.addView(clock);
        return bar;
    }

    private TextView barText(String s, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xF2FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setPadding(dp(8), dp(2), dp(8), dp(3));
        return t;
    }

    private TextView menuItem(String s, final Runnable action) {
        TextView t = barText(s, false);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(focusBackground(5));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    private Drawable focusBackground(float radius) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.pill(this, ACCENT, radius));
        s.addState(new int[]{android.R.attr.state_pressed}, Ui.pill(this, ACCENT, radius));
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    // ------------------------------------------------------------------ apps

    private void reloadApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = new ArrayList<>();
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent normal = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        found.addAll(pm.queryIntentActivities(leanback, 0));
        found.addAll(pm.queryIntentActivities(normal, 0));

        Map<String, AppEntry> byPkg = new LinkedHashMap<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || byPkg.containsKey(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            Drawable icon;
            try { icon = ri.loadIcon(pm); } catch (Exception e) { icon = null; }
            byPkg.put(pkg, new AppEntry(pkg, label == null ? pkg : label.toString(), icon, TileView.GLYPH_NONE));
        }
        List<AppEntry> list = new ArrayList<>(byPkg.values());
        Collections.sort(list, new Comparator<AppEntry>() {
            public int compare(AppEntry a, AppEntry b) { return a.label.compareToIgnoreCase(b.label); }
        });
        java.util.Set<String> hidden = new java.util.HashSet<>(prefs.getStringSet(KEY_HIDDEN, new java.util.HashSet<String>()));
        if (!prefs.getBoolean(KEY_HIDDEN_SEEDED, false)) {
            for (AppEntry e : list) {
                for (String l : DEFAULT_HIDDEN_LABELS) if (l.equalsIgnoreCase(e.label.trim())) hidden.add(e.key);
            }
            prefs.edit().putStringSet(KEY_HIDDEN, hidden).putBoolean(KEY_HIDDEN_SEEDED, true).apply();
        }
        installed.clear();
        for (AppEntry e : list) if (!hidden.contains(e.key)) installed.put(e.key, e);
    }

    private int hiddenCount() {
        return prefs.getStringSet(KEY_HIDDEN, new java.util.HashSet<String>()).size();
    }

    private void hideApp(String pkg) {
        java.util.Set<String> hidden = new java.util.HashSet<>(prefs.getStringSet(KEY_HIDDEN, new java.util.HashSet<String>()));
        hidden.add(pkg);
        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
        pins.remove(pkg);
        savePins();
        reloadApps();
        rebuildDock(-1);
    }

    private void unhideAll() {
        prefs.edit().putStringSet(KEY_HIDDEN, new java.util.HashSet<String>()).apply();
        reloadApps();
        toast("Hidden apps are back in Apps");
    }

    private AppEntry entryFor(String key) {
        if (AppEntry.APPS.equals(key)) return new AppEntry(key, "Apps", null, TileView.GLYPH_APPS);
        if (AppEntry.INPUTS.equals(key)) return new AppEntry(key, "Inputs", null, TileView.GLYPH_INPUTS);
        if (AppEntry.SETTINGS.equals(key)) return new AppEntry(key, "Settings", null, TileView.GLYPH_SETTINGS);
        if (AppEntry.LIVETV.equals(key)) return new AppEntry(key, "Live TV", null, TileView.GLYPH_LIVETV);
        return installed.get(key);
    }

    private void loadPins() {
        pins.clear();
        String saved = prefs.getString(KEY_PINS, null);
        if (saved == null) {
            for (String p : DEFAULT_PINS) if (p.startsWith("@") || installed.containsKey(p)) pins.add(p);
            savePins();
        } else if (saved.length() > 0) {
            for (String p : saved.split(",")) if (p.length() > 0 && !AppEntry.LIVETV.equals(p)) pins.add(p);
            if (!TextUtils.join(",", pins).equals(saved)) savePins();
        }
    }

    private void savePins() {
        prefs.edit().putString(KEY_PINS, TextUtils.join(",", pins)).apply();
    }

    private void launch(AppEntry e, View from) {
        switch (e.key) {
            case AppEntry.APPS: openAppsGrid(); return;
            case AppEntry.INPUTS: openInputs(from, true); return;
            case AppEntry.SETTINGS: openSettings(); return;
            case AppEntry.LIVETV: openLiveTv(from); return;
            default: launchPackage(e.key);
        }
    }

    private boolean launchPackage(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return start(i);
    }

    private boolean start(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException ex) {
            return false;
        }
    }

    private void openSettings() {
        closeOverlay();
        if (!start(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            toast("Settings could not be opened");
        }
    }

    /** True when the system already sends Home to Harbor (no accessibility service needed). */
    private boolean isDefaultHome() {
        try {
            ResolveInfo ri = getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    private void openHomeButtonSetup() {
        closeOverlay();
        toast("Turn on \"Harbor Home button\" so the remote's Home button opens Harbor");
        if (!start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            openSettings();
        }
    }

    private List<TvInputInfo> tvInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return out;
            for (TvInputInfo info : tim.getTvInputList()) {
                if (isHiddenInput(info)) continue;
                out.add(info);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** TvInputInfo.isHidden(Context) is public from API 24; call it reflectively. */
    private boolean isHiddenInput(TvInputInfo info) {
        try {
            Object r = TvInputInfo.class.getMethod("isHidden", Context.class).invoke(info, this);
            return r instanceof Boolean && (Boolean) r;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean openTvInput(TvInputInfo info) {
        Intent i = info.isPassthroughInput()
                ? new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()))
                : new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(info.getId()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return start(i);
    }

    private void openLiveTv(View from) {
        // 1. The TV's own tuner input (antenna / cable), if it exposes one.
        for (TvInputInfo info : tvInputs()) {
            if (info.getType() == TvInputInfo.TYPE_TUNER && !info.isPassthroughInput() && openTvInput(info)) return;
        }
        // 2. A known Live TV app.
        for (String pkg : LIVE_TV_PACKAGES) {
            if (launchPackage(pkg)) return;
        }
        // 3. Whatever handles the generic channel list.
        Intent any = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (start(any)) return;
        // 4. Let the user pick an input instead.
        toast("Live TV app not found. Pick an input, or pin your TV app from Apps.");
        openInputs(from, true);
    }

    // ------------------------------------------------------------------ dock

    private void rebuildDock(int focusIndex) {
        if (focusIndex < 0) {
            View f = getCurrentFocus();
            focusIndex = (f != null && f.getParent() == dockRow) ? dockRow.indexOfChild(f) : -1;
        }
        dockRow.removeAllViews();
        List<AppEntry> items = new ArrayList<>();
        for (String p : pins) {
            AppEntry e = entryFor(p);
            if (e != null) items.add(e);
        }
        int size = dp(60);
        for (AppEntry e : items) dockRow.addView(dockTile(e, size), dockParams(size));

        View sep = new View(this);
        sep.setBackgroundColor(0x55FFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(48));
        slp.leftMargin = dp(8);
        slp.rightMargin = dp(8);
        slp.bottomMargin = dp(14);
        dockRow.addView(sep, slp);

        dockRow.addView(dockTile(entryFor(AppEntry.INPUTS), size), dockParams(size));
        dockRow.addView(dockTile(entryFor(AppEntry.SETTINGS), size), dockParams(size));
        dockRow.addView(dockTile(entryFor(AppEntry.APPS), size), dockParams(size));

        if (focusIndex >= 0 && overlay == null) focusDock(focusIndex);
    }

    private LinearLayout.LayoutParams dockParams(int size) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size + dp(10));
        lp.leftMargin = dp(7);
        lp.rightMargin = dp(7);
        return lp;
    }

    private TileView dockTile(final AppEntry e, int size) {
        final TileView t = new TileView(this, e, false);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { launch(e, v); }
        });
        t.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { showDockActions(t); return true; }
        });
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (has) magnify(v);
                else v.post(new Runnable() { public void run() { if (!dockHasFocus()) unmagnify(); } });
            }
        });
        return t;
    }

    private boolean dockHasFocus() {
        View f = getCurrentFocus();
        return f != null && f.getParent() == dockRow;
    }

    private void focusDock(final int index) {
        dockRow.post(new Runnable() {
            public void run() {
                int i = Math.max(0, Math.min(index, dockRow.getChildCount() - 1));
                View v = dockRow.getChildAt(i);
                if (v != null && !v.isFocusable()) v = dockRow.getChildAt(Math.max(0, i - 1));
                if (v != null) v.requestFocus();
            }
        });
    }

    /** Desktop-style magnification: the focused tile grows the most, its neighbours a little. */
    private void magnify(View focused) {
        int fi = dockRow.indexOfChild(focused);
        float focusedScale = 1f;
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View c = dockRow.getChildAt(i);
            if (!(c instanceof TileView)) continue;
            int d = Math.abs(i - fi);
            // the separator doesn't count as a neighbour step
            if (d > 0 && betweenHasSeparator(i, fi)) d = Math.max(1, d - 1);
            float s = d == 0 ? 1.32f : d == 1 ? 1.12f : d == 2 ? 1.04f : 1f;
            if (d == 0) focusedScale = s;
            c.setPivotX(c.getWidth() / 2f);
            c.setPivotY(c.getHeight());
            c.animate().scaleX(s).scaleY(s).setDuration(160).start();
            ((TileView) c).setDot(d == 0);
        }
        showTooltip((TileView) focused, focusedScale);
    }

    private boolean betweenHasSeparator(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        for (int i = lo + 1; i < hi; i++) if (!(dockRow.getChildAt(i) instanceof TileView)) return true;
        return false;
    }

    private void unmagnify() {
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View c = dockRow.getChildAt(i);
            c.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
            if (c instanceof TileView) ((TileView) c).setDot(false);
        }
        tooltip.animate().alpha(0f).setDuration(120).start();
    }

    private void showTooltip(final TileView tile, final float scale) {
        tooltip.setText(tile.entry.label);
        tooltip.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                tooltip.getViewTreeObserver().removeOnPreDrawListener(this);
                positionTooltip(tile, scale);
                return true;
            }
        });
        tooltip.requestLayout();
        tooltip.invalidate();
    }

    private void positionTooltip(TileView tile, float scale) {
        if (tile.getParent() != dockRow) return;
        float cx = dockFrame.getLeft() + dockRow.getLeft() + tile.getLeft() + tile.getWidth() / 2f;
        float top = dockFrame.getTop() + dockRow.getTop() + tile.getTop() - tile.getHeight() * (scale - 1f);
        tooltip.setX(cx - tooltip.getWidth() / 2f);
        tooltip.setY(top - tooltip.getHeight() - dp(4));
        tooltip.animate().alpha(1f).setDuration(140).start();
    }

    private void showDockActions(final TileView tile) {
        final AppEntry e = tile.entry;
        final int pinIndex = pins.indexOf(e.key);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Open");
        actions.add(new Runnable() { public void run() { launch(e, tile); } });
        if (pinIndex >= 0) {
            if (pinIndex > 0) {
                labels.add("Move left");
                actions.add(new Runnable() { public void run() { movePin(pinIndex, -1); } });
            }
            if (pinIndex < pins.size() - 1) {
                labels.add("Move right");
                actions.add(new Runnable() { public void run() { movePin(pinIndex, 1); } });
            }
            if (!e.isBuiltIn()) {
                labels.add("Force quit");
                actions.add(new Runnable() { public void run() { forceQuit(e); } });
            }
            labels.add("Remove from Dock");
            actions.add(new Runnable() {
                public void run() {
                    pins.remove(pinIndex);
                    savePins();
                    rebuildDock(Math.max(0, pinIndex - 1));
                }
            });
        }
        if (AppEntry.SETTINGS.equals(e.key)) {
            labels.add("Wallpaper\u2026");
            actions.add(new Runnable() { public void run() { openWallpaperMenu(tile); } });
            if (hiddenCount() > 0) {
                labels.add("Show hidden apps");
                actions.add(new Runnable() { public void run() { unhideAll(); } });
            }
            if (!HomeButtonService.isEnabled(this) && !isDefaultHome()) {
                labels.add("Set up Home button");
                actions.add(new Runnable() { public void run() { openHomeButtonSetup(); } });
            }
        }
        float cx = dockFrame.getLeft() + dockRow.getLeft() + tile.getLeft() + tile.getWidth() / 2f;
        float top = dockFrame.getTop() + dockRow.getTop() + tile.getTop() - tile.getHeight() * 0.32f;
        showMenu(e.label, labels, actions, cx, top - dp(8), true, true, -1);
    }

    /**
     * Stops an app. Ends its background processes right away, then, if the Harbor helper
     * (accessibility service) is on, opens the app's Settings page and presses Force stop + OK.
     */
    private void forceQuit(AppEntry e) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(e.key);
        } catch (Exception ignored) { }
        if (HomeButtonService.beginForceStop(e.key)) {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + e.key))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            if (start(i)) {
                toast("Force quitting " + e.label + "\u2026");
                return;
            }
        }
        toast(e.label + " closed");
    }

    private void movePin(int index, int delta) {
        int to = index + delta;
        if (to < 0 || to >= pins.size()) return;
        Collections.swap(pins, index, to);
        savePins();
        rebuildDock(to);
    }

    // ------------------------------------------------------------------ panels

    /**
     * Shows a small translucent menu. If {@code above} is true the menu sits above (x, y) and is
     * centred on x; otherwise it drops down from (x, y) with its left edge at x.
     */
    private void showMenu(String title, List<String> labels, final List<Runnable> actions,
                          final float x, final float y, final boolean above, boolean centered, int checked) {
        java.util.Set<Integer> set = new java.util.HashSet<>();
        if (checked >= 0) set.add(checked);
        showMenu(title, labels, actions, x, y, above, centered, set, checked);
    }

    /** Long menus scroll; {@code checked} rows get a check mark and {@code focusRow} gets focus. */
    private void showMenu(String title, List<String> labels, final List<Runnable> actions,
                          final float x, final float y, final boolean above, boolean centered,
                          java.util.Set<Integer> checked, int focusRow) {
        closeOverlay();
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5), dp(5), dp(5), dp(5));
        panel.setBackground(Ui.frosted(this, 0xE62A2A31, 0x40FFFFFF, 9));
        panel.setElevation(dp(12));
        panel.setMinimumWidth(dp(190));

        if (title != null) {
            TextView h = new TextView(this);
            h.setText(title);
            h.setTextColor(0x99FFFFFF);
            h.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            h.setPadding(dp(10), dp(4), dp(10), dp(4));
            panel.addView(h);
        }
        final ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rowsBox, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        View first = null;
        for (int i = 0; i < labels.size(); i++) {
            final int idx = i;
            TextView row = new TextView(this);
            row.setText((checked.contains(i) ? "✓  " : "     ") + labels.get(i));
            row.setTextColor(0xFFFFFFFF);
            row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            row.setPadding(dp(10), dp(6), dp(16), dp(7));
            row.setFocusable(true);
            row.setClickable(true);
            row.setBackground(focusBackground(5));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    closeOverlay();
                    actions.get(idx).run();
                }
            });
            rowsBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (first == null || focusRow == i) first = row;
        }

        panel.setAlpha(0f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        root.addView(panel, lp);
        final boolean center = centered;
        panel.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                int avail = (int) (above ? y - dp(8) : root.getHeight() - y - dp(8));
                if (avail > dp(80) && panel.getHeight() > avail) {
                    ViewGroup.LayoutParams slp = scroll.getLayoutParams();
                    slp.height = avail - (panel.getHeight() - scroll.getHeight());
                    scroll.setLayoutParams(slp);
                    return false; // lay out again at the capped height
                }
                panel.getViewTreeObserver().removeOnPreDrawListener(this);
                float px = center ? x - panel.getWidth() / 2f : x;
                px = Math.max(dp(8), Math.min(px, root.getWidth() - panel.getWidth() - dp(8)));
                float py = above ? y - panel.getHeight() : y;
                panel.setX(px);
                panel.setY(Math.max(dp(8), py));
                panel.animate().alpha(1f).setDuration(120).start();
                return true;
            }
        });
        desktopClock.animate().alpha(0f).setDuration(120).start(); // keep pop-ups from sitting on the date
        openOverlay(panel, false, first);
    }

    /** The five inputs Jason uses, in this order. */
    private static final String[] INPUT_NAMES = {"HDMI 1", "HDMI 2", "HDMI 3", "Antenna TV", "AV"};

    private void openInputs(View anchor, boolean fromDock) {
        final TvInputInfo[] mapped = mapInputs(tvInputs());
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (int i = 0; i < INPUT_NAMES.length; i++) {
            final TvInputInfo info = mapped[i];
            final boolean antenna = i == 3;
            labels.add(INPUT_NAMES[i]);
            actions.add(new Runnable() {
                public void run() {
                    if (info != null && openTvInput(info)) return;
                    if (antenna) {
                        for (String pkg : LIVE_TV_PACKAGES) if (launchPackage(pkg)) return;
                    }
                    toast("That input could not be opened");
                }
            });
        }
        float cx, top;
        if (anchor != null && anchor.getParent() == dockRow) {
            cx = dockFrame.getLeft() + dockRow.getLeft() + anchor.getLeft() + anchor.getWidth() / 2f;
            top = dockFrame.getTop() + dockRow.getTop() + anchor.getTop() - anchor.getHeight() * 0.32f - dp(8);
        } else {
            cx = root.getWidth() / 2f;
            top = root.getHeight() - dp(120);
        }
        showMenu("Inputs", labels, actions, cx, top, true, true, -1);
    }

    /**
     * Matches the TV's reported inputs to HDMI 1-3, Antenna TV and AV. Devices hanging off an HDMI
     * port (CEC) and tuner inputs from streaming apps are ignored.
     */
    private TvInputInfo[] mapInputs(List<TvInputInfo> all) {
        TvInputInfo[] out = new TvInputInfo[INPUT_NAMES.length];
        List<TvInputInfo> hdmi = new ArrayList<>();
        TvInputInfo bestTuner = null;
        int bestScore = -1;
        PackageManager pm = getPackageManager();
        for (TvInputInfo info : all) {
            if (info.getParentId() != null) continue;
            String label = String.valueOf(info.loadLabel(this));
            String l = label.toLowerCase(java.util.Locale.US);
            String pkg = info.getServiceInfo() != null ? info.getServiceInfo().packageName : "";
            android.util.Log.i("Harbor", "input id=" + info.getId() + " type=" + info.getType() + " label=" + label
                    + " passthrough=" + info.isPassthroughInput() + " pkg=" + pkg);
            int type = info.getType();
            if (type == TvInputInfo.TYPE_HDMI) {
                hdmi.add(info);
            } else if (type == TvInputInfo.TYPE_COMPOSITE || l.equals("av") || l.contains("composite")) {
                if (out[4] == null) out[4] = info;
            } else if (type == TvInputInfo.TYPE_TUNER && !info.isPassthroughInput()) {
                int score = 0;
                if (l.contains("antenna") || l.contains("air") || l.contains("atv") || l.contains("dtv")
                        || l.contains("broadcast") || l.equals("tv") || l.contains("channel")) score += 3;
                String pl = pkg.toLowerCase(java.util.Locale.US);
                if (pl.contains("tcl") || pl.contains("mtk") || pl.contains("mediatek") || pl.contains("realtek")
                        || pl.contains("tvinput") || pl.contains("tuner")) score += 2;
                try {
                    if ((pm.getApplicationInfo(pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) score += 1;
                    else score -= 5; // downloaded apps' channel sources are not the antenna
                } catch (Exception ignored) { }
                if (score > bestScore) { bestScore = score; bestTuner = info; }
            }
        }
        if (bestScore >= 0) out[3] = bestTuner;
        // HDMI n: prefer a label that names the port number, else report order.
        boolean[] used = new boolean[hdmi.size()];
        for (int n = 1; n <= 3; n++) {
            for (int j = 0; j < hdmi.size(); j++) {
                String l = String.valueOf(hdmi.get(j).loadLabel(this)).replace(" ", "").toLowerCase(java.util.Locale.US);
                if (!used[j] && l.contains("hdmi" + n)) { out[n - 1] = hdmi.get(j); used[j] = true; break; }
            }
        }
        for (int n = 1; n <= 3; n++) {
            if (out[n - 1] != null) continue;
            for (int j = 0; j < hdmi.size(); j++) {
                if (!used[j]) { out[n - 1] = hdmi.get(j); used[j] = true; break; }
            }
        }
        return out;
    }

    private void openWallpaperMenu(View anchor) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        java.util.Set<Integer> checked = new java.util.HashSet<>();
        final boolean rotating = prefs.getBoolean(KEY_ROTATE, true);
        labels.add("Change every 10 minutes");
        if (rotating) checked.add(0);
        actions.add(new Runnable() {
            public void run() {
                prefs.edit().putBoolean(KEY_ROTATE, !rotating).apply();
                scheduleRotation();
                toast(!rotating ? "Wallpaper will change every 10 minutes" : "Wallpaper rotation off");
            }
        });
        labels.add("Next wallpaper");
        actions.add(new Runnable() { public void run() { setWallpaper(wallpaper.getIndex() + 1); } });
        int offset = labels.size();
        for (int i = 0; i < wallpaper.count(); i++) {
            final int idx = i;
            labels.add(wallpaper.nameOf(i));
            if (i == wallpaper.getIndex()) checked.add(offset + i);
            actions.add(new Runnable() { public void run() { setWallpaper(idx); } });
        }
        if (anchor != null && anchor.getParent() == dockRow) {
            float cx = dockFrame.getLeft() + dockRow.getLeft() + anchor.getLeft() + anchor.getWidth() / 2f;
            float top = dockFrame.getTop() + dockRow.getTop() + anchor.getTop() - anchor.getHeight() * 0.32f;
            showMenu("Wallpaper", labels, actions, cx, top - dp(8), true, true, checked, offset + wallpaper.getIndex());
        } else {
            showMenu("Wallpaper", labels, actions, dp(40), dp(40), false, false, checked, offset + wallpaper.getIndex());
        }
    }

    private void setWallpaper(int index) {
        wallpaper.show(index, true);
        prefs.edit().putInt(KEY_WALLPAPER, wallpaper.getIndex())
                .putLong(KEY_WALLPAPER_AT, System.currentTimeMillis()).apply();
        scheduleRotation();
    }

    private final Runnable rotateTick = new Runnable() {
        public void run() {
            if (!prefs.getBoolean(KEY_ROTATE, true)) return;
            setWallpaper(wallpaper.getIndex() + 1);
        }
    };

    /** Restarts the 10-minute timer, catching up if the screen was away longer than that. */
    private void scheduleRotation() {
        rotationHandler.removeCallbacks(rotateTick);
        if (!resumed || !prefs.getBoolean(KEY_ROTATE, true) || wallpaper.count() < 2) return;
        long since = System.currentTimeMillis() - prefs.getLong(KEY_WALLPAPER_AT, 0);
        long wait = Math.max(1000, ROTATE_MS - Math.max(0, since));
        rotationHandler.postDelayed(rotateTick, wait);
    }

    private void openAppsGrid() { openAppsGrid(null); }

    private void openAppsGrid(String focusKey) {
        closeOverlay();
        FrameLayout grid = new FrameLayout(this);
        grid.setClipChildren(false);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);

        TextView title = new TextView(this);
        title.setText("Apps");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setPadding(0, dp(28), 0, dp(6));
        column.addView(title);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, dp(14), 0, dp(24));
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        scroll.addView(rows);

        List<AppEntry> all = new ArrayList<>();
        all.addAll(installed.values());

        final int cols = 7;
        int tile = dp(78);
        LinearLayout row = null;
        View first = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                rows.addView(row);
            }
            final TileView t = gridTile(all.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tile, tile + dp(26));
            lp.setMargins(dp(14), dp(6), dp(14), dp(10));
            row.addView(t, lp);
            if (first == null || all.get(i).key.equals(focusKey)) first = t;
        }
        column.addView(scroll, new LinearLayout.LayoutParams(-2, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("OK to open  ·  Hold OK or press Menu for Dock and Hide options  ·  Back to close");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        hint.setPadding(0, dp(4), 0, dp(18));
        column.addView(hint);

        grid.addView(column, new FrameLayout.LayoutParams(-1, -1));
        grid.setAlpha(0f);
        grid.setScaleX(1.04f);
        grid.setScaleY(1.04f);
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        grid.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();

        wallpaper.setHeavyBlur(true);
        menuBar.animate().alpha(0f).setDuration(150).start();
        dockFrame.animate().alpha(0f).setDuration(150).start();
        desktopClock.animate().alpha(0f).setDuration(150).start();
        tooltip.setAlpha(0f);
        openOverlay(grid, true, first);
    }

    private TileView gridTile(final AppEntry e) {
        final TileView t = new TileView(this, e, true);
        t.setPinnedMark(pins.contains(e.key));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeOverlay();
                launch(e, null);
            }
        });
        t.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showGridActions(e, t);
                return true;
            }
        });
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(140).start();
            }
        });
        return t;
    }

    private void showGridActions(final AppEntry e, View tile) {
        final boolean pinned = pins.contains(e.key);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(pinned ? "Remove from Dock" : "Add to Dock");
        actions.add(new Runnable() {
            public void run() {
                if (pinned) pins.remove(e.key); else pins.add(e.key);
                savePins();
                rebuildDock(-1);
                openAppsGrid(e.key);
            }
        });
        if (!e.isBuiltIn()) {
            labels.add("Hide from Apps");
            actions.add(new Runnable() {
                public void run() {
                    hideApp(e.key);
                    toast(e.label + " hidden. Hold OK on Settings to bring hidden apps back.");
                    openAppsGrid(null);
                }
            });
        }
        labels.add("Cancel");
        actions.add(new Runnable() { public void run() { openAppsGrid(e.key); } });
        int[] loc = new int[2];
        tile.getLocationInWindow(loc);
        // the grid closes while this menu is up and reopens afterwards
        showMenu(e.label, labels, actions, loc[0] + tile.getWidth() / 2f, loc[1], true, true, -1);
    }

    private void openOverlay(View v, boolean isGrid, final View focus) {
        focusBeforeOverlay = getCurrentFocus();
        overlay = v;
        overlayIsGrid = isGrid;
        menuBar.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        dockRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        unmagnify();
        if (focus != null) focus.post(new Runnable() { public void run() { focus.requestFocus(); } });
    }

    private void closeOverlay() {
        if (overlay == null) return;
        root.removeView(overlay);
        overlay = null;
        menuBar.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        dockRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (overlayIsGrid) {
            wallpaper.setHeavyBlur(false);
            menuBar.animate().alpha(1f).setDuration(150).start();
            dockFrame.animate().alpha(1f).setDuration(150).start();
            desktopClock.animate().alpha(1f).setDuration(150).start();
        }
        overlayIsGrid = false;
        if (overlay == null) desktopClock.animate().alpha(1f).setDuration(150).start();
        final View back = focusBeforeOverlay;
        focusBeforeOverlay = null;
        if (back != null && back.isAttachedToWindow() && back.isFocusable()) {
            back.post(new Runnable() { public void run() { back.requestFocus(); } });
        } else {
            focusDock(0);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
