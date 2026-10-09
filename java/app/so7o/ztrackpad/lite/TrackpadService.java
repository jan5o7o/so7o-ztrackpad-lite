package app.so7o.ztrackpad.lite;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;


/**
 * ZTrackpad Lite - a floating trackpad plus on-screen pointer, built on an
 * AccessibilityService and nothing else.
 *
 * Overlay windows, all TYPE_ACCESSIBILITY_OVERLAY, all on the default display:
 *   bubble       - small draggable dot, tap to toggle the trackpad; snaps to the
 *                  nearer vertical edge on release
 *   (the theme menu and the lock are NOT windows any more: they are dots docked
 *    inside the pad's title-bar area, see addDockedDot)
 *   pad          - the trackpad surface (handle bar + touch area + button row)
 *   cursor       - the pointer, drawn by us and moved with updateViewLayout
 *
 * There is only one display in play: the pointer drives the screen the panels are on.
 * Every input event is a plain accessibility gesture (dispatchGesture), which is what
 * keeps this app dependency-free - and is also its cost, because that path has no
 * display parameter, no hover, and only an approximate drag.
 */
public class TrackpadService extends AccessibilityService {

    private static final String TAG = "ZTrackpad";

    /* ---- gesture tuning ---- */
    private static final int SLOP = 14;            // px before a touch counts as a move
    private static final long LONGPRESS_MS = 520;  // hold this long without moving = long press
    /** px of injected scroll per px of finger travel, for the two-finger drag. */
    private static final float SCROLL_GAIN = 1.4f;
    /**
     * The edge strips: injected px per px of finger travel.
     *
     * This is the strip's *speed*, and the one number to reach for when a swipe does not carry
     * far enough. It is a plain ratio: the whole gesture's travel is banked while the finger is
     * down and spent as one stroke on release, so a 300px swipe down the strip moves the page
     * 300 x this.
     */
    private static final float EDGE_GAIN = 2.0f;
    /**
     * Floor on the distance one gesture injects, in dp.
     *
     * The target's touch slop is about 8dp, but the floor is set well above it because a
     * stroke only marginally over the slop is read as a slow drag rather than a scroll. A
     * stroke below this is not worth sending at all: it costs a gesture round trip and moves
     * nothing.
     */
    private static final int MIN_SCROLL_DP = 20;
    /** Ceiling on one gesture's scroll, as a fraction of the screen height. */
    private static final float SCROLL_CAP_SCREEN = 0.6f;
    /** How long the single stroke that carries a scroll takes. */
    private static final long SCROLL_MS = 260L;
    /**
     * Width of the pad's edge scroll strips, in dp.
     *
     * Fixed rather than a fraction of the pad: the pad can be resized down to a dp(240)
     * minimum, where a percentage would leave a sliver too narrow to hit.
     */
    private static final int EDGE_SCROLL_DP = 28;
    private static final long CLICK_MS = 45L;
    /**
     * How long to wait before injecting through the panels, and how long the panels then
     * stay non-touchable on top of the gesture itself. Two frames is enough for the window
     * manager to apply FLAG_NOT_TOUCHABLE; the margin covers the round trip back.
     */
    private static final long TOUCHABLE_SETTLE_MS = 40L;
    private static final long HOLD_MS = 650;
    private static final long DRAG_HOLD_MS = 260;   // hold still this long, then move = drag
    private static final long DRAG_BEAT_MS = 150;   // heartbeat that keeps the press alive
    private static final long CHUNK_MS = 400;       // stroke chunk length (too short reads as a tap)
    private static final long DRAG_STALL_MS = 2500; // no touch movement this long -> force release
    private static final long DOUBLE_TAP_MS = 300;  // tap, then touch again within this -> drag
    private static final long REPEAT_DELAY_MS = 420; // hold a key this long before it auto-repeats
    private static final long REPEAT_MS = 60;        // then repeat at this interval

    /**
     * Pointer z-order relative to the pad.
     * false = the pad draws OVER the pointer (pointer is hidden whenever it sits on the pad).
     * true  = the pointer is raised above the pad and always visible.
     */
    private static final boolean CURSOR_ABOVE_PANELS = true;

    // pref-key prefixes: "" keeps the trackpad's original names so saved geometry survives
    private static final String PAD_KEY = "";
    private static final String THEME_KEY = "t_";

    /** Pref holding the chosen Theme id. */
    private static final String PREF_THEME = "theme";

    /** Pref holding the panel opacity multiplier. */
    private static final String PREF_OPACITY = "opacity";

    /**
     * Pref holding whether the pad is locked against moving and resizing.
     *
     * Persisted, unlike bubble positions: a lock that forgot itself on restart would be
     * worse than no lock, because the pad would move on the next accidental brush.
     */
    private static final String PREF_LOCK = "padLocked";

    /** Opacity slider range, in percent. */
    private static final int OPACITY_MIN = 20;
    private static final int OPACITY_MAX = 100;

    private WindowManager wm;
    private SharedPreferences prefs;
    private Handler ui;

    /**
     * Size of the display. Fingers, panels and pointer all share it: there is only one
     * display, so a pointer coordinate is a screen coordinate.
     */
    private int screenW = 1080, screenH = 1920;
    private float density = 3f;

    private View bubble, pad;
    /** The pad's touch surface: draws the edge-strip dotted markers. Always on in Lite. */
    private PadSurface padSurface;
    private CursorView cursor;
    private TextView moveChip;
    /** The pad's lock dot, so a theme rebuild can hand back a new one. */
    private LockDot lockDot;
    /** When true the pad ignores the move handle and every resize grip. */
    private boolean padLocked = false;
    /** Visual preset. Never null; Theme.byId falls back to the default. */
    private Theme theme = Theme.byId(null);
    /** Panel opacity multiplier. Only panel fills scale - see fill(). */
    private float opacity = 1f;
    private Vibrator vib;
    private Boolean hapticsOn = null;
    private WindowManager.LayoutParams bubbleLp, padLp, cursorLp;
    private View themePanel;
    private WindowManager.LayoutParams themePanelLp;
    private LinearLayout themeRows;
    private LinearLayout opacityRows;
    private TextView opacityLabel;
    /** Slider value while a drag is in progress; -1 when idle. */
    private float pendingOpacity = -1f;
    private boolean themeVisible = false;

    private float cursorX, cursorY;
    private float sensitivity = 1.7f;

    private boolean padVisible = false;
    private boolean scrollMode = false;
    /** True while a touch that started in an edge strip owns the gesture - see PadTouch. */
    private boolean edgeScroll = false;
    private boolean dragArmed = false;
    private boolean dragging = false;
    private boolean holdReady = false;
    private boolean tapDrag = false;
    private long lastTapUpAt = 0L;
    // where the last drag chunk actually ended - continueStroke() requires the next
    // path to START exactly here, or the continuation is rejected and the drag dies
    private float strokeEndX, strokeEndY;
    private long lastMoveAt;
    private Runnable holdRun;
    private Runnable beat;

    private float downX, downY, lastX, lastY;
    private long downTime;
    private boolean moved;
    /** Scroll asked for during a finger-down gesture, spent on release - see flushPendingScroll. */
    private float pendingScrollY = 0f;
    private GestureDescription.StrokeDescription stroke;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("pad", MODE_PRIVATE);
        ui = new Handler(Looper.getMainLooper());
        readScreenMetrics();
        // before anything is built: every panel reads its colours from here
        theme = Theme.byId(prefs.getString(PREF_THEME, Theme.DEFAULT_ID));
        opacity = prefs.getFloat(PREF_OPACITY, 1f);
        padLocked = prefs.getBoolean(PREF_LOCK, false);

        cursorX = screenW / 2f;
        cursorY = screenH / 2f;

