package com.mihir.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Glass sidebar home screen: clock top-left, Wi-Fi / Bluetooth / Settings buttons top-right, a frosted
 * panel on the left with your pinned apps and an "Apps" entry that opens the full app list, over a
 * slowly rotating wallpaper. Plain framework views only (no AndroidX), no network, no background work.
 *
 * Every size is expressed in "design units": 540 units = the screen height, so the layout looks the
 * same on 1080p and 4K output.
 */
public class MainActivity extends Activity {

    // Panel geometry in design units (matches the mockup this was built from).
    private static final int PANEL_X = 24, PANEL_Y = 108, PANEL_W = 265;
    private static final int ROW_H = 54, PANEL_PAD = 14, ICON = 39;
    private static final int MAX_PINNED = 6; // pinned apps; the "Apps" row is always added after them
    private static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;

    // Pinned apps, hidden apps and the app-list column count come from res/values/config.xml.
    private String[] pinnedPkgs;
    private final Set<String> hidden = new HashSet<>();
    private int cols;

    private float unit;
    private int screenW, screenH;

    static final class App {
        String pkg, label;
        Drawable icon;
        Intent intent;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> rows = new ArrayList<>();
    private final List<View> headerButtons = new ArrayList<>();
    private final List<View> appTiles = new ArrayList<>();

    private FrameLayout homeLayer, panel, frostClip;
    private View panelBorder, highlight, clockBox, buttonsBox;
    private LinearLayout list;
    private GradientDrawable highlightGlow;
    private CrossfadeImage wall, frost;
    private FrameLayout overlay;
    private ScrollView overlayScroll;
    private LinearLayout overlayGrid;
    private TextView clock, ampm, date;
    private ImageView wifiIcon;

    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private boolean firstWallpaper = true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;

    private int lastRow = 0;
    private boolean firstShow = true;
    private int packageSeq;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pinnedPkgs = getResources().getStringArray(R.array.dock_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        cols = getResources().getInteger(R.integer.columns);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        unit = screenH / 540f;
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (getCurrentFocus() == null) focusHome();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        unregisterReceiver(tick);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close the app list and jump back to the first app.
        if (overlayOpen()) closeOverlay();
        if (!rows.isEmpty()) { lastRow = 0; rows.get(0).requestFocus(); }
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to: Back closes the app list, or returns to the first app.
        if (overlayOpen()) { closeOverlay(); return; }
        if (!rows.isEmpty() && !rows.get(0).hasFocus()) rows.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers inside, so a new photo can crossfade in over the old one.
        wall = new CrossfadeImage(this, ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));

