package com.mirrornote.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQ_PICK_WALLPAPER = 204;

    private static final String PREFS_NAME = "mirror_note_prefs";
    private static final String KEY_PINNED = "pinned_files";
    private static final String KEY_FAV = "fav_files";
    private static final String KEY_CREATED_MAP = "created_map_json";
    private static final String KEY_SORT_MODE = "sort_mode";
    private static final String KEY_FONT_SIZE = "font_size_sp";
    private static final String KEY_DARK_GLASS = "dark_glass_mode";
    private static final String KEY_GLASS_ALPHA = "glass_alpha";
    private static final String KEY_BORDER_ALPHA = "border_alpha";
    private static final String KEY_WP_DIM = "wp_dim";
    private static final String KEY_CORNER_RADIUS = "corner_radius_dp";
    private static final String KEY_WP_PATH = "wallpaper_file_path";

    private static final int SORT_EDITED = 0;
    private static final int SORT_CREATED = 1;
    private static final int SORT_ALPHA = 2;

    private static class NoteEntry {
        final File file;
        final String fileName;
        final String title;
        final String body;
        final long lastModified;
        final long createdAt;
        final boolean pinned;
        final boolean favourite;

        NoteEntry(File file, String fileName, String title, String body,
                  long lastModified, long createdAt, boolean pinned, boolean favourite) {
            this.file = file;
            this.fileName = fileName;
            this.title = title;
            this.body = body;
            this.lastModified = lastModified;
            this.createdAt = createdAt;
            this.pinned = pinned;
            this.favourite = favourite;
        }
    }

    private FrameLayout rootContainer;
    private ImageView wallpaperImageView;
    private View wallpaperDimOverlay;

    private View homeScreen;
    private View editorScreen;
    private View settingsScreen;
    private View editorBottomBar;

    private TextView homeTitleView;
    private TextView countSummaryView;
    private EditText searchInput;
    private View clearSearchBtn;
    private LinearLayout notesListContainer;

    private EditText editorTitleInput;
    private EditText editorBodyInput;
    private TextView editorTimestampView;
    private TextView editorMetaView;

    private TextView settingsPermStatus;
    private TextView settingsTotalNotes;
    private TextView settingsFontLabel;
    private TextView settingsWpPathLabel;
    private TextView settingsThemeModeLabel;

    private SharedPreferences prefs;
    private File notesFolder;
    private List<NoteEntry> allNotes = new ArrayList<>();

    private int sortMode = SORT_EDITED;
    private int defaultFontSize = 17;
    private boolean darkGlassMode = false; // false = White Glass, true = Black Glass
    private int glassAlpha = 195;          // 0..255
    private int borderAlpha = 45;          // 0..255
    private int wallpaperDim = 22;         // 0..85
    private int cornerRadiusDp = 22;       // 8..34

    private String currentEditingFileName = null;
    private String lastSavedSignature = "";
    private boolean isApplyingUndoRedo = false;
    private final List<String> undoStack = new ArrayList<>();
    private final List<String> redoStack = new ArrayList<>();

    private byte[] lastDeletedBytes = null;
    private String lastDeletedName = null;

    private final Handler autosaveHandler = new Handler(Looper.getMainLooper());
    private final Runnable autosaveRunnable = this::performAutosave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sortMode = prefs.getInt(KEY_SORT_MODE, SORT_EDITED);
        defaultFontSize = prefs.getInt(KEY_FONT_SIZE, 17);
        darkGlassMode = prefs.getBoolean(KEY_DARK_GLASS, false);
        glassAlpha = prefs.getInt(KEY_GLASS_ALPHA, 195);
        borderAlpha = prefs.getInt(KEY_BORDER_ALPHA, 45);
        wallpaperDim = prefs.getInt(KEY_WP_DIM, 22);
        cornerRadiusDp = prefs.getInt(KEY_CORNER_RADIUS, 22);

        setupWindowAndTheme();
        buildRootLayout();
        applySavedWallpaperAndDim();
        attachKeyboardLiftListener();
        showHomeScreen();
        checkPermissionAndLoad(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermissionAndLoad(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        autosaveHandler.removeCallbacks(autosaveRunnable);
        if (editorScreen != null && editorScreen.getVisibility() == View.VISIBLE) {
            performAutosave();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_WALLPAPER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importWallpaperFromUri(data.getData());
        }
    }

    private int bgColor() {
        return darkGlassMode ? 0xFF07090D : 0xFFF7F9FC;
    }

    private int primaryTextColor() {
        return darkGlassMode ? 0xFFFFFFFF : 0xFF0F172A;
    }

    private int secondaryTextColor() {
        return darkGlassMode ? 0xFFA1A1AA : 0xFF64748B;
    }

    private int accentColor() {
        return darkGlassMode ? 0xFFAAB8FF : 0xFF2563EB;
    }

    private void setupWindowAndTheme() {
        int bg = bgColor();
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (darkGlassMode) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable glassDrawable(int radiusDp, boolean accentBorder) {
        GradientDrawable d = new GradientDrawable();
        int alpha = Math.max(0, Math.min(255, glassAlpha));
        int baseRgb = darkGlassMode ? 0x00141822 : 0x00FFFFFF;
        d.setColor((alpha << 24) | baseRgb);
        d.setCornerRadius(dp(radiusDp));
        int bAlpha = Math.max(0, Math.min(255, borderAlpha));
        int bRgb = darkGlassMode ? 0x00FFFFFF : 0x000F172A;
        int stroke = accentBorder ? accentColor() : ((bAlpha << 24) | bRgb);
        d.setStroke(dp(1), stroke);
        return d;
    }

    private GradientDrawable accentPillDrawable(int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(accentColor());
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private void applyAndroid17Press(View v, boolean isFab) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate()
                            .scaleX(isFab ? 0.90f : 0.965f)
                            .scaleY(isFab ? 0.90f : 0.965f)
                            .translationY(dp(1.5f))
                            .alpha(0.88f)
                            .setDuration(120)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(250)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.22f))
                            .start();
                    break;
            }
            return false;
        });
    }

    private void animateScreenIn(View screen) {
        screen.setVisibility(View.VISIBLE);
        screen.setAlpha(0f);
        screen.setScaleX(0.985f);
        screen.setScaleY(0.985f);
        screen.setTranslationY(dp(14));
        screen.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2.0f))
                .start();
    }

    private TextView makeText(String text, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif",
                bold ? Typeface.BOLD : Typeface.NORMAL));
        return tv;
    }

    private void buildRootLayout() {
        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(bgColor());
        rootContainer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        wallpaperImageView = new ImageView(this);
        wallpaperImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperImageView.setVisibility(View.GONE);
        rootContainer.addView(wallpaperImageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        wallpaperDimOverlay = new View(this);
        rootContainer.addView(wallpaperDimOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        homeScreen = buildHomeScreen();
        editorScreen = buildEditorScreen();
        settingsScreen = buildSettingsScreen();

        editorScreen.setVisibility(View.GONE);
        settingsScreen.setVisibility(View.GONE);

        rootContainer.addView(homeScreen);
        rootContainer.addView(editorScreen);
        rootContainer.addView(settingsScreen);

        setContentView(rootContainer);
    }

    // Ensures bottom bar lifts cleanly above open keyboard even on OEM/gesture devices
    private void attachKeyboardLiftListener() {
        if (rootContainer == null) return;
        rootContainer.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (editorBottomBar == null || editorScreen == null || editorScreen.getVisibility() != View.VISIBLE) {
                return;
            }
            Rect r = new Rect();
            rootContainer.getWindowVisibleDisplayFrame(r);
            int screenHeight = rootContainer.getRootView().getHeight();
            int keypadHeight = screenHeight - r.bottom;
            float lift = keypadHeight > dp(120) ? -keypadHeight + dp(12) : 0f;
            if (Math.abs(editorBottomBar.getTranslationY() - lift) > 1f) {
                editorBottomBar.animate()
                        .translationY(lift)
                        .setDuration(180)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                        .start();
            }
        });
    }

    // ==================== WALLPAPER & THEME ====================
    private void pickWallpaperImage() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_PICK_WALLPAPER);
    }

    private void importWallpaperFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return;
            Bitmap decoded = BitmapFactory.decodeStream(is);
            if (decoded == null) {
                Toast.makeText(this, "Could not read image", Toast.LENGTH_SHORT).show();
                return;
            }
            int maxDim = 1280;
            int w = decoded.getWidth();
            int h = decoded.getHeight();
            if (w > maxDim || h > maxDim) {
                float ratio = Math.min((float) maxDim / w, (float) maxDim / h);
                w = Math.round(w * ratio);
                h = Math.round(h * ratio);
                decoded = Bitmap.createScaledBitmap(decoded, w, h, true);
            }
            File outFile = new File(getFilesDir(), "mirror_note_wallpaper.jpg");
            try (FileOutputStream fos = new FileOutputStream(outFile, false)) {
                decoded.compress(Bitmap.CompressFormat.JPEG, 82, fos);
                fos.flush();
            }
            prefs.edit().putString(KEY_WP_PATH, outFile.getAbsolutePath()).apply();
            applySavedWallpaperAndDim();
            refreshSettingsLabels();
            Toast.makeText(this, "Wallpaper set", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Wallpaper failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void clearWallpaperImage() {
        String path = prefs.getString(KEY_WP_PATH, "");
        if (path != null && !path.isEmpty()) {
            File f = new File(path);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        prefs.edit().remove(KEY_WP_PATH).apply();
        applySavedWallpaperAndDim();
        refreshSettingsLabels();
        Toast.makeText(this, "Wallpaper cleared", Toast.LENGTH_SHORT).show();
    }

    private void applySavedWallpaperAndDim() {
        String path = prefs.getString(KEY_WP_PATH, "");
        if (wallpaperImageView != null) {
            if (path != null && !path.isEmpty() && new File(path).exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(path);
                if (bmp != null) {
                    wallpaperImageView.setImageDrawable(new BitmapDrawable(getResources(), bmp));
                    wallpaperImageView.setVisibility(View.VISIBLE);
                } else {
                    wallpaperImageView.setVisibility(View.GONE);
                }
            } else {
                wallpaperImageView.setImageDrawable(null);
                wallpaperImageView.setVisibility(View.GONE);
            }
        }
        if (wallpaperDimOverlay != null) {
            int alpha = Math.round((wallpaperDim / 100f) * 210f);
            int tint = darkGlassMode ? Color.argb(alpha, 0, 0, 0) : Color.argb(alpha, 255, 255, 255);
            wallpaperDimOverlay.setBackgroundColor(tint);
        }
    }

    private void rebuildScreensWithCurrentTheme() {
        setupWindowAndTheme();
        applySavedWallpaperAndDim();
        buildRootLayout();
        applySavedWallpaperAndDim();
        attachKeyboardLiftListener();
        showSettingsScreen();
        checkPermissionAndLoad(false);
    }

    // ==================== CLEAN HOME SCREEN ====================
    private View buildHomeScreen() {
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(96));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(28), dp(20), dp(24));
        scroll.addView(page);

        // Clean Header: Logo + App Name + Notes count (no corner wallpaper/settings clutter)
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView logoBadge = makeText("M", 15, darkGlassMode ? 0xFF07090D : Color.WHITE, true);
        logoBadge.setGravity(Gravity.CENTER);
        logoBadge.setBackground(accentPillDrawable(12));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        logoLp.rightMargin = dp(10);
        topBar.addView(logoBadge, logoLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        homeTitleView = makeText("Mirror Note", 26, primaryTextColor(), true);
        countSummaryView = makeText("0 notes", 12, secondaryTextColor(), false);
        titleCol.addView(homeTitleView);
        titleCol.addView(countSummaryView);
        topBar.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        page.addView(topBar);

        // Search Bar
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(glassDrawable(18, false));
        searchRow.setPadding(dp(14), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        searchLp.topMargin = dp(16);
        searchLp.bottomMargin = dp(14);

        searchInput = new EditText(this);
        searchInput.setHint("Search");
        searchInput.setHintTextColor(secondaryTextColor());
        searchInput.setTextColor(primaryTextColor());
        searchInput.setTextSize(15);
        searchInput.setSingleLine(true);
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearSearchBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                renderNotesList();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchRow.addView(searchInput);

        Button clearBtn = new Button(this);
        clearBtn.setText("×");
        clearBtn.setTextSize(16);
        clearBtn.setTextColor(secondaryTextColor());
        clearBtn.setBackgroundColor(Color.TRANSPARENT);
        clearBtn.setMinimumWidth(0);
        clearBtn.setMinimumHeight(0);
        clearBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        clearBtn.setVisibility(View.GONE);
        clearBtn.setOnClickListener(v -> searchInput.setText(""));
        clearSearchBtn = clearBtn;
        searchRow.addView(clearSearchBtn);

        page.addView(searchRow, searchLp);

        notesListContainer = new LinearLayout(this);
        notesListContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(notesListContainer);

        wrapper.addView(scroll);

        // Home Bottom Toggle Bar: Recent | Created | A-Z | + / Settings
        LinearLayout homeDock = new LinearLayout(this);
        homeDock.setOrientation(LinearLayout.HORIZONTAL);
        homeDock.setGravity(Gravity.CENTER_VERTICAL);
        homeDock.setBackground(glassDrawable(24, false));
        homeDock.setElevation(dp(8));
        homeDock.setPadding(dp(8), dp(6), dp(8), dp(6));

        String[] sortLabels = {"Recent", "Created", "A–Z"};
        int[] sortModes = {SORT_EDITED, SORT_CREATED, SORT_ALPHA};
        for (int i = 0; i < sortLabels.length; i++) {
            final int mode = sortModes[i];
            Button tabBtn = new Button(this);
            tabBtn.setText(sortLabels[i]);
            tabBtn.setAllCaps(false);
            tabBtn.setTextSize(12.5f);
            boolean active = (sortMode == mode);
            tabBtn.setTextColor(active ? (darkGlassMode ? 0xFF07090D : Color.WHITE) : secondaryTextColor());
            tabBtn.setBackground(active ? accentPillDrawable(16) : new GradientDrawable());
            tabBtn.setMinimumHeight(0);
            tabBtn.setOnClickListener(v -> {
                sortMode = mode;
                prefs.edit().putInt(KEY_SORT_MODE, sortMode).apply();
                showHomeScreen();
            });
            applyAndroid17Press(tabBtn, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            lp.rightMargin = dp(4);
            homeDock.addView(tabBtn, lp);
        }

        Button newBtn = new Button(this);
        newBtn.setText("+");
        newBtn.setTextSize(18);
        newBtn.setTextColor(darkGlassMode ? 0xFF07090D : Color.WHITE);
        newBtn.setBackground(accentPillDrawable(16));
        newBtn.setOnClickListener(v -> openEditorForNewNote());
        newBtn.setOnLongClickListener(v -> {
            showSettingsScreen();
            return true;
        });
        applyAndroid17Press(newBtn, true);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(dp(44), dp(38));
        nLp.rightMargin = dp(4);
        homeDock.addView(newBtn, nLp);

        Button gearBtn = new Button(this);
        gearBtn.setText("⚙");
        gearBtn.setTextSize(15);
        gearBtn.setTextColor(primaryTextColor());
        gearBtn.setBackground(glassDrawable(16, false));
        gearBtn.setOnClickListener(v -> showSettingsScreen());
        applyAndroid17Press(gearBtn, false);
        homeDock.addView(gearBtn, new LinearLayout.LayoutParams(dp(40), dp(38)));

        FrameLayout.LayoutParams hdLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hdLp.gravity = Gravity.BOTTOM;
        hdLp.leftMargin = dp(16);
        hdLp.rightMargin = dp(16);
        hdLp.bottomMargin = dp(16);
        wrapper.addView(homeDock, hdLp);

        return wrapper;
    }

    // ==================== FULL-SCREEN EDITOR ====================
    private View buildEditorScreen() {
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screen.setPadding(dp(18), dp(22), dp(18), dp(74));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        Button backBtn = new Button(this);
        backBtn.setText("←");
        backBtn.setTextSize(18);
        backBtn.setTextColor(primaryTextColor());
        backBtn.setBackground(glassDrawable(18, false));
        backBtn.setOnClickListener(v -> {
            performAutosave();
            hideKeyboard(editorBodyInput);
            showHomeScreen();
        });
        applyAndroid17Press(backBtn, false);
        topBar.addView(backBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        Button moreBtn = new Button(this);
        moreBtn.setText("⋮");
        moreBtn.setTextSize(17);
        moreBtn.setTextColor(primaryTextColor());
        moreBtn.setBackground(glassDrawable(18, false));
        moreBtn.setOnClickListener(v -> showEditorMoreOptions());
        applyAndroid17Press(moreBtn, false);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        moreLp.rightMargin = dp(8);
        topBar.addView(moreBtn, moreLp);

        Button doneBtn = new Button(this);
        doneBtn.setText("Done");
        doneBtn.setAllCaps(false);
        doneBtn.setTextSize(13.5f);
        doneBtn.setTextColor(darkGlassMode ? 0xFF07090D : Color.WHITE);
        doneBtn.setBackground(accentPillDrawable(18));
        doneBtn.setPadding(dp(16), 0, dp(16), 0);
        doneBtn.setOnClickListener(v -> {
            saveCurrentNote(true);
            hideKeyboard(editorBodyInput);
            showHomeScreen();
        });
        applyAndroid17Press(doneBtn, false);
        topBar.addView(doneBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));

        screen.addView(topBar);

        editorTimestampView = makeText(currentTimestampLabel(), 12, secondaryTextColor(), false);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsLp.topMargin = dp(10);
        screen.addView(editorTimestampView, tsLp);

        editorTitleInput = new EditText(this);
        editorTitleInput.setHint("Title");
        editorTitleInput.setHintTextColor(secondaryTextColor());
        editorTitleInput.setTextColor(primaryTextColor());
        editorTitleInput.setTextSize(24);
        editorTitleInput.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        editorTitleInput.setSingleLine(true);
        editorTitleInput.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(8);
        screen.addView(editorTitleInput, titleLp);

        editorMetaView = makeText("0 words • 0 chars", 11.5f, secondaryTextColor(), false);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(4);
        metaLp.bottomMargin = dp(8);
        screen.addView(editorMetaView, metaLp);

        editorBodyInput = new EditText(this);
        editorBodyInput.setHint("Start writing...");
        editorBodyInput.setHintTextColor(secondaryTextColor());
        editorBodyInput.setTextColor(primaryTextColor());
        editorBodyInput.setTextSize(defaultFontSize);
        editorBodyInput.setLineSpacing(dp(5), 1.18f);
        editorBodyInput.setGravity(Gravity.TOP | Gravity.START);
        editorBodyInput.setBackgroundColor(Color.TRANSPARENT);
        editorBodyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (!isApplyingUndoRedo && editorBodyInput != null) {
                    String currentText = editorBodyInput.getText().toString();
                    if (undoStack.isEmpty() || !undoStack.get(undoStack.size() - 1).equals(currentText)) {
                        undoStack.add(currentText);
                        if (undoStack.size() > 40) undoStack.remove(0);
                        redoStack.clear();
                    }
                }
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateEditorCounts();
                scheduleAutosave();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        editorTitleInput.addTextChangedListener(watcher);
        editorBodyInput.addTextChangedListener(watcher);

        screen.addView(editorBodyInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        wrapper.addView(screen);

        // Bottom Toolbar: Undo | Redo | Select All | Share (lifts above keyboard)
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackground(glassDrawable(24, false));
        bottomBar.setElevation(dp(8));
        bottomBar.setPadding(dp(8), dp(6), dp(8), dp(6));

        Button undoBtn = new Button(this);
        undoBtn.setText("Undo");
        undoBtn.setAllCaps(false);
        undoBtn.setTextSize(12.5f);
        undoBtn.setTextColor(primaryTextColor());
        undoBtn.setBackground(glassDrawable(14, false));
        undoBtn.setOnClickListener(v -> performUndo());
        applyAndroid17Press(undoBtn, false);
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        uLp.rightMargin = dp(6);
        bottomBar.addView(undoBtn, uLp);

        Button redoBtn = new Button(this);
        redoBtn.setText("Redo");
        redoBtn.setAllCaps(false);
        redoBtn.setTextSize(12.5f);
        redoBtn.setTextColor(primaryTextColor());
        redoBtn.setBackground(glassDrawable(14, false));
        redoBtn.setOnClickListener(v -> performRedo());
        applyAndroid17Press(redoBtn, false);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        rLp.rightMargin = dp(6);
        bottomBar.addView(redoBtn, rLp);

        Button selectAllBtn = new Button(this);
        selectAllBtn.setText("Select All");
        selectAllBtn.setAllCaps(false);
        selectAllBtn.setTextSize(12.5f);
        selectAllBtn.setTextColor(primaryTextColor());
        selectAllBtn.setBackground(glassDrawable(14, false));
        selectAllBtn.setOnClickListener(v -> selectAllInEditor());
        applyAndroid17Press(selectAllBtn, false);
        LinearLayout.LayoutParams saLp = new LinearLayout.LayoutParams(0, dp(38), 1.15f);
        saLp.rightMargin = dp(6);
        bottomBar.addView(selectAllBtn, saLp);

        Button shareBtn = new Button(this);
        shareBtn.setText("Share");
        shareBtn.setAllCaps(false);
        shareBtn.setTextSize(12.5f);
        shareBtn.setTextColor(darkGlassMode ? 0xFF07090D : Color.WHITE);
        shareBtn.setBackground(accentPillDrawable(14));
        shareBtn.setOnClickListener(v -> shareEditorContent());
        applyAndroid17Press(shareBtn, false);
        bottomBar.addView(shareBtn, new LinearLayout.LayoutParams(0, dp(38), 1f));

        editorBottomBar = bottomBar;
        FrameLayout.LayoutParams bbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bbLp.gravity = Gravity.BOTTOM;
        bbLp.leftMargin = dp(18);
        bbLp.rightMargin = dp(18);
        bbLp.bottomMargin = dp(14);
        wrapper.addView(bottomBar, bbLp);

        return wrapper;
    }

    private void selectAllInEditor() {
        if (editorBodyInput == null) return;
        editorBodyInput.requestFocus();
        String txt = editorBodyInput.getText().toString();
        if (txt.length() > 0) {
            editorBodyInput.setSelection(0, txt.length());
            Toast.makeText(this, "Selected all", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== SETTINGS & CUSTOMIZE ====================
    private View buildSettingsScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(26), dp(20), dp(34));
        scroll.addView(page);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button backBtn = new Button(this);
        backBtn.setText("←");
        backBtn.setTextSize(18);
        backBtn.setTextColor(primaryTextColor());
        backBtn.setBackground(glassDrawable(18, false));
        backBtn.setOnClickListener(v -> showHomeScreen());
        applyAndroid17Press(backBtn, false);
        top.addView(backBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView heading = makeText("Settings", 22, primaryTextColor(), true);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.leftMargin = dp(12);
        top.addView(heading, hLp);
        page.addView(top);

        // Theme & Wallpaper Card
        LinearLayout wpCard = new LinearLayout(this);
        wpCard.setOrientation(LinearLayout.VERTICAL);
        wpCard.setBackground(glassDrawable(cornerRadiusDp, false));
        wpCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        wpCard.addView(makeText("THEME & WALLPAPER", 11, accentColor(), true));
        settingsThemeModeLabel = makeText(
                "Mode: " + (darkGlassMode ? "Dark Black Glass" : "White Glass"),
                14, primaryTextColor(), true);
        settingsWpPathLabel = makeText("Wallpaper: Default", 12.5f, secondaryTextColor(), false);
        wpCard.addView(settingsThemeModeLabel, topSpace(4));
        wpCard.addView(settingsWpPathLabel, topSpace(2));

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = dp(10);

        Button whiteBtn = new Button(this);
        whiteBtn.setText("White Glass");
        whiteBtn.setAllCaps(false);
        whiteBtn.setTextColor(!darkGlassMode ? Color.WHITE : primaryTextColor());
        whiteBtn.setBackground(!darkGlassMode ? accentPillDrawable(14) : glassDrawable(14, false));
        whiteBtn.setOnClickListener(v -> {
            darkGlassMode = false;
            prefs.edit().putBoolean(KEY_DARK_GLASS, false).apply();
            rebuildScreensWithCurrentTheme();
        });
        applyAndroid17Press(whiteBtn, false);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        wLp.rightMargin = dp(8);
        modeRow.addView(whiteBtn, wLp);

        Button blackBtn = new Button(this);
        blackBtn.setText("Black Glass");
        blackBtn.setAllCaps(false);
        blackBtn.setTextColor(darkGlassMode ? 0xFF07090D : primaryTextColor());
        blackBtn.setBackground(darkGlassMode ? accentPillDrawable(14) : glassDrawable(14, false));
        blackBtn.setOnClickListener(v -> {
            darkGlassMode = true;
            prefs.edit().putBoolean(KEY_DARK_GLASS, true).apply();
            rebuildScreensWithCurrentTheme();
        });
        applyAndroid17Press(blackBtn, false);
        modeRow.addView(blackBtn, new LinearLayout.LayoutParams(0, dp(38), 1f));
        wpCard.addView(modeRow, mLp);

        LinearLayout wpBtnRow = new LinearLayout(this);
        wpBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams wpRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wpRowLp.topMargin = dp(8);

        Button setWpBtn = new Button(this);
        setWpBtn.setText("Set Photo");
        setWpBtn.setAllCaps(false);
        setWpBtn.setTextColor(primaryTextColor());
        setWpBtn.setBackground(glassDrawable(14, true));
        setWpBtn.setOnClickListener(v -> pickWallpaperImage());
        applyAndroid17Press(setWpBtn, false);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        sLp.rightMargin = dp(8);
        wpBtnRow.addView(setWpBtn, sLp);

        Button clearWpBtn = new Button(this);
        clearWpBtn.setText("Clear BG");
        clearWpBtn.setAllCaps(false);
        clearWpBtn.setTextColor(primaryTextColor());
        clearWpBtn.setBackground(glassDrawable(14, false));
        clearWpBtn.setOnClickListener(v -> clearWallpaperImage());
        applyAndroid17Press(clearWpBtn, false);
        wpBtnRow.addView(clearWpBtn, new LinearLayout.LayoutParams(0, dp(38), 1f));

        wpCard.addView(wpBtnRow, wpRowLp);
        page.addView(wpCard, topSpace(16));

        // Customize Sliders Card
        LinearLayout glassCard = new LinearLayout(this);
        glassCard.setOrientation(LinearLayout.VERTICAL);
        glassCard.setBackground(glassDrawable(cornerRadiusDp, false));
        glassCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        glassCard.addView(makeText("CUSTOMIZE GLASS & FONT", 11, accentColor(), true));

        glassCard.addView(addSliderRow("Transparency", glassAlpha, 255, val -> {
            glassAlpha = val;
            prefs.edit().putInt(KEY_GLASS_ALPHA, glassAlpha).apply();
        }), topSpace(10));

        glassCard.addView(addSliderRow("Border Visibility", borderAlpha, 255, val -> {
            borderAlpha = val;
            prefs.edit().putInt(KEY_BORDER_ALPHA, borderAlpha).apply();
        }), topSpace(8));

        glassCard.addView(addSliderRow("Wallpaper Dim", wallpaperDim, 85, val -> {
            wallpaperDim = val;
            prefs.edit().putInt(KEY_WP_DIM, wallpaperDim).apply();
            applySavedWallpaperAndDim();
        }), topSpace(8));

        glassCard.addView(addSliderRow("Corner Roundness", cornerRadiusDp, 34, val -> {
            cornerRadiusDp = Math.max(8, val);
            prefs.edit().putInt(KEY_CORNER_RADIUS, cornerRadiusDp).apply();
        }), topSpace(8));

        settingsFontLabel = makeText("Editor Font: " + defaultFontSize + "sp", 13, primaryTextColor(), true);
        glassCard.addView(settingsFontLabel, topSpace(12));

        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = dp(8);

        Button smBtn = new Button(this);
        smBtn.setText("A-");
        smBtn.setAllCaps(false);
        smBtn.setTextColor(primaryTextColor());
        smBtn.setBackground(glassDrawable(12, false));
        smBtn.setOnClickListener(v -> changeFontSize(-2));
        applyAndroid17Press(smBtn, false);
        LinearLayout.LayoutParams smLp = new LinearLayout.LayoutParams(dp(54), dp(36));
        smLp.rightMargin = dp(8);
        fontRow.addView(smBtn, smLp);

        Button lgBtn = new Button(this);
        lgBtn.setText("A+");
        lgBtn.setAllCaps(false);
        lgBtn.setTextColor(primaryTextColor());
        lgBtn.setBackground(glassDrawable(12, false));
        lgBtn.setOnClickListener(v -> changeFontSize(2));
        applyAndroid17Press(lgBtn, false);
        fontRow.addView(lgBtn, new LinearLayout.LayoutParams(dp(54), dp(36)));
        glassCard.addView(fontRow, fLp);

        page.addView(glassCard, topSpace(14));

        // Storage Card
        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setBackground(glassDrawable(cornerRadiusDp, false));
        infoCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        infoCard.addView(makeText("STORAGE", 11, accentColor(), true));
        infoCard.addView(makeText("Internal storage/Mirror Note", 14.5f, primaryTextColor(), true), topSpace(4));
        settingsPermStatus = makeText("File access: Checking...", 12.5f, secondaryTextColor(), false);
        settingsTotalNotes = makeText("Total notes: 0", 12.5f, secondaryTextColor(), false);
        infoCard.addView(settingsPermStatus, topSpace(4));
        infoCard.addView(settingsTotalNotes, topSpace(2));

        Button permBtn = new Button(this);
        permBtn.setText("Manage File Permission");
        permBtn.setAllCaps(false);
        permBtn.setTextSize(13);
        permBtn.setTextColor(primaryTextColor());
        permBtn.setBackground(glassDrawable(14, true));
        permBtn.setOnClickListener(v -> requestManageAllFilesPermission());
        applyAndroid17Press(permBtn, false);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        pLp.topMargin = dp(10);
        infoCard.addView(permBtn, pLp);

        page.addView(infoCard, topSpace(14));

        return scroll;
    }

    private interface SliderCallback {
        void onValue(int value);
    }

    private View addSliderRow(String title, int initial, int max, SliderCallback cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        TextView nameTv = makeText(title, 12.5f, primaryTextColor(), true);
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valTv = makeText(String.valueOf(initial), 12, secondaryTextColor(), true);
        hdr.addView(nameTv);
        hdr.addView(valTv);
        box.addView(hdr);

        SeekBar seek = new SeekBar(this);
        seek.setMax(max);
        seek.setProgress(Math.min(max, Math.max(0, initial)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                valTv.setText(String.valueOf(progress));
                if (fromUser && cb != null) {
                    cb.onValue(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(4);
        box.addView(seek, sLp);

        return box;
    }

    private LinearLayout.LayoutParams topSpace(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private View hairline() {
        View v = new View(this);
        int div = darkGlassMode ? 0x1FFFFFFF : 0x1A0F172A;
        v.setBackgroundColor(div);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private String currentTimestampLabel() {
        String dt = new SimpleDateFormat("dd/MM/yyyy, HH:mm", Locale.getDefault()).format(new Date());
        return dt + "  |  Default";
    }

    private void changeFontSize(int delta) {
        defaultFontSize = Math.max(14, Math.min(24, defaultFontSize + delta));
        prefs.edit().putInt(KEY_FONT_SIZE, defaultFontSize).apply();
        if (editorBodyInput != null) {
            editorBodyInput.setTextSize(defaultFontSize);
        }
        refreshSettingsLabels();
    }

    private void refreshSettingsLabels() {
        if (settingsPermStatus != null) {
            settingsPermStatus.setText("File access: " + (hasStorageAccess() ? "Allowed" : "Not allowed"));
        }
        if (settingsTotalNotes != null) {
            settingsTotalNotes.setText("Total notes: " + allNotes.size());
        }
        if (settingsFontLabel != null) {
            settingsFontLabel.setText("Editor Font: " + defaultFontSize + "sp");
        }
        if (settingsThemeModeLabel != null) {
            settingsThemeModeLabel.setText("Mode: " + (darkGlassMode ? "Dark Black Glass" : "White Glass"));
        }
        if (settingsWpPathLabel != null) {
            String wp = prefs.getString(KEY_WP_PATH, "");
            settingsWpPathLabel.setText((wp != null && !wp.isEmpty() && new File(wp).exists())
                    ? "Wallpaper: Custom photo active"
                    : "Wallpaper: Default");
        }
    }

    // ==================== NAVIGATION ====================
    private void showHomeScreen() {
        editorScreen.setVisibility(View.GONE);
        settingsScreen.setVisibility(View.GONE);
        if (editorBottomBar != null) {
            editorBottomBar.setTranslationY(0f);
        }
        animateScreenIn(homeScreen);
        checkPermissionAndLoad(false);
    }

    private void showSettingsScreen() {
        homeScreen.setVisibility(View.GONE);
        editorScreen.setVisibility(View.GONE);
        refreshSettingsLabels();
        animateScreenIn(settingsScreen);
    }

    private void openEditorForNewNote() {
        if (!hasStorageAccess()) {
            requestManageAllFilesPermission();
            return;
        }
        currentEditingFileName = null;
        undoStack.clear();
        redoStack.clear();
        editorTitleInput.setText("");
        editorBodyInput.setText("");
        editorBodyInput.setTextSize(defaultFontSize);
        editorTimestampView.setText(currentTimestampLabel());
        lastSavedSignature = "";
        updateEditorCounts();

        homeScreen.setVisibility(View.GONE);
        settingsScreen.setVisibility(View.GONE);
        animateScreenIn(editorScreen);

        editorTitleInput.requestFocus();
        showKeyboard(editorTitleInput);
    }

    private void openEditorForExistingNote(NoteEntry entry) {
        currentEditingFileName = entry.fileName;
        undoStack.clear();
        redoStack.clear();
        editorTitleInput.setText(entry.title);
        editorBodyInput.setText(entry.body);
        editorBodyInput.setTextSize(defaultFontSize);
        String dt = new SimpleDateFormat("dd/MM/yyyy, HH:mm", Locale.getDefault())
                .format(new Date(entry.lastModified));
        editorTimestampView.setText(dt + "  |  Default");
        lastSavedSignature = entry.title + "\n" + entry.body;
        updateEditorCounts();

        homeScreen.setVisibility(View.GONE);
        settingsScreen.setVisibility(View.GONE);
        animateScreenIn(editorScreen);
    }

    private void performUndo() {
        if (undoStack.isEmpty() || editorBodyInput == null) return;
        String prev = undoStack.remove(undoStack.size() - 1);
        redoStack.add(editorBodyInput.getText().toString());
        isApplyingUndoRedo = true;
        editorBodyInput.setText(prev);
        editorBodyInput.setSelection(prev.length());
        isApplyingUndoRedo = false;
        updateEditorCounts();
    }

    private void performRedo() {
        if (redoStack.isEmpty() || editorBodyInput == null) return;
        String next = redoStack.remove(redoStack.size() - 1);
        undoStack.add(editorBodyInput.getText().toString());
        isApplyingUndoRedo = true;
        editorBodyInput.setText(next);
        editorBodyInput.setSelection(next.length());
        isApplyingUndoRedo = false;
        updateEditorCounts();
    }

    private void showKeyboard(View target) {
        target.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 120);
    }

    private void hideKeyboard(View target) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && target != null) {
            imm.hideSoftInputFromWindow(target.getWindowToken(), 0);
        }
    }

    // ==================== STORAGE & PERMISSIONS ====================
    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    private void requestManageAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                    Toast.makeText(this, "Allow file access in App Settings", Toast.LENGTH_LONG).show();
                }
            }
        } else {
            ensureMirrorNoteFolder();
            renderNotesList();
        }
    }

    private boolean ensureMirrorNoteFolder() {
        if (!hasStorageAccess()) {
            return false;
        }
        File root = Environment.getExternalStorageDirectory();
        notesFolder = new File(root, "Mirror Note");
        if (!notesFolder.exists()) {
            return notesFolder.mkdirs();
        }
        return notesFolder.isDirectory();
    }

    private void checkPermissionAndLoad(boolean showToastIfDenied) {
        if (hasStorageAccess()) {
            ensureMirrorNoteFolder();
            loadNotesFromDisk();
        } else {
            allNotes.clear();
            if (showToastIfDenied) {
                Toast.makeText(this, "File access permission is required.", Toast.LENGTH_SHORT).show();
            }
        }
        renderNotesList();
        refreshSettingsLabels();
    }

    private String sanitizeBaseName(String rawTitle) {
        String trimmed = rawTitle == null ? "" : rawTitle.trim();
        if (trimmed.isEmpty()) {
            trimmed = "Untitled";
        }
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().replaceAll("[. ]+$", "");
        if (cleaned.isEmpty()) {
            cleaned = "Untitled";
        }
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80).trim();
        }
        return cleaned;
    }

    private void loadNotesFromDisk() {
        allNotes.clear();
        if (notesFolder == null || !notesFolder.exists()) return;

        File[] files = notesFolder.listFiles((dir, name) ->
                name != null && name.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (files == null) return;

        Set<String> pinnedSet = getStringSetPref(KEY_PINNED);
        Set<String> favSet = getStringSetPref(KEY_FAV);
        JSONObject createdMap = getCreatedMap();

        for (File f : files) {
            if (!f.isFile()) continue;
            String fileName = f.getName();
            String title = fileName.substring(0, fileName.length() - 4);
            String body = "";
            try {
                byte[] bytes = Files.readAllBytes(f.toPath());
                body = new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            long mod = f.lastModified();
            long created = createdMap.optLong(fileName, mod > 0 ? mod : System.currentTimeMillis());
            boolean isPinned = pinnedSet.contains(fileName);
            boolean isFav = favSet.contains(fileName);
            allNotes.add(new NoteEntry(f, fileName, title, body, mod, created, isPinned, isFav));
        }

        Collections.sort(allNotes, new Comparator<NoteEntry>() {
            @Override
            public int compare(NoteEntry a, NoteEntry b) {
                if (a.pinned != b.pinned) {
                    return a.pinned ? -1 : 1;
                }
                if (sortMode == SORT_ALPHA) {
                    return a.title.compareToIgnoreCase(b.title);
                } else if (sortMode == SORT_CREATED) {
                    return Long.compare(b.createdAt, a.createdAt);
                } else {
                    return Long.compare(b.lastModified, a.lastModified);
                }
            }
        });
    }

    private void scheduleAutosave() {
        autosaveHandler.removeCallbacks(autosaveRunnable);
        autosaveHandler.postDelayed(autosaveRunnable, 1100);
    }

    private void performAutosave() {
        if (editorTitleInput == null || editorBodyInput == null) return;
        String title = editorTitleInput.getText().toString().trim();
        String body = editorBodyInput.getText().toString();
        if (title.isEmpty() && body.trim().isEmpty()) {
            return;
        }
        String sig = title + "\n" + body;
        if (sig.equals(lastSavedSignature)) {
            return;
        }
        saveCurrentNote(false);
    }

    private boolean saveCurrentNote(boolean showValidationToast) {
        if (!hasStorageAccess() || !ensureMirrorNoteFolder()) {
            if (showValidationToast) {
                requestManageAllFilesPermission();
            }
            return false;
        }

        String rawTitle = editorTitleInput.getText().toString().trim();
        String body = editorBodyInput.getText().toString();

        if (rawTitle.isEmpty() && body.trim().isEmpty()) {
            if (showValidationToast) {
                Toast.makeText(this, "Write a title or note first.", Toast.LENGTH_SHORT).show();
            }
            return false;
        }

        String baseTitle = sanitizeBaseName(rawTitle.isEmpty() ? firstLineOrUntitled(body) : rawTitle);
        String targetFileName = baseTitle + ".txt";

        if (currentEditingFileName == null || !currentEditingFileName.equals(targetFileName)) {
            File candidate = new File(notesFolder, targetFileName);
            if (candidate.exists()) {
                int suffix = 2;
                while (new File(notesFolder, baseTitle + " (" + suffix + ").txt").exists()) {
                    suffix++;
                }
                targetFileName = baseTitle + " (" + suffix + ").txt";
            }
        }

        File targetFile = new File(notesFolder, targetFileName);
        byte[] utf8Bytes = body.getBytes(StandardCharsets.UTF_8);

        try (FileOutputStream fos = new FileOutputStream(targetFile, false)) {
            fos.write(utf8Bytes);
            fos.flush();
        } catch (Exception e) {
            if (showValidationToast) {
                Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return false;
        }

        if (currentEditingFileName != null && !currentEditingFileName.equals(targetFileName)) {
            File oldFile = new File(notesFolder, currentEditingFileName);
            migrateMetadata(currentEditingFileName, targetFileName);
            if (oldFile.exists() && !oldFile.equals(targetFile)) {
                //noinspection ResultOfMethodCallIgnored
                oldFile.delete();
            }
        } else {
            ensureCreatedTimestamp(targetFileName);
        }

        currentEditingFileName = targetFileName;
        lastSavedSignature = rawTitle + "\n" + body;
        if (editorTimestampView != null) {
            editorTimestampView.setText(currentTimestampLabel());
        }
        return true;
    }

    private String firstLineOrUntitled(String body) {
        if (body == null) return "Untitled";
        String first = body.trim().split("\\R", 2)[0].trim();
        return first.isEmpty() ? "Untitled" : first;
    }

    private void updateEditorCounts() {
        String text = editorBodyInput.getText().toString();
        int chars = text.length();
        String trimmed = text.trim();
        int words = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
        editorMetaView.setText(words + (words == 1 ? " word" : " words") + " • " + chars + " chars");
    }

    private void copyEditorContent() {
        String title = editorTitleInput.getText().toString().trim();
        String body = editorBodyInput.getText().toString();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(title.isEmpty() ? "Note" : title, body));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareEditorContent() {
        String title = editorTitleInput.getText().toString().trim();
        String body = editorBodyInput.getText().toString();
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, title.isEmpty() ? "Note" : title);
        share.putExtra(Intent.EXTRA_TEXT, (title.isEmpty() ? "" : title + "\n\n") + body);
        startActivity(Intent.createChooser(share, "Share note"));
    }

    private void showEditorMoreOptions() {
        String[] options = {
                "Pin / Unpin Note",
                "Favourite / Unfavourite",
                "Copy Content",
                "Delete Note"
        };
        new AlertDialog.Builder(this)
                .setTitle("Note Options")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        if (saveCurrentNote(false) && currentEditingFileName != null) {
                            toggleSetPref(KEY_PINNED, currentEditingFileName);
                            Toast.makeText(this, "Pin updated", Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 1) {
                        if (saveCurrentNote(false) && currentEditingFileName != null) {
                            toggleSetPref(KEY_FAV, currentEditingFileName);
                            Toast.makeText(this, "Favourite updated", Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 2) {
                        copyEditorContent();
                    } else if (which == 3) {
                        if (currentEditingFileName != null) {
                            confirmAndDeleteFile(new File(notesFolder, currentEditingFileName), true);
                        } else {
                            editorTitleInput.setText("");
                            editorBodyInput.setText("");
                            showHomeScreen();
                        }
                    }
                })
                .show();
    }

    private void confirmAndDeleteFile(File target, boolean returnHomeAfter) {
        if (target == null || !target.exists()) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete Note?")
                .setMessage("Delete \"" + target.getName() + "\"?")
                .setPositiveButton("Delete", (d, w) -> {
                    try {
                        lastDeletedName = target.getName();
                        lastDeletedBytes = Files.readAllBytes(target.toPath());
                    } catch (Exception ignored) {
                        lastDeletedBytes = null;
                    }
                    //noinspection ResultOfMethodCallIgnored
                    boolean deleted = target.delete();
                    if (deleted) {
                        removeMetadata(target.getName());
                        if (returnHomeAfter) {
                            currentEditingFileName = null;
                            showHomeScreen();
                        } else {
                            loadNotesFromDisk();
                            renderNotesList();
                        }
                        showUndoDeleteDialog();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showUndoDeleteDialog() {
        if (lastDeletedName == null || lastDeletedBytes == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Deleted")
                .setMessage("Restore " + lastDeletedName + "?")
                .setPositiveButton("Undo", (d, w) -> {
                    if (ensureMirrorNoteFolder()) {
                        File restored = new File(notesFolder, lastDeletedName);
                        try (FileOutputStream fos = new FileOutputStream(restored, false)) {
                            fos.write(lastDeletedBytes);
                            fos.flush();
                            ensureCreatedTimestamp(lastDeletedName);
                            checkPermissionAndLoad(false);
                            Toast.makeText(this, "Restored", Toast.LENGTH_SHORT).show();
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNegativeButton("OK", null)
                .show();
    }

    private void renderNotesList() {
        if (notesListContainer == null) return;
        notesListContainer.removeAllViews();

        String query = searchInput != null ? searchInput.getText().toString().trim().toLowerCase(Locale.ROOT) : "";
        List<NoteEntry> filtered = new ArrayList<>();
        for (NoteEntry n : allNotes) {
            if (query.isEmpty()
                    || n.title.toLowerCase(Locale.ROOT).contains(query)
                    || n.body.toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(n);
            }
        }

        int total = allNotes.size();
        countSummaryView.setText(total == 1 ? "1 note" : total + " notes");

        if (filtered.isEmpty()) {
            String msg = !hasStorageAccess()
                    ? "Tap + to allow file access."
                    : (query.isEmpty() ? "No notes yet. Tap + to write." : "No matching notes.");
            TextView empty = makeText(msg, 14, secondaryTextColor(), false);
            empty.setPadding(dp(2), dp(36), dp(2), dp(24));
            notesListContainer.addView(empty);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
        boolean hasPinnedHeader = false;
        boolean hasAllHeader = false;

        for (int i = 0; i < filtered.size(); i++) {
            NoteEntry item = filtered.get(i);

            if (item.pinned && !hasPinnedHeader) {
                hasPinnedHeader = true;
                TextView pHdr = makeText("PINNED", 11, accentColor(), true);
                LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                hLp.topMargin = dp(4);
                hLp.bottomMargin = dp(6);
                notesListContainer.addView(pHdr, hLp);
            } else if (!item.pinned && hasPinnedHeader && !hasAllHeader) {
                hasAllHeader = true;
                TextView aHdr = makeText("NOTES", 11, secondaryTextColor(), true);
                LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                hLp.topMargin = dp(14);
                hLp.bottomMargin = dp(6);
                notesListContainer.addView(aHdr, hLp);
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(2), dp(12), dp(2), dp(12));
            row.setBackgroundColor(Color.TRANSPARENT);

            String prefix = (item.pinned ? "📌 " : "") + (item.favourite ? "★ " : "");
            TextView tView = makeText(prefix + item.title, 16.5f, primaryTextColor(), true);
            tView.setSingleLine(true);
            row.addView(tView);

            String cleanPreview = item.body.replace('\n', ' ').trim();
            if (cleanPreview.length() > 90) {
                cleanPreview = cleanPreview.substring(0, 90) + "…";
            }
            TextView pView = makeText(
                    cleanPreview.isEmpty() ? "No additional text" : cleanPreview,
                    13, secondaryTextColor(), false);
            pView.setSingleLine(true);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pLp.topMargin = dp(3);
            row.addView(pView, pLp);

            TextView dView = makeText(sdf.format(new Date(item.lastModified)), 11, secondaryTextColor(), false);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.topMargin = dp(4);
            row.addView(dView, dLp);

            row.setOnClickListener(v -> openEditorForExistingNote(item));
            row.setOnLongClickListener(v -> {
                showItemLongPressMenu(item);
                return true;
            });
            applyAndroid17Press(row, false);

            notesListContainer.addView(row);

            if (i < filtered.size() - 1) {
                notesListContainer.addView(hairline());
            }
        }
    }

    private void showItemLongPressMenu(NoteEntry item) {
        String pinLabel = item.pinned ? "Unpin" : "Pin";
        String favLabel = item.favourite ? "Unfavourite" : "Favourite";
        String[] actions = {pinLabel, favLabel, "Rename", "Delete"};

        new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        toggleSetPref(KEY_PINNED, item.fileName);
                        loadNotesFromDisk();
                        renderNotesList();
                    } else if (which == 1) {
                        toggleSetPref(KEY_FAV, item.fileName);
                        loadNotesFromDisk();
                        renderNotesList();
                    } else if (which == 2) {
                        showRenameDialog(item);
                    } else if (which == 3) {
                        confirmAndDeleteFile(item.file, false);
                    }
                })
                .show();
    }

    private void showRenameDialog(NoteEntry item) {
        final EditText input = new EditText(this);
        input.setText(item.title);
        input.setTextColor(primaryTextColor());
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle("Rename Note")
                .setView(input)
                .setPositiveButton("Rename", (d, w) -> {
                    String nextBase = sanitizeBaseName(input.getText().toString());
                    String nextName = nextBase + ".txt";
                    if (nextName.equals(item.fileName)) return;
                    File dest = new File(notesFolder, nextName);
                    if (dest.exists()) {
                        Toast.makeText(this, "A note with this title already exists.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (item.file.renameTo(dest)) {
                        migrateMetadata(item.fileName, nextName);
                        loadNotesFromDisk();
                        renderNotesList();
                        Toast.makeText(this, "Renamed to " + nextName, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ==================== METADATA HELPERS ====================
    private Set<String> getStringSetPref(String key) {
        Set<String> raw = prefs.getStringSet(key, null);
        return raw == null ? new HashSet<>() : new HashSet<>(raw);
    }

    private void toggleSetPref(String key, String fileName) {
        Set<String> set = getStringSetPref(key);
        if (set.contains(fileName)) {
            set.remove(fileName);
        } else {
            set.add(fileName);
        }
        prefs.edit().putStringSet(key, set).apply();
    }

    private JSONObject getCreatedMap() {
        String raw = prefs.getString(KEY_CREATED_MAP, "{}");
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void saveCreatedMap(JSONObject obj) {
        prefs.edit().putString(KEY_CREATED_MAP, obj.toString()).apply();
    }

    private void ensureCreatedTimestamp(String fileName) {
        JSONObject map = getCreatedMap();
        if (!map.has(fileName)) {
            try {
                map.put(fileName, System.currentTimeMillis());
                saveCreatedMap(map);
            } catch (JSONException ignored) {
            }
        }
    }

    private void migrateMetadata(String oldName, String newName) {
        Set<String> pinned = getStringSetPref(KEY_PINNED);
        if (pinned.remove(oldName)) {
            pinned.add(newName);
            prefs.edit().putStringSet(KEY_PINNED, pinned).apply();
        }
        Set<String> favs = getStringSetPref(KEY_FAV);
        if (favs.remove(oldName)) {
            favs.add(newName);
            prefs.edit().putStringSet(KEY_FAV, favs).apply();
        }
        JSONObject map = getCreatedMap();
        if (map.has(oldName)) {
            long ts = map.optLong(oldName, System.currentTimeMillis());
            map.remove(oldName);
            try {
                map.put(newName, ts);
                saveCreatedMap(map);
            } catch (JSONException ignored) {
            }
        }
    }

    private void removeMetadata(String fileName) {
        Set<String> pinned = getStringSetPref(KEY_PINNED);
        if (pinned.remove(fileName)) {
            prefs.edit().putStringSet(KEY_PINNED, pinned).apply();
        }
        Set<String> favs = getStringSetPref(KEY_FAV);
        if (favs.remove(fileName)) {
            prefs.edit().putStringSet(KEY_FAV, favs).apply();
        }
        JSONObject map = getCreatedMap();
        if (map.has(fileName)) {
            map.remove(fileName);
            saveCreatedMap(map);
        }
    }
}