        buildAll();
        setPadVisible(true);
        raise(pad, padLp, "pad");
        if (CURSOR_ABOVE_PANELS) raise(cursor, cursorLp, "cursor");
        ControlReceiver.service = this;
        Log.i(TAG, "connected " + screenW + "x" + screenH + " density=" + density);
    }

    /**
     * Overlay z-order follows add order, so raising = remove + re-add. The pointer is
     * raised last (see CURSOR_ABOVE_PANELS) so it stays visible over the pad.
     */
    private void raise(View v, WindowManager.LayoutParams lp, String what) {
        if (v == null || lp == null) return;
        try {
            wm.removeView(v);
            wm.addView(v, lp);
            Log.i(TAG, what + " raised");
        } catch (Exception e) {
            Log.w(TAG, "raise " + what + " failed: " + e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* not needed */ }

    @Override
    public void onInterrupt() { /* not needed */ }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        removeAll();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        removeAll();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        int oldW = screenW, oldH = screenH;
        readScreenMetrics();
        if (oldW != screenW || oldH != screenH) {
            if (pad != null) {
                clampGeometryToScreen(padLp);
                try { wm.updateViewLayout(pad, padLp); } catch (Exception ignored) {}
                saveGeometry(padLp, PAD_KEY);
            }
            // bubbles carry no persisted geometry, but a right-hand dot must not stay
            // parked past the new edge when the screen turns
            resnapBubble(bubble, bubbleLp, bubbleSide);
        }
    }

    private void readScreenMetrics() {
        Rect b = wm.getCurrentWindowMetrics().getBounds();
        screenW = b.width();
        screenH = b.height();
        density = getResources().getDisplayMetrics().density;
    }

    /**
     * Apply the opacity multiplier to a panel fill.
     *
     * Only fills scale. Dimming the text and borders too would not make the panels look
     * more transparent, it would just make them unreadable - the point of turning the
     * opacity down is to see more of the app underneath while still reading the pad.
     */
    private int fill(int color) {
        int a = (color >>> 24) & 0xFF;
        int scaled = Math.round(a * opacity);
        if (scaled < 0) scaled = 0;
        if (scaled > 0xFF) scaled = 0xFF;
        return (scaled << 24) | (color & 0x00FFFFFF);
    }

    private int dp(float v) { return Math.round(v * density); }

    /**
     * Build every overlay. Order matters: it sets the z-order, so the pad and its bubble
     * come first and the cursor is raised last.
     */
    private void buildAll() {
        buildBubble();
        buildCursor();
        buildPad();
        buildThemePanel();
    }

    // =========================================================================
    // Theme
    // =========================================================================

    /**
     * Rebuild the panels under the current preset, keeping positions and visibility.
     *
     * A rebuild rather than a recolour, because every background is a generated
     * GradientDrawable or StateListDrawable built from theme values at construction time.
     * The bubble is the exception: it is restyled in place, so it does not jump back to
     * its default position (bubble positions are not persisted).
     */
    private void applyTheme() {
        boolean themeWas = themeVisible;

        // persist geometry first, or the rebuild would fall back to the defaults
        saveGeometry(padLp, PAD_KEY);

        removeViews();
        buildAll();

        restyleBubbles();
        setPadVisible(true);
        // keep the theme panel open, so presets can be tried one after another
        setThemeVisible(themeWas);

        raise(pad, padLp, "pad");
        if (CURSOR_ABOVE_PANELS) raise(cursor, cursorLp, "cursor");
        updateCursorAlpha();
        updateModeUi();
        Log.i(TAG, "theme applied: " + theme.id);
    }

    private void setTheme(String id) {
        Theme next = Theme.byId(id);
        if (next == theme) return;
        theme = next;
        prefs.edit().putString(PREF_THEME, theme.id).apply();
        applyTheme();
    }

    /** Bubbles are plain TextViews, so they can be restyled without being rebuilt. */
    private void restyleBubbles() {
        restyleBubble(bubble, theme.bubbleTrack);
    }

    private void restyleBubble(View v, int textColor) {
        if (!(v instanceof TextView)) return;
        ((TextView) v).setTextColor(textColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(fill(theme.bubbleFill));
        bg.setStroke(dp(1.5f), theme.bubbleStroke);
        v.setBackground(bg);
    }

    private void buildThemePanel() {
        FrameLayout container = new FrameLayout(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        FrameLayout handle = new FrameLayout(this);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setCornerRadii(new float[]{dp(theme.radius), dp(theme.radius),
                dp(theme.radius), dp(theme.radius), 0, 0, 0, 0});
        hbg.setColor(fill(theme.panelHead));
        handle.setBackground(hbg);
        handle.addView(makeChip("\u25D0  THEME \u2014 tap a preset"),
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER));
        // The close button owns the title bar's right end. It is a clickable child, so it
        // gets the touch before the drag listener does and pressing it never drags the panel.
        TextView closeX = new TextView(this);
        closeX.setText("\u00D7");
        closeX.setTextSize(15f);
        closeX.setTextColor(theme.textDim);
        closeX.setGravity(Gravity.CENTER);
        closeX.setPadding(dp(14), 0, dp(14), 0);
        closeX.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tick(); setThemeVisible(false); }
        });
        handle.addView(closeX, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX() - themePanelLp.x;
                        dy = e.getRawY() - themePanelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        themePanelLp.x = (int) (e.getRawX() - dx);
                        themePanelLp.y = (int) (e.getRawY() - dy);
                        try { wm.updateViewLayout(themePanel, themePanelLp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        saveGeometry(themePanelLp, THEME_KEY);
                        return true;
                }
                return false;
            }
        });
        content.addView(handle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        themeRows = new LinearLayout(this);
        themeRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(themeRows, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        content.addView(sectionLabel("OPACITY"));
        opacityRows = new LinearLayout(this);
        opacityRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(opacityRows, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        container.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        GradientDrawable rbg = new GradientDrawable();
        rbg.setCornerRadius(dp(theme.radius));
        rbg.setColor(fill(theme.panelSolid));
        rbg.setStroke(dp(1.5f), theme.panelStroke);
        container.setBackground(rbg);

        themePanelLp = overlayLp(dp(300), dp(300),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        themePanel = container;

        addResizeGrips(container, themePanelLp, THEME_KEY, true);

        restoreGeometry(themePanelLp, THEME_KEY, dp(300), dp(300));
        try { wm.addView(themePanel, themePanelLp); }
        catch (Exception ex) { Log.e(TAG, "themePanel", ex); }
        refreshThemeRows();
        setThemeVisible(false);
    }

    private void refreshThemeRows() {
        if (themeRows != null) {
            themeRows.removeAllViews();
            for (int i = 0; i < Theme.PRESETS.length; i++) {
                themeRows.addView(themeRow(Theme.PRESETS[i]));
            }
        }
        if (opacityRows != null) {
            opacityRows.removeAllViews();
            opacityRows.addView(opacitySlider());
        }
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(10f);
        t.setTextColor(theme.textDim);
        t.setPadding(dp(12), dp(12), dp(12), dp(4));
        return t;
    }

    /**
     * The opacity control: a slider, with a live percentage label.
     *
     * It deliberately does NOT apply on every tick. Applying means rebuilding the panels
     * (their backgrounds are generated drawables), and rebuilding inside a SeekBar's own
     * touch callback would remove the slider mid-drag. So the drag only updates the
     * label, and the release posts the rebuild - by which time the touch is over.
     */
    private View opacitySlider() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), 0, dp(12), dp(6));

        opacityLabel = new TextView(this);
        opacityLabel.setTextSize(11f);
        opacityLabel.setTextColor(theme.textSecondary);
        opacityLabel.setText(opacityText(opacity));
        box.addView(opacityLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        pendingOpacity = -1f;
        SeekBar sb = new SeekBar(this);
        sb.setMax(OPACITY_MAX - OPACITY_MIN);
        sb.setProgress(Math.round(opacity * 100f) - OPACITY_MIN);
        sb.setProgressTintList(ColorStateList.valueOf(theme.accent));
        sb.setThumbTintList(ColorStateList.valueOf(theme.accent));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float v = (OPACITY_MIN + progress) / 100f;
                pendingOpacity = v;
                if (opacityLabel != null) opacityLabel.setText(opacityText(v));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                final float v = (pendingOpacity < 0f) ? opacity : pendingOpacity;
                pendingOpacity = -1f;
                ui.post(new Runnable() {
                    @Override public void run() { setOpacity(v); }
                });
            }
        });
        box.addView(sb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private String opacityText(float v) {
        return "Panel opacity: " + Math.round(v * 100f) + "%";
    }

    private void setOpacity(float v) {
        if (Math.abs(v - opacity) < 0.01f) return;
        opacity = v;
        prefs.edit().putFloat(PREF_OPACITY, v).apply();
        applyTheme();
    }

    private TextView themeRow(final Theme preset) {
        boolean active = (preset == theme);
        TextView t = new TextView(this);
        t.setText((active ? "\u25C9  " : "\u25CB  ") + preset.label);
        t.setTextSize(12f);
        t.setTextColor(active ? theme.textPrimary : theme.textSecondary);
        t.setPadding(dp(12), dp(11), dp(12), dp(11));
        t.setBackground(keyBgStateRect(active ? theme.selectedRow : 0x00000000, theme.accent));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tick(); setTheme(preset.id); }
        });
        return t;
    }

    private void setThemeVisible(boolean visible) {
        themeVisible = visible;
        if (themePanel == null) return;
        if (visible) {
            refreshThemeRows();
            clampGeometryToScreen(themePanelLp);
            raise(themePanel, themePanelLp, "themePanel");
        }
        themePanel.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void removeViews() {
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) {}
        try { if (pad != null) wm.removeView(pad); } catch (Exception ignored) {}
        try { if (themePanel != null) wm.removeView(themePanel); } catch (Exception ignored) {}
        try { if (cursor != null) wm.removeView(cursor); } catch (Exception ignored) {}
        bubble = pad = null;
        themePanel = null;
        cursor = null;
        themeRows = null;
        opacityRows = null;
    }

    private void removeAll() {
        ControlReceiver.service = null;
        removeViews();
    }

    private WindowManager.LayoutParams overlayLp(int w, int h, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        return lp;
    }

    // =========================================================================
    // Cursor
    // =========================================================================

    private void updateCursorAlpha() {
        if (cursor == null) return;
        cursor.setAlpha(padVisible ? 1f : 0f);
    }

    /**
     * Entry point for ControlReceiver, and therefore for scripts. Runs on the main
     * thread, which the window work requires.
     *
     * Returns one machine-readable line so a script can parse it.
     */
    public String controlCommand(String op, String arg) {
        if (op == null || op.length() == 0) op = "status";
        if ("status".equals(op)) return padStatus();

        // Pad lock, so a script can freeze the pad the same way the pad's own lock dot
        // does - both go through setPadLocked, so the dot and this can never disagree.
        if ("lock".equals(op)) {
            String what = (arg == null || arg.length() == 0) ? "toggle" : arg.trim();
            if ("on".equals(what)) setPadLocked(true);
            else if ("off".equals(what)) setPadLocked(false);
            else if ("toggle".equals(what)) setPadLocked(!padLocked);
            else return "error: lock wants on|off|toggle, not '" + what + "'";
            return "ok lock " + padStatus();
        }

        // Theme, so the preset can be read back and restored without tapping the menu.
        if ("theme".equals(op)) {
            String what = (arg == null) ? "" : arg.trim();
            if (what.length() == 0) return "ok theme " + theme.id;
            boolean known = false;
            for (int i = 0; i < Theme.PRESETS.length; i++) {
                if (Theme.PRESETS[i].id.equals(what)) { known = true; break; }
            }
            if (!known) {
                // Theme.byId falls back to the default, so an unknown id has to be caught
                // here or a typo would silently reset the theme instead of being rejected.
                StringBuilder ids = new StringBuilder();
                for (int i = 0; i < Theme.PRESETS.length; i++) {
                    if (i > 0) ids.append('|');
                    ids.append(Theme.PRESETS[i].id);
                }
                return "error: theme wants " + ids + ", not '" + what + "'";
            }
            setTheme(what);
            return "ok theme " + theme.id;
        }

        return "error: unknown op '" + op + "' (status|lock|theme)";
    }

    /** One line of key=value pairs, for scripts to parse. */
    public String padStatus() {
        return "padlocked=" + padLocked
                + " pad=" + (padVisible ? "shown" : "hidden")
                + " theme=" + theme.id
                + " w=" + screenW
                + " h=" + screenH;
    }

    // =========================================================================
    // Bubble
    // =========================================================================

    /**
     * Which vertical edge a bubble last snapped to. Held in memory only, like every other
     * bubble property: each dot starts on its default side at launch.
     */
    private static final class BubbleSide {
        boolean right;
        BubbleSide(boolean right) { this.right = right; }
    }

    private final BubbleSide bubbleSide = new BubbleSide(true);       // ● starts RIGHT

    /** The in-flight snap, so a new touch can cancel it instead of fighting it. */
    private ValueAnimator bubbleAnim;

    private void buildBubble() {
        final int size = dp(44);
        TextView v = new TextView(this);
        v.setText("\u25CF");
        v.setTextColor(theme.bubbleTrack);
        v.setTextSize(20f);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(fill(theme.bubbleFill));
        bg.setStroke(dp(1.5f), theme.bubbleStroke);
        v.setBackground(bg);

        bubbleLp = overlayLp(size, size,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        bubbleLp.x = screenW - size - dp(8);
        bubbleLp.y = (int) (screenH * 0.45f);

        attachBubbleDrag(v, bubbleLp, bubbleSide, new Runnable() {
            @Override public void run() { setPadVisible(!padVisible); }
        });

        bubble = v;
        try { wm.addView(bubble, bubbleLp); } catch (Exception ex) { Log.e(TAG, "bubble", ex); }
    }

    /**
     * Drag a floating dot anywhere, and let go: it flies to whichever vertical edge is
     * nearer, like a chat head.
     *
     * Nothing clamped these before, and the windows carry FLAG_LAYOUT_NO_LIMITS, so a drag
     * could park a dot half off the screen or past the bottom, with no way back except
     * groping for it blind. Snapping fixes that and also settles the tap/drag ambiguity:
     * a touch now counts as a drag only once it passes the touch slop, so a short fast
     * flick no longer toggles the panel on release as well.
     */
    private void attachBubbleDrag(final View v, final WindowManager.LayoutParams lp,
                                  final BubbleSide side, final Runnable onTap) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        v.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy, downX, downY;
            private long t0;
            private boolean dragged;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        cancelSnap();
                        dx = e.getRawX() - lp.x;
                        dy = e.getRawY() - lp.y;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        t0 = SystemClock.uptimeMillis();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragged && Math.abs(e.getRawX() - downX)
                                + Math.abs(e.getRawY() - downY) > slop) dragged = true;
                        if (!dragged) return true;
                        lp.x = (int) (e.getRawX() - dx);
                        lp.y = (int) (e.getRawY() - dy);
                        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragged) snapBubble(view, lp, side, e.getRawX() > screenW / 2f, true);
                        else if (SystemClock.uptimeMillis() - t0 < 250) onTap.run();
                        return true;
                }
                return false;
            }
        });
    }

    /** Put a bubble back on its recorded edge, without animating - used when the screen
     *  size changes, where the window is being re-laid out anyway. */
    private void resnapBubble(View v, WindowManager.LayoutParams lp, BubbleSide side) {
        if (v == null || lp == null) return;
        snapBubble(v, lp, side, side.right, false);
    }

    private void cancelSnap() {
        if (bubbleAnim == null) return;
        bubbleAnim.cancel();
        bubbleAnim = null;
    }

    /**
     * Send a bubble to the given vertical edge, keeping its height but clamping it inside
     * the screen - the dot must never end up half off it, which is the point of the snap.
     *
     * The animated x is written through the LayoutParams the touch listener reads, so an
     * interrupted snap leaves the dot where it actually is.
     */
    private void snapBubble(View v, WindowManager.LayoutParams lp, BubbleSide side,
                            boolean toRight, boolean animate) {
        if (v == null || lp == null) return;
        cancelSnap();
        side.right = toRight;
        final int margin = dp(8);
        lp.y = clampInt(lp.y, margin, Math.max(margin, screenH - lp.height - margin));
        final int to = toRight ? Math.max(margin, screenW - lp.width - margin) : margin;
        if (!animate || lp.x == to) {
            lp.x = to;
            try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
            return;
        }
        ValueAnimator a = ValueAnimator.ofInt(lp.x, to);
        a.setDuration(160);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator anim) {
                lp.x = (Integer) anim.getAnimatedValue();
                try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
            }
        });
        bubbleAnim = a;
        a.start();
    }

    // =========================================================================
    // Cursor
    // =========================================================================

    private class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean hot;

        CursorView() {
            super(TrackpadService.this);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.WHITE);
            edge.setStyle(Paint.Style.STROKE);
            edge.setColor(Color.BLACK);
            edge.setStrokeWidth(dp(1.5f));
            edge.setStrokeJoin(Paint.Join.ROUND);
        }

        void setHot(boolean h) {
            if (hot == h) return;
            hot = h;
            fill.setColor(h ? theme.cursorHot : theme.cursor);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            Path p = new Path();
            p.moveTo(0f, 0f);
            p.lineTo(0f, h * 0.70f);
            p.lineTo(w * 0.20f, h * 0.55f);
            p.lineTo(w * 0.33f, h);
            p.lineTo(w * 0.48f, h * 0.94f);
            p.lineTo(w * 0.35f, h * 0.49f);
            p.lineTo(w * 0.62f, h * 0.49f);
            p.close();
            c.drawPath(p, fill);
            c.drawPath(p, edge);
        }
    }

    private void buildCursor() {
        cursor = new CursorView();
        cursorLp = overlayLp(dp(28), dp(34),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        cursorLp.x = (int) cursorX;
        cursorLp.y = (int) cursorY;
        cursor.setAlpha(0f);
        try { wm.addView(cursor, cursorLp); } catch (Exception ex) { Log.e(TAG, "cursor", ex); }
    }

    // =========================================================================
    // Trackpad
    // =========================================================================

    /**
     * A control dot docked just under a panel's title bar - the floating bubbles moved
     * inside the pad so they cannot be lost behind another window or overlapped.
     *
     * It floats over the content, so the pad's proportions do not change, and it sits
     * below the 34dp handle so the whole title bar stays draggable. That is the whole
     * reason it is not *in* the handle: there it swallowed drag touches.
     *
     * `slot` counts inward from that side (0 = against the corner), so a second dot on the
     * same side is spaced off the first instead of landing on top of it.
     */
    private TextView addDockedDot(FrameLayout container, String glyph, int glyphColor,
                                  boolean alignRight, int slot, View.OnClickListener action) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextColor(glyphColor);
        t.setTextSize(12f);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(fill(theme.bubbleFill));
        bg.setStroke(dp(1.5f), theme.bubbleStroke);
        t.setBackground(bg);
        t.setOnClickListener(action);
        dockDot(container, t, alignRight, slot);
        return t;
    }

    /** Seat a docked control dot: under the handle, inset clear of the panel's corner. */
    private void dockDot(FrameLayout container, View dot, boolean alignRight, int slot) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(26), dp(26),
                Gravity.TOP | (alignRight ? Gravity.RIGHT : Gravity.LEFT));
        // The side inset has to clear the panel's rounded corner (theme.radius, dp(18) by
        // default): at dp(6) the dot sat inside the curve and looked glued to the edge.
        lp.topMargin = dp(34) + dp(8);
        // NOTE: do NOT branch on `side & Gravity.RIGHT`. Gravity.LEFT is 3 and Gravity.RIGHT
        // is 5, so they share bit 0 and that test is true for BOTH - which is how the left
        // dot ended up flush against the edge while the right one was inset correctly.
        if (alignRight) {
            lp.rightMargin = dp(14) + slot * dp(32);
        } else {
            lp.leftMargin = dp(14) + slot * dp(32);
        }
        container.addView(dot, lp);
    }

    /**
     * The pad's lock dot, drawn rather than typed.
     *
     * It began as the emoji padlock, which ignores setTextColor: the icon rendered in the
     * font's own colours while every other dot followed the theme. A thin outline, the same
     * in both states - the pad's state shows on the handle (`≡ MOVE` / `≡ LOCKED`), so the
     * dot has no reason to shout about it, and no reason to change under the finger that
     * just tapped it.
     */
    private class LockDot extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();

        LockDot() {
            super(TrackpadService.this);
            ring.setStyle(Paint.Style.FILL);
            rim.setStyle(Paint.Style.STROKE);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            ring.setColor(fill(theme.bubbleFill));
            rim.setColor(theme.bubbleStroke);
            rim.setStrokeWidth(dp(1.5f));
            glyph.setColor(theme.textDim);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f;
            float sw = Math.max(1f, dp(1.5f));
            c.drawCircle(cx, cy, r - sw / 2f, ring);
            c.drawCircle(cx, cy, r - sw / 2f, rim);

            // Sized off the view so a theme change (different dp) cannot distort it.
            float line = Math.max(1f, dp(1.5f));
            glyph.setStrokeWidth(line);
            float bw = w * 0.28f;
            float bh = h * 0.27f;
            // body top such that body + shackle, not just the body, is centred in the ring
            float sr = bw * 0.36f;
            float top = cy - (bh - sr) / 2f;
            c.drawRoundRect(cx - bw / 2f, top, cx + bw / 2f, top + bh,
                    bw * 0.22f, bw * 0.22f, glyph);

            // the shackle: the top half of an oval whose middle sits on the body's top
            // edge, so its legs land there. Narrower than the body, like the real thing.
            arc.set(cx - sr, top - sr, cx + sr, top + sr);
            c.drawArc(arc, 180f, 180f, false, glyph);
        }
    }

    private void buildPad() {
        final int padW = (int) (screenW * 0.90f);
        final int padH = (int) (screenH * 0.30f);

        FrameLayout container = new FrameLayout(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // --- drag handle -------------------------------------------------
        LinearLayout handle = new LinearLayout(this);
        handle.setGravity(Gravity.CENTER);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setCornerRadii(new float[]{dp(theme.radius), dp(theme.radius), dp(theme.radius), dp(theme.radius), 0, 0, 0, 0});
        hbg.setColor(fill(theme.panelHead));
        handle.setBackground(hbg);
        moveChip = makeChip("\u2261  MOVE");
        handle.addView(moveChip);
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy, sx, sy;
            private boolean dragged;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                // Locked: swallow the whole gesture. Returning true at DOWN matters - the
                // MOVE branch below would otherwise run on a stale grab offset.
                if (padLocked) return true;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX() - padLp.x;
                        dy = e.getRawY() - padLp.y;
                        sx = e.getRawX();
                        sy = e.getRawY();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - sx) > dp(6) || Math.abs(e.getRawY() - sy) > dp(6)) {
                            dragged = true;
                        }
                        padLp.x = (int) (e.getRawX() - dx);
                        padLp.y = (int) (e.getRawY() - dy);
                        clampGeometryToScreen(padLp);
                        try { wm.updateViewLayout(pad, padLp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragged) saveGeometry(padLp, PAD_KEY);
                        else resetPanelGeometry(pad, padLp, PAD_KEY, padDefW(), padDefH());
                        return true;
                }
                return false;
            }
        });

        // --- touch surface ------------------------------------------------
        padSurface = new PadSurface();
        padSurface.setOnTouchListener(new PadTouch());

        // --- button row ---------------------------------------------------
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        GradientDrawable bbg = new GradientDrawable();
        bbg.setCornerRadii(new float[]{0, 0, 0, 0, dp(theme.radius), dp(theme.radius), dp(theme.radius), dp(theme.radius)});
        bbg.setColor(fill(theme.panelBar));
        bar.setBackground(bbg);
        bar.setPadding(dp(30), 0, dp(30), dp(8)); // keep the corners clear for the resize grips; the dp(8) sits the buttons clearly above the pad's bottom edge

        // Action bar: back / recents / right-click / pointer toggle. Back and recents are
        // performGlobalAction calls - the same no-Shizuku mechanism as the arrow keys.
        bar.addView(makeButton("\u25C0", new Runnable() {               // back
            @Override public void run() { performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); }
        }));
        bar.addView(makeButton("\u25A6", new Runnable() {               // recents / app switcher
            @Override public void run() { performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); }
        }));
        bar.addView(makeButton("\u22EE", new Runnable() {               // right click / context menu at pointer
            @Override public void run() { rightClick(); }
        }));
        bar.addView(makeButton("\u25CF", new Runnable() {               // toggle pointer visibility
            @Override public void run() { cursor.setAlpha(cursor.getAlpha() > 0f ? 0f : 1f); }
        }));

        // arrow keys - GLOBAL_ACTION_DPAD_* injects real DPAD key events
        LinearLayout keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER);
        GradientDrawable kbg = new GradientDrawable();
        kbg.setColor(fill(theme.panelKeys));
        keys.setBackground(kbg);
        keys.setPadding(dp(30), 0, dp(30), 0);
        keys.addView(makeKey("\u2190", GLOBAL_ACTION_DPAD_LEFT));
        keys.addView(makeKey("\u2191", GLOBAL_ACTION_DPAD_UP));
        keys.addView(makeKey("\u2193", GLOBAL_ACTION_DPAD_DOWN));
        keys.addView(makeKey("\u2192", GLOBAL_ACTION_DPAD_RIGHT));

        content.addView(handle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        content.addView(padSurface, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(keys, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        content.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        container.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Two docked control dots under the handle, both on the left: the theme menu
        // outermost, the move/resize lock beside it. They used to float on screen, and
        // were briefly tried inside the handle, where they fought the drag gesture.
        addDockedDot(container, "\u25D0", theme.bubbleTheme, false, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        tick(); setThemeVisible(!themeVisible);
                    }
                });
        lockDot = new LockDot();
        lockDot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tick(); setPadLocked(!padLocked);
            }
        });
        // one round control dot per side of the pad's top: theme on the left, lock on the
        // right - they were both on the left, which left the pad's top row lopsided.
        dockDot(container, lockDot, true, 0);

        // the handle's label is the only thing that reads the lock state, and updateModeUi
        // owns that label
        updateModeUi();

        GradientDrawable rbg = new GradientDrawable();
        rbg.setCornerRadius(dp(theme.radius));
        rbg.setColor(fill(theme.padContainer));
        rbg.setStroke(dp(1.5f), theme.padStroke);
        container.setBackground(rbg);

        padLp = overlayLp(padW, padH,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        pad = container;

        // resize grips: drag any corner to resize
        // one visible grip, all four corners. includeTopLeft is true: the theme button is
        // below the handle now, clear of that corner.
        addResizeGrips(container, padLp, PAD_KEY, true);

        restoreGeometry(padLp, PAD_KEY, padDefW(), padDefH());
        try { wm.addView(pad, padLp); } catch (Exception ex) { Log.e(TAG, "pad", ex); }
        setPadVisible(false);
    }

    // =========================================================================
    // Resize grips + geometry persistence
    // =========================================================================

    /**
     * Freeze the pad's geometry, or let it go again. The grips and the drag listener stay
     * attached and simply refuse - removing them instead would be invisible in the code and
     * indistinguishable from a broken grip on the device. Edge scrolling still works while
     * locked: the lock is about the pad's geometry, not about the pointer.
     */
    private void setPadLocked(boolean locked) {
        padLocked = locked;
        if (prefs != null) prefs.edit().putBoolean(PREF_LOCK, locked).apply();
        updateModeUi();
        toast(locked ? "pad locked" : "pad unlocked");
    }

    private int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private int padDefW() { return (int) (screenW * 0.90f); }
    private int padDefH() { return (int) (screenH * 0.36f); }

    /** Short system "click" haptic, honouring the user's haptics setting. */
    private void tick() {
        try {
            if (hapticsOn == null) {
                hapticsOn = Settings.System.getInt(getContentResolver(),
                        Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1;
            }
            if (!hapticsOn) return;
            if (vib == null) vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vib == null || !vib.hasVibrator()) return;
            vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        } catch (Throwable ignored) {}
    }

    /**
     * The classic resize handle: three diagonal strokes fanning out from the corner.
     *
     * No background, so only the strokes are drawn - and it still receives touches,
     * which is all a grip needs to be.
     */
    private class GripView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        GripView() {
            super(TrackpadService.this);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            p.setColor(theme.grip);
            p.setStrokeWidth(Math.max(2f, dp(1.5f)));
            float[] f = { 0.80f, 0.56f, 0.32f };
            for (int i = 0; i < f.length; i++) {
                c.drawLine(w * f[i], h, w, h * f[i], p);
            }
        }
    }

    private View makeGrip() {
        return new GripView();
    }

    /**
     * All four corners resize; only one of them is drawn.
     *
     * A grip is nothing but a touch target - the drawing is cosmetic - so the invisible
     * ones cost nothing and mean a panel can still be resized from any corner without
     * four blobs on screen.
     *
     * includeTopLeft is false for the pad, where the hamburger button occupies that
     * corner and a grip there would swallow its taps.
     */
    private void addResizeGrips(FrameLayout container, WindowManager.LayoutParams lp,
                                String prefix, boolean includeTopLeft) {
        addGrip(container, container, lp, prefix, Gravity.BOTTOM | Gravity.RIGHT, 1, 1, true);
        if (includeTopLeft) {
            addGrip(container, container, lp, prefix, Gravity.TOP | Gravity.LEFT, -1, -1, false);
        }
        addGrip(container, container, lp, prefix, Gravity.TOP | Gravity.RIGHT, 1, -1, false);
        addGrip(container, container, lp, prefix, Gravity.BOTTOM | Gravity.LEFT, -1, 1, false);
    }

    /** dirX / dirY: -1 = this grip owns the left/top edge, +1 = right/bottom edge. */
    private void addGrip(FrameLayout parent, final View target,
                         final WindowManager.LayoutParams lp, final String prefix,
                         int gravity, final int dirX, final int dirY, boolean visible) {
        // invisible grips have no background but still receive touches, which is the
        // whole point of them
        View g = visible ? makeGrip() : new View(this);
        FrameLayout.LayoutParams flp =
                new FrameLayout.LayoutParams(dp(24), dp(24), gravity);
        flp.setMargins(dp(5), dp(5), dp(5), dp(5));
        g.setLayoutParams(flp);
        g.setOnTouchListener(new View.OnTouchListener() {
            private float sx, sy;
            private int sw, sh, ox, oy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                // A locked pad keeps its grips - an invisible grip with no listener cannot be
                // told apart from a broken one. Only the pad has a lock, so only pad
                // geometry (PAD_KEY is the empty prefix) is refused here.
                if (padLocked && PAD_KEY.equals(prefix)) return true;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        sw = lp.width;
                        sh = lp.height;
                        ox = lp.x;
                        oy = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - sx);
                        int dy = (int) (e.getRawY() - sy);
                        int nw = clampInt(sw + dirX * dx, dp(240), screenW);
                        int nh = clampInt(sh + dirY * dy, dp(120), screenH);
                        lp.width = nw;
                        lp.height = nh;
                        lp.x = (dirX < 0) ? ox + (sw - nw) : ox;
                        lp.y = (dirY < 0) ? oy + (sh - nh) : oy;
                        clampGeometryToScreen(lp);
                        try { wm.updateViewLayout(target, lp); } catch (Exception ignored) {}
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        saveGeometry(lp, prefix);
                        return true;
                }
                return false;
            }
        });
        parent.addView(g);
    }

    private void saveGeometry(WindowManager.LayoutParams lp, String prefix) {
        if (lp == null || prefs == null) return;
        prefs.edit()
                .putInt(prefix + "w", lp.width)
                .putInt(prefix + "h", lp.height)
                .putInt(prefix + "x", lp.x)
                .putInt(prefix + "y", lp.y)
                .apply();
    }

    private void restoreGeometry(WindowManager.LayoutParams lp, String prefix,
                                 int defW, int defH) {
        int w = prefs.getInt(prefix + "w", defW);
        int h = prefs.getInt(prefix + "h", defH);
        lp.width = w;
        lp.height = h;
        lp.x = prefs.getInt(prefix + "x", (screenW - w) / 2);
        lp.y = prefs.getInt(prefix + "y", screenH - h - dp(48));
        clampGeometryToScreen(lp);
    }

    /**
     * As restoreGeometry, but with an explicit default position instead of the generic
     * "centred, near the bottom". Only applies when nothing is saved yet, so it decides
     * where the panel first appears rather than where it stays.
     */
    /** Re-centre a panel at its default size. */
    private void resetPanelGeometry(View target, WindowManager.LayoutParams lp,
                                    String prefix, int defW, int defH) {
        if (lp == null) return;
        lp.width = defW;
        lp.height = defH;
        lp.x = (screenW - defW) / 2;
        lp.y = screenH - defH - dp(48);
        clampGeometryToScreen(lp);
        try { wm.updateViewLayout(target, lp); } catch (Exception ignored) {}
        saveGeometry(lp, prefix);
    }

    private void clampGeometryToScreen(WindowManager.LayoutParams lp) {
        if (lp == null) return;
        lp.width = clampInt(lp.width, dp(240), screenW);
        lp.height = clampInt(lp.height, dp(120), screenH);
        lp.x = clampInt(lp.x, 0, Math.max(0, screenW - lp.width));
        lp.y = clampInt(lp.y, 0, Math.max(0, screenH - lp.height));
    }

    private TextView makeChip(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(theme.textSecondary);
        t.setTextSize(10f);
        // every chip is a panel title bar, so it belongs centred in its space - without
        // this it sits left-aligned, which is noticeable on the pad once the theme button
        // and its matching spacer take a bite out of either end
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(10), 0);
        return t;
    }

    /** Arrow keys: performGlobalAction(GLOBAL_ACTION_DPAD_*) injects a real DPAD key event.
     *  Hold to auto-repeat, like any other repeating key. */
    private TextView makeKey(String label, final int action) {
        return makeRepeatButton(label, new Runnable() {
            @Override public void run() {
                boolean ok = performGlobalAction(action);
                Log.i(TAG, "dpad action=" + action + " accepted=" + ok);
            }
        });
    }

    /** A trackpad-row button that auto-repeats while held. */
    private TextView makeRepeatButton(String label, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(theme.textPrimary);
        t.setTextSize(17f);
        t.setGravity(Gravity.CENTER);
        t.setBackground(keyBgState(0x00000000, theme.accent));
        t.setClickable(true);
        // dp(1) hairline between the pad's bottom buttons, the same seam makeButton adds.
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.rightMargin = dp(1);
        t.setLayoutParams(lp);
        attachRepeat(t, action, true);
        return t;
    }

    private void cancelRepeat(Runnable[] rep) {
        if (rep[0] != null) {
            ui.removeCallbacks(rep[0]);
            rep[0] = null;
        }
    }

    /** Press and hold -> auto-repeat, exactly like a real keyboard key. */
    private void attachRepeat(final View v, final Runnable action, final boolean repeatable) {
        final Runnable[] rep = new Runnable[1];
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.setPressed(true);
                        tick();                     // haptic once, not per repeat
                        action.run();
                        cancelRepeat(rep);
                        if (repeatable) {
                            rep[0] = new Runnable() {
                                @Override public void run() {
                                    action.run();
                                    ui.postDelayed(this, REPEAT_MS);
                                }
                            };
                            ui.postDelayed(rep[0], REPEAT_DELAY_MS);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.setPressed(false);
                        cancelRepeat(rep);
                        view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        view.setPressed(false);
                        cancelRepeat(rep);
                        return true;
                }
                return false;
            }
        });
    }

    private GradientDrawable keyShape(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(fill);
        g.setStroke(dp(1f), stroke);
        return g;
    }

    /** Key background that visibly changes colour while the key is held down. */
    private StateListDrawable keyBgState(int normal, int pressed) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, keyShape(pressed, theme.keyStrokePressed));
        s.addState(new int[]{}, keyShape(normal, theme.keyStroke));
        return s;
    }

    private GradientDrawable keyShapeRect(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(1f), stroke);
        return g;
    }

    /** Square-cornered key background for list rows: the borders have no rounding. */
    private StateListDrawable keyBgStateRect(int normal, int pressed) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, keyShapeRect(pressed, theme.keyStrokePressed));
        s.addState(new int[]{}, keyShapeRect(normal, theme.keyStroke));
        return s;
    }

    private TextView makeButton(String label, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(theme.textPrimary);
        t.setTextSize(17f);
        t.setGravity(Gravity.CENTER);
        t.setBackground(keyBgState(0x00000000, theme.accent));
        // dp(1) hairline between the pad's bottom buttons - carried over from Z Trackpad;
        // the row's own side padding swallows the outer margin, so only the seams show.
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.rightMargin = dp(1);
        t.setLayoutParams(lp);
        t.setPadding(dp(4), 0, dp(4), 0);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tick(); action.run(); }
        });
        return t;
    }

    private void setPadVisible(boolean visible) {
        padVisible = visible;
        if (pad == null) return;
        pad.setVisibility(visible ? View.VISIBLE : View.GONE);
        updateCursorAlpha();
    }

    private void toast(final String msg) {
        Log.i(TAG, msg);
    }

    // =========================================================================
    // Pointer movement + gesture injection
    // =========================================================================

    private float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private void moveCursor(float dx, float dy) {
        // Finger deltas and pointer coordinates are the same space: there is one display.
        cursorX = clamp(cursorX + dx * sensitivity, 0, screenW - 1);
        cursorY = clamp(cursorY + dy * sensitivity, 0, screenH - 1);
        if (cursor == null) return;
        cursorLp.x = (int) cursorX;
        cursorLp.y = (int) cursorY;
        try { wm.updateViewLayout(cursor, cursorLp); } catch (Exception ignored) {}
    }

    private boolean sendStroke(GestureDescription.StrokeDescription s) { return sendStroke(s, null); }

    private boolean sendStroke(GestureDescription.StrokeDescription s,
                              AccessibilityService.GestureResultCallback cb) {
        try {
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(s);
            final boolean isDragChunk = (s.getDuration() == CHUNK_MS);
            AccessibilityService.GestureResultCallback use =
                    (cb != null) ? cb : (isDragChunk ? chunkCb : null);
            boolean ok = dispatchGesture(b.build(), use, null);
            if (!ok && isDragChunk) Log.w(TAG, "drag: dispatch refused (gesture already in flight)");
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "stroke failed: " + e);
            return false;
        }
    }

    /** Reports whether the platform actually accepted our drag chunks or cancelled them. */
    private final AccessibilityService.GestureResultCallback chunkCb =
            new AccessibilityService.GestureResultCallback() {
        @Override public void onCompleted(GestureDescription g) {
            Log.d(TAG, "drag chunk: completed");
        }
        @Override public void onCancelled(GestureDescription g) {
            Log.w(TAG, "drag chunk: CANCELLED by platform - will restart");
            stroke = null;   // keep dragging; the heartbeat restarts the chain
        }
    };

    /**
     * The panels are touchable overlays, so an injected click aimed at a desktop icon
     * sitting under one would land on the panel instead. Briefly drop FLAG_NOT_TOUCHABLE
     * so the event dispatches to the app underneath, then restore it.
     *
     * Only used from the tap paths (click / long press / right click), which all fire on
     * finger-UP - so there is no in-flight gesture of ours to disturb. The drag path must
     * NOT do this, because there the finger is still down on the panel.
     */
    /**
     * Make the panels non-touchable, inject, then put them back.
     *
     * `updateViewLayout` only *queues* the flag change - it returns before the window
     * manager has applied it - so injecting straight afterwards raced it and the pad still
     * swallowed the tap whenever the pointer sat over one of our own panels. Measured in
     * split screen, where the pad covers most of a pane: the same click that worked with
     * the pointer clear of the pad did nothing underneath it.
     *
     * Two consequences: the injection is posted a couple of frames late, and the flag stays
     * up for as long as the gesture lasts plus a margin - a long press needs all of
     * HOLD_MS, or its UP arrives at a window that has gone touchable again.
     */
    /**
     * Restores the pad's touchability as soon as the injected stroke is done with the pad.
     *
     * The timed restore below stays as a safety net; this callback shortens the window
     * whenever the platform reports completion early - and, crucially, when it *cancels*
     * the stroke (which happens when a real finger lands on the pad mid-injection): the
     * pad goes straight back to touchable instead of staying open for the whole gesture,
     * so a follow-up touch lands on the pad, not on the app underneath.
     */
    private final AccessibilityService.GestureResultCallback throughPanelsCb =
            new AccessibilityService.GestureResultCallback() {
        @Override public void onCompleted(GestureDescription g) { setPanelsTouchable(true); }
        @Override public void onCancelled(GestureDescription g) { setPanelsTouchable(true); }
    };

    private void injectThroughPanels(GestureDescription.StrokeDescription stroke, final long gestureMs) {
        setPanelsTouchable(false);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!sendStroke(stroke, throughPanelsCb)) setPanelsTouchable(true);
                ui.postDelayed(new Runnable() {
                    @Override public void run() { setPanelsTouchable(true); }
                }, gestureMs + TOUCHABLE_SETTLE_MS);
            }
        }, TOUCHABLE_SETTLE_MS);
    }

    private void setPanelsTouchable(boolean touchable) {
        final int F = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (pad != null && padLp != null) {
            padLp.flags = touchable ? (padLp.flags & ~F) : (padLp.flags | F);
            try { wm.updateViewLayout(pad, padLp); } catch (Exception ignored) {}
        }
        Log.i(TAG, "panels touchable=" + touchable);
    }

    private void click() {
        Log.i(TAG, "click at " + (int) cursorX + "," + (int) cursorY);
        // Through the panels, not over them: the tap has to reach whatever is under the
        // pad. The finger is already up, so dropping FLAG_NOT_TOUCHABLE is safe here - and
        // the drag path above deliberately does not do it, because there the finger is down.
        Path p = new Path();
        p.moveTo(cursorX, cursorY);
        injectThroughPanels(new GestureDescription.StrokeDescription(p, 0, CLICK_MS), CLICK_MS);
    }

    private void longPress() {
        Log.i(TAG, "longpress at " + (int) cursorX + "," + (int) cursorY);
        Path p = new Path();
        p.moveTo(cursorX, cursorY);
        injectThroughPanels(new GestureDescription.StrokeDescription(p, 0, HOLD_MS), HOLD_MS);
    }

    private void rightClick() {
        Log.i(TAG, "right click at " + (int) cursorX + "," + (int) cursorY);
        longPress();   // the best a touch gesture can do
    }

    /** The two-finger drag: `SCROLL_GAIN` per px of finger travel. */
    private void scrollBy(float dy) {
        pendingScrollY += dy * SCROLL_GAIN;
    }

    /** The edge strips: `EDGE_GAIN` per px of finger travel. */
    private void scrollByEdge(float dy) {
        pendingScrollY += dy * EDGE_GAIN;
    }

    /**
     * Spend the scroll a gesture asked for, once the finger is up.
     *
     * This is the only reason the strips work at all. dispatchGesture() cannot inject while a
     * real touch is in progress: the platform accepts the stroke and then cancels it, and the
     * injected gesture cancels the real touch stream driving it, so a finger-down scroll gets
     * exactly one flush and then goes deaf. Measured on this device, an 800px swipe down the
     * strip produced one accepted flush and nothing else, and the list underneath never moved.
     *
     * Taps survive that for the same reason this works: click() runs on ACTION_UP, when the
     * finger is already up. So a gesture accumulates the distance it wants here and spends it
     * on release, as a single stroke.
     *
     * The cost is real and worth stating plainly: the scroll lands when you lift your finger
     * instead of following it. That is a worse feel than upstream's, and it is the ceiling for
     * a build with no shell process to inject through - the alternative is a strip that does
     * nothing at all, which is what it did before this.
     */
    private void flushPendingScroll() {
        float move = pendingScrollY;
        pendingScrollY = 0f;
        if (move == 0f) return;
        // One stroke has to carry the whole gesture, so cap it: a long drag is a fling, and a
        // fling over a full screen carries on scrolling after the finger is long gone.
        float cap = screenH * SCROLL_CAP_SCREEN;
        if (move > cap) move = cap;
        if (move < -cap) move = -cap;
        // The banked distance is already proportional to the finger, so this is only a floor
        // for the whole gesture rather than something a fast swipe trips over constantly.
        if (Math.abs(move) < dp(MIN_SCROLL_DP)) return;
        Log.i(TAG, "scroll on release move=" + (int) move);
        Path p = new Path();
        p.moveTo(cursorX, cursorY);
        p.lineTo(cursorX, clamp(cursorY + move, 0, screenH - 1));
        sendStroke(new GestureDescription.StrokeDescription(p, 0, SCROLL_MS));
    }

    // ------------------------------------------------------------------
    // Drag. Built from continued accessibility strokes, so it is approximate:
    // dispatchGesture() can cancel the touch stream driving it, and the
    // heartbeat below is what restarts it in place.
    // ------------------------------------------------------------------

    /* ------------------------------------------------------------------
     * Press-and-hold drag, built from continued strokes.
     * A StrokeDescription is only valid for its declared duration, so a
     * heartbeat re-continues it every DRAG_BEAT_MS. That is what lets a
     * drag survive the user pausing mid-drag.
     * ------------------------------------------------------------------ */

    private boolean beginDrag() {
        Path p = new Path();
        p.moveTo(cursorX, cursorY);
        GestureDescription.StrokeDescription s =
                new GestureDescription.StrokeDescription(p, 0, CHUNK_MS, true);
        if (!sendStroke(s)) return false;
        stroke = s;
        strokeEndX = cursorX;
        strokeEndY = cursorY;
        lastMoveAt = SystemClock.uptimeMillis();
        startBeat();
        Log.i(TAG, "drag: begin at " + (int) cursorX + "," + (int) cursorY);
        return true;
    }

    private void dragTo() {
        if (stroke == null) return;
        try {
            Path p = new Path();
            p.moveTo(strokeEndX, strokeEndY);   // MUST match where the last chunk ended
            p.lineTo(cursorX, cursorY);
            GestureDescription.StrokeDescription next = stroke.continueStroke(p, 0, CHUNK_MS, true);
            if (sendStroke(next)) {
                stroke = next;
                strokeEndX = cursorX;
                strokeEndY = cursorY;
            }
        } catch (Exception e) {
            Log.w(TAG, "drag: chain broke (" + e.getClass().getSimpleName() + ") - will restart");
            stroke = null;   // heartbeat / next move restarts the chain in place
        }
    }

    private void endDrag() {
        stopBeat();
        if (stroke != null) {
            try {
                Path p = new Path();
                p.moveTo(strokeEndX, strokeEndY);
                p.lineTo(cursorX, cursorY);
                sendStroke(stroke.continueStroke(p, 0, 100, false));
            } catch (Exception ignored) {}
            stroke = null;
            Log.i(TAG, "drag: end at " + (int) cursorX + "," + (int) cursorY);
        }
    }

    private void startBeat() {
        if (beat == null) {
            beat = new Runnable() {
                @Override public void run() {
                    if (!dragging) return;
                    // safety: never leave an app holding a press if touches stop arriving
                    if (SystemClock.uptimeMillis() - lastMoveAt > DRAG_STALL_MS) {
                        Log.i(TAG, "drag: stalled -> auto-release");
                        endDrag();
                        dragging = false;
                        updateModeUi();
                        return;
                    }
                    if (stroke == null) beginDrag();   // restart a broken chain
                    else dragTo();
                    if (dragging) ui.postDelayed(this, DRAG_BEAT_MS);
                }
            };
        }
        ui.removeCallbacks(beat);
        ui.postDelayed(beat, DRAG_BEAT_MS);
    }

    private void stopBeat() {
        if (beat != null) ui.removeCallbacks(beat);
    }

    /** Hold still for DRAG_HOLD_MS, then move -> drag. No mode switch needed. */
    private void scheduleHold() {
        cancelHold();
        holdRun = new Runnable() {
            @Override public void run() {
                holdRun = null;
                holdReady = true;
            }
        };
        ui.postDelayed(holdRun, DRAG_HOLD_MS);
    }

    private void cancelHold() {
        holdReady = false;
        if (holdRun != null) { ui.removeCallbacks(holdRun); holdRun = null; }
    }

    private void updateModeUi() {
        if (cursor != null) cursor.setHot(dragging);
        if (moveChip != null) {
            // This is the only writer of the handle's label: a transient gesture wins, then
            // the lock, then the armed/move states. The lock used to set the text itself,
            // and the next drag promptly overwrote it back to MOVE.
            String mode = edgeScroll ? "\u2261  SCROLL"
                    : (dragging ? "\u2261  DRAGGING"
                            : (padLocked ? "\u2261  LOCKED"
                                    : (dragArmed ? "\u2261  DRAG ARMED" : "\u2261  MOVE")));
            moveChip.setText(mode);
        }
    }

    // =========================================================================
    // Trackpad touch handling
    // =========================================================================

    /**
     * The pad's touch surface: the themed body plus a column of dots down the middle of
     * each edge-scroll strip - the laptop-trackpad affordance that says "drag here to
     * scroll", carried over from Z Trackpad. Each strip is EDGE_SCROLL_DP wide, so each
     * column sits dp(EDGE_SCROLL_DP)/2 in from its edge. Dots on purpose, not dashes:
     * a dashed line reads as a divider, and a dotted one as "use this edge". Always on
     * in Lite: there is no CONTROLS panel to offer a switch, and the strips are the
     * whole point of this build.
     */
    private class PadSurface extends View {
        private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);

        PadSurface() {
            super(TrackpadService.this);
            GradientDrawable sbg = new GradientDrawable();
            sbg.setColor(fill(theme.panelBody));
            setBackground(sbg);
            mark.setColor(theme.scrollMark);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float x = dp(EDGE_SCROLL_DP) / 2f;
            float r = dp(1.4f);
            float pitch = dp(12f);
            // The docked control dots (theme left, lock beside it) sit just under the
            // handle, dp(8) down from the surface's top and dp(26) tall. The columns have
            // to start below them, and two dots are dropped from the top of each column
            // on purpose: dp(8) + dp(26) + dp(6) + two dp(12) pitches = dp(64), so what
            // remains is clearly clear of the buttons rather than brushing past them.
            float y = dp(64f);
            float bottom = getHeight() - dp(12f);
            while (y <= bottom) {
                c.drawCircle(x, y, r, mark);
                c.drawCircle(getWidth() - x, y, r, mark);
                y += pitch;
            }
        }
    }

    private class PadTouch implements View.OnTouchListener {

        private float centroidX(MotionEvent e) {
            float s = 0;
            for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i);
            return s / e.getPointerCount();
        }

        private float centroidY(MotionEvent e) {
            float s = 0;
            for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
            return s / e.getPointerCount();
        }

        // two-finger tap == right click (context menu), tracked separately from two-finger scroll
        private float twoX, twoY;
        private boolean twoMoved;
        private long twoAt;
        // A right click is announced when the first finger lifts but fired only on the
        // final ACTION_UP: firing at POINTER_UP would open the click-through window while
        // the second finger is still down, and that finger's touches would then land on
        // the app underneath. secondMoved records whether the remaining finger moved
        // while we waited - if it did, the tap was no longer a right click.
        private boolean pendingRightClick;
        private boolean secondMoved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {

                case MotionEvent.ACTION_DOWN:
                    boolean quickReturn = (SystemClock.uptimeMillis() - lastTapUpAt) < DOUBLE_TAP_MS;
                    // A touch landing in an edge strip scrolls, and nothing else: no cursor,
                    // no click, no drag. Decided here at DOWN, because the hold timer and
                    // the tap-then-drag window would otherwise claim a slow edge swipe as a
                    // press-and-drag. The strip is scroll-only on purpose - the pointer is
                    // somewhere else entirely, so a click from here would land somewhere
                    // the finger never was.
                    edgeScroll = (e.getX() < dp(EDGE_SCROLL_DP)
                            || e.getX() > v.getWidth() - dp(EDGE_SCROLL_DP));
                    Log.i(TAG, "down pad=" + v.getWidth() + "x" + v.getHeight()
                            + " at " + (int) e.getX() + "," + (int) e.getY()
                            + " dragArmed=" + dragArmed + " tapDrag=" + quickReturn
                            + (edgeScroll ? " EDGE" : ""));
                    downX = lastX = e.getX();
                    downY = lastY = e.getY();
                    downTime = SystemClock.uptimeMillis();
                    moved = false;
                    scrollMode = false;
                    pendingScrollY = 0f;
                    twoMoved = false;
                    pendingRightClick = false;
                    secondMoved = false;
                    cancelHold();
                    if (edgeScroll) {
                        updateModeUi();
                    } else if (dragArmed) {
                        if (beginDrag()) { dragging = true; updateModeUi(); }
                    } else if (quickReturn) {
                        // tapped a moment ago -> this touch drags as soon as it moves
                        tapDrag = true;
                    } else {
                        scheduleHold();
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    scrollMode = true;
                    edgeScroll = false;
                    twoMoved = false;
                    pendingRightClick = false;
                    secondMoved = false;
                    twoX = centroidX(e);
                    twoY = centroidY(e);
                    twoAt = SystemClock.uptimeMillis();
                    lastX = twoX;
                    lastY = twoY;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    lastMoveAt = SystemClock.uptimeMillis();
                    if (e.getPointerCount() >= 2 || scrollMode) {
                        float cx = centroidX(e), cy = centroidY(e);
                        if (Math.abs(cx - twoX) > SLOP * 2 || Math.abs(cy - twoY) > SLOP * 2) {
                            twoMoved = true;
                        }
                        if (twoMoved && !dragging) {
                            // held both fingers still first, then moved -> press-and-drag
                            boolean held = holdReady;
                            cancelHold();
                            if (held && !dragArmed) {
                                if (beginDrag()) { dragging = true; updateModeUi(); }
                            }
                        }
                        if (dragging) {
                            moveCursor(cx - lastX, cy - lastY);
                            dragTo();
                        } else {
                            scrollBy(cy - lastY);   // immediate two-finger movement = scroll
                        }
                        lastX = cx;
                        lastY = cy;
                        return true;
                    }
                    float x = e.getX(), y = e.getY();
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x;
                    lastY = y;
                    // a right click is pending only until the remaining finger moves
                    if (pendingRightClick && (Math.abs(dx) > SLOP || Math.abs(dy) > SLOP)) {
                        secondMoved = true;
                    }
                    if (edgeScroll) {
                        // same sign convention as the two-finger drag: swipe up, content down
                        scrollByEdge(dy);
                        // pretend it moved, so ACTION_UP cannot turn this into a click
                        moved = true;
                        return true;
                    }
                    if (!moved && (Math.abs(x - downX) > SLOP || Math.abs(y - downY) > SLOP)) {
                        boolean wasHeld = holdReady;
                        boolean wasTapDrag = tapDrag;
                        moved = true;
                        cancelHold();
                        // held still, or tapped just before -> start a real press-and-drag
                        if (!dragging && !dragArmed && (wasHeld || wasTapDrag)) {
                            Log.i(TAG, "drag trigger (held=" + wasHeld + " tapDrag=" + wasTapDrag + ")");
                            if (beginDrag()) { dragging = true; updateModeUi(); }
                        }
                    }
                    if (moved) moveCursor(dx, dy);
                    if (dragging) {
                        if (stroke == null) beginDrag();
                        dragTo();
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    if (e.getPointerCount() <= 2) {
                        scrollMode = false;
                        // two fingers down and never moved == right click (no duration limit,
                        // otherwise a slow two-finger tap falls into a dead zone). Announced
                        // here, fired on ACTION_UP: firing now would open the click-through
                        // window while the second finger is still down (see pendingRightClick).
                        if (!twoMoved) {
                            pendingRightClick = true;
                            secondMoved = false;
                        }
                        // remaining finger becomes a move origin again
                        int keep = (e.getActionIndex() == 0) ? 1 : 0;
                        lastX = e.getX(keep);
                        lastY = e.getY(keep);
                        moved = true;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (pendingRightClick) {
                        pendingRightClick = false;
                        cancelHold();
                        tapDrag = false;
                        if (!secondMoved) rightClick();
                        lastTapUpAt = 0L;   // a right click must not arm the tap-then-drag window
                        return true;
                    }
                    if (edgeScroll) {
                        edgeScroll = false;
                        tapDrag = false;
                        flushPendingScroll();
                        // an edge tap must not arm the tap-then-drag window
                        lastTapUpAt = 0L;
                        updateModeUi();
                        return true;
                    }
                    if (dragging) {
                        endDrag();
                        dragging = false;
                        cancelHold();
                        tapDrag = false;
                        lastTapUpAt = 0L;
                        updateModeUi();
                        return true;
                    }
                    cancelHold();
                    if (scrollMode) { scrollMode = false; tapDrag = false; flushPendingScroll(); return true; }
                    long dt = SystemClock.uptimeMillis() - downTime;
                    if (!moved) {
                        // a second tap that never moved is still a double click
                        if (tapDrag || dt < LONGPRESS_MS) click();
                        else longPress();
                        lastTapUpAt = tapDrag ? 0L : SystemClock.uptimeMillis();
                    } else {
                        lastTapUpAt = 0L;
                    }
                    tapDrag = false;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    cancelHold();
                    tapDrag = false;
                    pendingRightClick = false;
                    secondMoved = false;
                    edgeScroll = false;
                    if (dragging) { endDrag(); dragging = false; updateModeUi(); }
                    scrollMode = false;
                    flushPendingScroll();
                    return true;
            }
            return false;
        }
    }

    // =========================================================================
    // Floating windows ("pop-up view") - the task switcher
    //
    // Overlapping floating windows have no way to reach each other: the one at the back
    // is simply hidden, and there is no affordance that names it. This is the entry point
    // for one - a bubble that lists them and puts the chosen one in front.
    //
    // Scoped to the display the panels live on, which is the unfolded screen while the
    // phone is open. On the cover screen the list is simply empty: display 1 reports
    // canHostTasks=false, so nothing floats there to list. DeX and cross-display listing
    // are deliberately not attempted yet.
    // =========================================================================
}