        // Soft scrims: darker on the left (panel, clock) and at the top (buttons) so text stays readable.
        View sideScrim = new View(this);
        sideScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x8C000000, 0x40000000, 0x00000000, 0x00000000}));
        root.addView(sideScrim, new FrameLayout.LayoutParams(-1, -1));
        View topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x80000000, 0x00000000}));
        root.addView(topScrim, new FrameLayout.LayoutParams(-1, u(160)));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }

        homeLayer = new FrameLayout(this);
        homeLayer.setClipChildren(false);
        root.addView(homeLayer, new FrameLayout.LayoutParams(-1, -1));
        homeLayer.addView(buildClock(), lp(-2, -2, Gravity.TOP | Gravity.START, u(35), u(30), 0, 0));
        homeLayer.addView(buildButtons(), lp(-2, -2, Gravity.TOP | Gravity.END, 0, u(24), u(30), 0));
        buildPanel();
        homeLayer.addView(panel, lp(u(PANEL_W), panelHeight(MAX_PINNED + 1), Gravity.TOP | Gravity.START,
                u(PANEL_X), u(PANEL_Y), 0, 0));

        overlay = buildOverlay();
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private View buildClock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);

        View bar = new View(this);
        bar.setBackgroundColor(0x59FFFFFF);
        box.addView(bar, new LinearLayout.LayoutParams(Math.max(2, u(1.5f)), u(44)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.BOTTOM);
        clock = text(31, "sans-serif", Color.WHITE);
        clock.setIncludeFontPadding(false);
        timeRow.addView(clock);
        ampm = text(17, "sans-serif", Color.WHITE);
        ampm.setIncludeFontPadding(false);
        timeRow.addView(ampm, llp(-2, -2, u(6), 0, 0, u(3)));
        col.addView(timeRow);
        date = text(14, "sans-serif", 0xE6FFFFFF);
        date.setIncludeFontPadding(false);
        col.addView(date, llp(-2, -2, 0, u(6), 0, 0));
        for (TextView t : new TextView[]{clock, ampm, date}) t.setShadowLayer(u(8), 0, u(1), 0x99000000);
        box.addView(col, llp(-2, -2, u(20), 0, 0, 0));
        clockBox = box;
        return box;
    }

    private View buildButtons() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setClipChildren(false);

        FrameLayout wifi = headerButton(R.drawable.ic_wifi, () -> launchSettings(Settings.ACTION_WIFI_SETTINGS));
        wifiIcon = (ImageView) wifi.getChildAt(0);
        FrameLayout bluetooth = headerButton(R.drawable.ic_bluetooth,
                () -> launchSettings(Settings.ACTION_BLUETOOTH_SETTINGS));
        FrameLayout settings = headerButton(R.drawable.ic_settings, () -> launchSettings(Settings.ACTION_SETTINGS));
        int i = 0;
        for (View v : new View[]{wifi, bluetooth, settings}) {
            box.addView(v, llp(u(37), u(37), i == 0 ? 0 : u(14), 0, 0, 0));
            headerButtons.add(v);
            i++;
        }
        buttonsBox = box;
        return box;
    }

    private FrameLayout headerButton(int iconRes, Runnable action) {
        FrameLayout b = new FrameLayout(this);
        b.setId(View.generateViewId());
        b.setFocusable(true);
        b.setClickable(true);
        b.setDefaultFocusHighlightEnabled(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x4D2A2C34);
        bg.setStroke(Math.max(1, u(1)), 0x40FFFFFF);
        b.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xF2FFFFFF);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.addView(icon, new FrameLayout.LayoutParams(u(19), u(19), Gravity.CENTER));

        b.setOnClickListener(v -> action.run());
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x4D2A2C34);
            icon.setColorFilter(has ? 0xFF111111 : 0xF2FFFFFF);
            v.animate().scaleX(has ? 1.14f : 1f).scaleY(has ? 1.14f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
        });
        return b;
    }

    /** The frosted glass panel: blurred wallpaper + tint + hairline border + sliding highlight + rows. */
    private void buildPanel() {
        int pw = u(PANEL_W), ph = panelHeight(MAX_PINNED + 1);
        panel = new FrameLayout(this);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        frostClip = new FrameLayout(this);
        frostClip.setClipToOutline(true);
        frostClip.setOutlineProvider(roundOutline(u(26)));
        frostClip.addOnLayoutChangeListener((v, l, t, r, b, oL, oT, oR, oB) -> v.invalidateOutline());
        frost = new CrossfadeImage(this, ImageView.ScaleType.FIT_XY);
        frostClip.addView(frost, new FrameLayout.LayoutParams(pw, ph));
        View tint = new View(this);
        tint.setBackgroundColor(getColor(R.color.glass_tint));
        frostClip.addView(tint, new FrameLayout.LayoutParams(-1, -1));
        View sheen = new View(this);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x26FFFFFF, 0x08FFFFFF, 0x00FFFFFF}));
        frostClip.addView(sheen, new FrameLayout.LayoutParams(-1, -1));
        panel.addView(frostClip, new FrameLayout.LayoutParams(pw, ph));

        panelBorder = new View(this);
        panelBorder.setBackground(ring(u(26), Math.max(1, u(1)), 0x38FFFFFF));
        panel.addView(panelBorder, new FrameLayout.LayoutParams(pw, ph));

        // One highlight pill that glides to whichever row has focus.
        highlightGlow = ring(u(18), u(4), 0x22FFFFFF);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x5CFFFFFF, 0x30FFFFFF});
        fill.setCornerRadius(u(17));
        fill.setStroke(Math.max(2, u(1.5f)), 0xB3E4F0FF);
        highlight = new View(this);
        highlight.setBackground(new LayerDrawable(new Drawable[]{highlightGlow, fill}));
        highlight.setAlpha(0f);
        highlight.setScaleX(1.08f);
        highlight.setScaleY(1.08f);
        highlight.setTranslationX(u(6));
        panel.addView(highlight, new FrameLayout.LayoutParams(u(PANEL_W - 12), u(60)));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setPadding(0, u(PANEL_PAD), 0, u(PANEL_PAD));
        panel.addView(list, new FrameLayout.LayoutParams(-1, -2));
    }

    private int panelHeight(int rowCount) {
        return u(PANEL_PAD * 2 + ROW_H * rowCount);
    }

    private static void setHeight(View v, int h) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        p.height = h;
        v.setLayoutParams(p);
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();

        Map<String, App> byPkg = new HashMap<>();
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(leanback, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (hidden.contains(pkg) || byPkg.containsKey(pkg)) continue;
            byPkg.put(pkg, makeApp(pm, ri, Intent.CATEGORY_LEANBACK_LAUNCHER));
        }
        // Phone apps you sideloaded have no Leanback entry but still run on a TV. System apps without one
        // are skipped so the list doesn't fill up with helpers.
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (hidden.contains(pkg) || byPkg.containsKey(pkg)) continue;
            if ((ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            byPkg.put(pkg, makeApp(pm, ri, Intent.CATEGORY_LAUNCHER));
        }

        List<App> pinned = new ArrayList<>();
        for (String p : pinnedPkgs) {
            App a = byPkg.get(p);
            if (a != null && pinned.size() < MAX_PINNED && !pinned.contains(a)) pinned.add(a);
        }
        List<App> all = new ArrayList<>(byPkg.values());
        Collections.sort(all, (x, y) -> x.label.compareToIgnoreCase(y.label));

        list.removeAllViews();
        rows.clear();
        for (App a : pinned) addAppRow(a);
        addAppsRow();
        int h = panelHeight(rows.size());
        setHeight(panel, h);
        setHeight(frostClip, h);
        setHeight(panelBorder, h);

        buildOverlayGrid(all);
        wireFocus();

        if (!overlayOpen()) focusHome();
    }

    private App makeApp(PackageManager pm, ResolveInfo ri, String category) {
        ActivityInfo ai = ri.activityInfo;
        App a = new App();
        a.pkg = ai.packageName;
        a.label = String.valueOf(ri.loadLabel(pm));
        a.icon = ri.loadIcon(pm);
        a.intent = new Intent(Intent.ACTION_MAIN).addCategory(category)
                .setComponent(new ComponentName(ai.packageName, ai.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        a.color = sampleColor(a.pkg, a.icon);
        return a;
    }

    /** Each view needs its own Drawable instance (bounds are per instance). */
    private Drawable iconOf(App a) {
        Drawable.ConstantState cs = a.icon.getConstantState();
        return cs != null ? cs.newDrawable(getResources()).mutate() : a.icon;
    }

    private void addAppRow(App app) {
        LinearLayout row = newRow(iconView(iconOf(app), 9), app.label);
        row.setOnClickListener(v -> launch(app.intent));
        registerRow(row, app.color);
    }

    private void addAppsRow() {
        ImageView icon = iconView(getDrawable(R.drawable.ic_apps_tile), 9);
        LinearLayout row = newRow(icon, "Apps");
        row.setOnClickListener(v -> openOverlay());
        registerRow(row, 0);
    }

    private void registerRow(LinearLayout row, int color) {
        final int index = rows.size();
        row.setOnFocusChangeListener((v, has) -> {
            if (has) {
                lastRow = index;
                highlightGlow.setStroke(u(4), withAlpha(color == 0 ? 0xFFFFFF : color, 0.30f));
                showHighlight(v);
            } else {
                // If focus moved to another row it is already showing the pill; otherwise fade it out.
                v.post(() -> { if (!list.hasFocus()) hideHighlight(); });
            }
        });
        rows.add(row);
        list.addView(row, new LinearLayout.LayoutParams(-1, u(ROW_H)));
    }

    private LinearLayout newRow(View icon, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setId(View.generateViewId());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(u(33), 0, u(14), 0);
        row.setClipChildren(false);
        row.setFocusable(true);
        row.setClickable(true);
        row.setDefaultFocusHighlightEnabled(false);
        row.addView(icon, new LinearLayout.LayoutParams(u(ICON), u(ICON)));

        TextView t = text(16.5f, "sans-serif", Color.WHITE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setText(label);
        t.setShadowLayer(u(6), 0, u(1), 0x66000000);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMargins(u(21), 0, 0, 0);
        row.addView(t, tp);
        return row;
    }

    private ImageView iconView(Drawable d, float radiusUnits) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(d);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setClipToOutline(true);
        v.setOutlineProvider(roundOutline(u(radiusUnits)));
        return v;
    }

    private void showHighlight(View row) {
        if (!row.isLaidOut()) {
            // First focus happens before the first layout pass; place the pill once positions exist.
            row.post(() -> { if (row.hasFocus() && row.isLaidOut()) showHighlight(row); });
            return;
        }
        float y = list.getTop() + row.getTop() + (row.getHeight() - u(60)) / 2f;
        boolean wasHidden = highlight.getAlpha() < 0.05f;
        highlight.animate().cancel();
        if (wasHidden) highlight.setTranslationY(y);
        highlight.animate().translationY(y).alpha(1f)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .setDuration(wasHidden ? 150 : 220).start();
    }

    private void hideHighlight() {
        highlight.animate().cancel();
        highlight.animate().alpha(0f).setDuration(150).start();
    }

    /** Explicit D-pad targets: Up/Down walk the list, Right (or Up from the top) reaches the buttons. */
    private void wireFocus() {
        int n = rows.size();
        View firstBtn = headerButtons.get(0);
        for (int i = 0; i < n; i++) {
            View r = rows.get(i);
            r.setNextFocusUpId(i == 0 ? firstBtn.getId() : rows.get(i - 1).getId());
            r.setNextFocusDownId(i == n - 1 ? r.getId() : rows.get(i + 1).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(firstBtn.getId());
        }
        int m = headerButtons.size();
        for (int i = 0; i < m; i++) {
            View b = headerButtons.get(i);
            b.setNextFocusUpId(b.getId());
            b.setNextFocusDownId(rows.get(0).getId());
            b.setNextFocusLeftId(i == 0 ? rows.get(0).getId() : headerButtons.get(i - 1).getId());
            b.setNextFocusRightId(i == m - 1 ? b.getId() : headerButtons.get(i + 1).getId());
        }
    }

    private void focusHome() {
        if (rows.isEmpty()) return;
        rows.get(Math.min(lastRow, rows.size() - 1)).requestFocus();
    }

    // ---------------------------------------------------------------- app list overlay

    private FrameLayout buildOverlay() {
        FrameLayout o = new FrameLayout(this);
        o.setVisibility(View.GONE);
        o.setBackgroundColor(0xE6080A10);

        TextView title = text(22, "sans-serif-light", Color.WHITE);
        title.setText("Apps");
        o.addView(title, lp(-2, -2, Gravity.TOP | Gravity.START, u(60), u(30), 0, 0));

        overlayScroll = new ScrollView(this);
        overlayScroll.setVerticalScrollBarEnabled(false);
        overlayScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        overlayScroll.setClipToPadding(false);
        overlayScroll.setPadding(u(52), u(14), u(52), u(40));
        overlayGrid = new LinearLayout(this);
        overlayGrid.setOrientation(LinearLayout.VERTICAL);
        overlayGrid.setClipChildren(false);
        overlayScroll.addView(overlayGrid, new FrameLayout.LayoutParams(-1, -2));
        o.addView(overlayScroll, lp(-1, -1, Gravity.TOP, 0, u(76), 0, 0));
        return o;
    }

    private void buildOverlayGrid(List<App> all) {
        overlayGrid.removeAllViews();
        appTiles.clear();
        LinearLayout row = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                overlayGrid.addView(row, llp(-1, -2, 0, 0, 0, u(14)));
            }
            row.addView(appTile(all.get(i)), new LinearLayout.LayoutParams(0, -2, 1f));
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = all.size() % cols; i != 0 && i < cols; i++) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    private View appTile(App app) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(u(8), u(14), u(8), u(10));
        t.setFocusable(true);
        t.setClickable(true);
        t.setDefaultFocusHighlightEnabled(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(u(16));
        bg.setColor(Color.TRANSPARENT);
        t.setBackground(bg);

        t.addView(iconView(iconOf(app), 14), new LinearLayout.LayoutParams(u(64), u(64)));
        TextView label = text(13, "sans-serif", Color.WHITE);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setText(app.label);
        t.addView(label, llp(-1, -2, 0, u(8), 0, 0));

        t.setOnClickListener(v -> launch(app.intent));
        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0x33FFFFFF : Color.TRANSPARENT);
            bg.setStroke(Math.max(1, u(1.5f)), has ? 0xB3E4F0FF : Color.TRANSPARENT);
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(160).start();
        });
        appTiles.add(t);
        return t;
    }

    private boolean overlayOpen() {
        return overlay != null && overlay.getVisibility() == View.VISIBLE;
    }

    private void openOverlay() {
        homeLayer.setVisibility(View.INVISIBLE);
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(200).start();
        overlayScroll.scrollTo(0, 0);
        if (!appTiles.isEmpty()) appTiles.get(0).requestFocus();
    }

    private void closeOverlay() {
        overlay.animate().cancel();
        overlay.setVisibility(View.GONE);
        homeLayer.setVisibility(View.VISIBLE);
        if (!rows.isEmpty()) {
            lastRow = rows.size() - 1; // came from the "Apps" row
            rows.get(lastRow).requestFocus();
        }
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "HH:mm" : "hh:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (now.get(Calendar.HOUR_OF_DAY) < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now.getTime()));
    }

    /** Dim the Wi-Fi icon when there is no network (no location permission needed). */
    private void updateWifi() {
        boolean online = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            online = nc != null && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (RuntimeException ignored) { }
        wifiIcon.setAlpha(online ? 1f : 0.4f);
    }

    // ---------------------------------------------------------------- wallpaper

    /** Decode the next photo off the main thread, build its frosted crop, then crossfade both in. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = "wallpapers/" + wallpapers[wallIndex];
        new Thread(() -> {
            Bitmap bm = null;
            Bitmap blurred = null;
            try (java.io.InputStream in = getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeStream(in, null, o);
                if (bm != null) blurred = makeFrost(bm);
            } catch (java.io.IOException | RuntimeException ignored) { }
            Bitmap photo = bm, glass = blurred;
            runOnUiThread(() -> showWallpaper(photo, glass));
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    private void showWallpaper(Bitmap photo, Bitmap glass) {
        if (photo == null || isDestroyed()) return;
        long ms = firstWallpaper ? 600 : 2000;
        firstWallpaper = false;
        wall.show(photo, ms);
        if (glass != null) frost.show(glass, ms);
    }

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /**
     * The part of the photo that sits behind the panel, shrunk to a few dozen pixels and box-blurred.
     * Scaled back up by the ImageView it reads as frosted glass, with no GPU blur at runtime.
     */
    private Bitmap makeFrost(Bitmap src) {
        float s = Math.max(screenW / (float) src.getWidth(), screenH / (float) src.getHeight());
        float offX = (src.getWidth() * s - screenW) / 2f;
        float offY = (src.getHeight() * s - screenH) / 2f;
        int x = Math.max(0, Math.round((u(PANEL_X) + offX) / s));
        int y = Math.max(0, Math.round((u(PANEL_Y) + offY) / s));
        int w = Math.min(src.getWidth() - x, Math.round(u(PANEL_W) / s));
        int h = Math.min(src.getHeight() - y, Math.round(panelHeight(MAX_PINNED + 1) / s));
        if (w < 8 || h < 8) return null;
        Bitmap crop = Bitmap.createBitmap(src, x, y, w, h);
        int tw = 56, th = Math.max(8, Math.round(tw * (float) h / w));
        Bitmap small = Bitmap.createScaledBitmap(crop, tw, th, true);
        int[] px = new int[tw * th];
        small.getPixels(px, 0, tw, 0, 0, tw, th);
        for (int i = 0; i < 3; i++) boxBlur(px, tw, th, 3);
        return Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888);
    }

    private static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        blurPass(px, tmp, w, h, r, true);
        blurPass(tmp, px, w, h, r, false);
    }

    private static void blurPass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int red = 0, green = 0, blue = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.min(len - 1, Math.max(0, i + k));
                    int p = horizontal ? in[line * w + j] : in[j * w + line];
                    red += (p >> 16) & 0xFF;
                    green += (p >> 8) & 0xFF;
                    blue += p & 0xFF;
                    n++;
                }
                int o = 0xFF000000 | ((red / n) << 16) | ((green / n) << 8) | (blue / n);
                if (horizontal) out[line * w + i] = o; else out[i * w + line] = o;
            }
        }
    }

    /** Average the icon's vivid pixels, then push saturation so the highlight glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            copy.setBounds(0, 0, 24, 24);
            copy.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View[] bars = {clockBox, buttonsBox};
        for (View v : bars) {
            v.setAlpha(0f);
            v.setTranslationY(-u(10));
            v.animate().alpha(1f).translationY(0).setDuration(450).setStartDelay(80).start();
        }
        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            r.setAlpha(0f);
            r.setTranslationX(-u(18));
            r.animate().alpha(1f).translationX(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(420).setStartDelay(120 + i * 50L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Open a Settings page; fall back to the main Settings app if the TV has no screen for it. */
    private void launchSettings(String action) {
        if (!launch(new Intent(action))) launch(new Intent(Settings.ACTION_SETTINGS));
    }

    private TextView text(float sizeUnits, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeUnits * unit);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    /** Design units to pixels (540 units = screen height). */
    private int u(float v) {
        return Math.round(v * unit);
    }

    private static GradientDrawable ring(int radius, int strokeWidth, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setColor(Color.TRANSPARENT);
        g.setStroke(strokeWidth, color);
        return g;
    }

    private static ViewOutlineProvider roundOutline(final int radius) {
        return new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        };
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    /** Two stacked ImageViews; show() fades the new bitmap in over the old one, then frees the old one. */
    static final class CrossfadeImage extends FrameLayout {
        private ImageView front, back;

        CrossfadeImage(Context c, ImageView.ScaleType type) {
            super(c);
            front = layer(c, type);
            back = layer(c, type);
            addView(back, new LayoutParams(-1, -1));
            addView(front, new LayoutParams(-1, -1));
        }

        private static ImageView layer(Context c, ImageView.ScaleType type) {
            ImageView v = new ImageView(c);
            v.setScaleType(type);
            return v;
        }

        void show(Bitmap bm, long durationMs) {
            final ImageView incoming = back, outgoing = front;
            incoming.animate().cancel();
            outgoing.animate().cancel();
            removeView(incoming);
            addView(incoming, new LayoutParams(-1, -1)); // on top
            incoming.setImageBitmap(bm);
            incoming.setAlpha(0f);
            outgoing.setAlpha(1f);
            front = incoming;
            back = outgoing;
            incoming.animate().alpha(1f).setDuration(durationMs)
                    .withEndAction(() -> outgoing.setImageDrawable(null)).start();
        }
    }
}
