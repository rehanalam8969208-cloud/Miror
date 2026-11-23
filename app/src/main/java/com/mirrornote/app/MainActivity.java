package com.mirrornote.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
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
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
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

    // White + Glassy Palette with Soft Blue Accent
    private static final int COLOR_BG_WHITE = 0xFFF7F9FC;
    private static final int COLOR_TEXT_PRIMARY = 0xFF0F172A;
    private static final int COLOR_TEXT_SECONDARY = 0xFF64748B;
    private static final int COLOR_ACCENT_BLUE = 0xFF2563EB;
    private static final int COLOR_GLASS_WHITE = 0xCCFFFFFF;
    private static final int COLOR_GLASS_BORDER = 0x330F172A;
    private static final int COLOR_DIVIDER = 0x1A0F172A;

    private static final String PREFS_NAME = "mirror_note_prefs";
    private static final String KEY_PINNED = "pinned_files";
    private static final String KEY_FAV = "fav_files";
    private static final String KEY_CREATED_MAP = "created_map_json";
    private static final String KEY_SORT_MODE = "sort_mode";
    private static final String KEY_FONT_SIZE = "font_size_sp";

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

    // Soft Floating Blue Smoke Background View
    private static class BlueSmokeBackgroundView extends View {
        private final Paint puffPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator smokeAnimator;
        private float phase = 0f;

        BlueSmokeBackgroundView(Context context) {
            super(context);
            startSmoke();
        }

        private void startSmoke() {
            smokeAnimator = ValueAnimator.ofFloat(0f, 1f);
            smokeAnimator.setDuration(14000);
            smokeAnimator.setRepeatCount(ValueAnimator.INFINITE);
            smokeAnimator.setRepeatMode(ValueAnimator.REVERSE);
            smokeAnimator.setInterpolator(new LinearInterpolator());
            smokeAnimator.addUpdateListener(anim -> {
                phase = (float) anim.getAnimatedValue();
                invalidate();
            });
            smokeAnimator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (smokeAnimator != null) {
                smokeAnimator.cancel();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            canvas.drawColor(COLOR_BG_WHITE);

            drawSmokeCloud(canvas,
                    w * (0.20f + 0.14f * phase),
                    h * (0.16f - 0.06f * phase),
                    Math.max(w, h) * 0.56f,
                    0x2E38BDF8, 0x1260A5FA);

            drawSmokeCloud(canvas,
                    w * (0.82f - 0.16f * phase),
                    h * (0.46f + 0.09f * phase),
                    Math.max(w, h) * 0.62f,
                    0x2460A5FA, 0x0E93C5FD);

            drawSmokeCloud(canvas,
                    w * (0.36f + 0.18f * (1f - phase)),
                    h * (0.82f - 0.10f * phase),
                    Math.max(w, h) * 0.65f,
                    0x293B82F6, 0x107DD3FC);
        }

        private void drawSmokeCloud(Canvas canvas, float cx, float cy, float radius,
                                    int innerColor, int midColor) {
            RadialGradient grad = new RadialGradient(
                    cx, cy, radius,
                    new int[]{innerColor, midColor, Color.TRANSPARENT},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP);
            puffPaint.setShader(grad);
            canvas.drawCircle(cx, cy, radius, puffPaint);
        }
    }

    private FrameLayout rootContainer;
    private View homeScreen;
    private View editorScreen;
    private View settingsScreen;

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

    private SharedPreferences prefs;
    private File notesFolder;
    private List<NoteEntry> allNotes = new ArrayList<>();

    private int sortMode = SORT_EDITED;
    private int defaultFontSize = 17;

    private String currentEditingFileName = null;
    private String lastSavedSignature = "";
    private boolean isApplyingUndoRedo = false;
    private final List<String> undoStack = new ArrayList<>();
    private final List<String> redoStack = new ArrayList<>();

    private File lastDeletedFile = null;
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

        setupWindowTheme();
        buildRootLayout();
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

    private void setupWindowTheme() {
        getWindow().setStatusBarColor(COLOR_BG_WHITE);
        getWindow().setNavigationBarColor(COLOR_BG_WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable whiteGlassDrawable(int radiusDp, boolean accentBorder) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(COLOR_GLASS_WHITE);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), accentBorder ? 0x662563EB : COLOR_GLASS_BORDER);
        return d;
    }

    private GradientDrawable bluePillDrawable(int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(COLOR_ACCENT_BLUE);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private void applyPressFeedback(View v, boolean isFab) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate()
                            .scaleX(isFab ? 0.92f : 0.97f)
                            .scaleY(isFab ? 0.92f : 0.97f)
                            .alpha(0.88f)
                            .setDuration(100)
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(190)
                            .start();
                    break;
            }
            return false;
        });
    }

    private void animateScreenIn(View screen) {
        screen.setVisibility(View.VISIBLE);
        screen.setAlpha(0f);
        screen.setTranslationY(dp(10));
        screen.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .start();
    }

    private TextView makeText(String text, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) {
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        } else {
            tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        return tv;
    }

    private void buildRootLayout() {
        rootContainer = new FrameLayout(this);
        rootContainer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Animated blue smoke backdrop across all views
        BlueSmokeBackgroundView smokeBg = new BlueSmokeBackgroundView(this);
        rootContainer.addView(smokeBg, new FrameLayout.LayoutParams(
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

    // ==================== HOME SCREEN ====================
    private View buildHomeScreen() {
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(100));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(28), dp(20), dp(24));
        scroll.addView(page);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView logoBadge = makeText("M", 15, Color.WHITE, true);
        logoBadge.setGravity(Gravity.CENTER);
        logoBadge.setBackground(bluePillDrawable(12));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        logoLp.rightMargin = dp(10);
        topBar.addView(logoBadge, logoLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleColLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleCol.addView(makeText("Notes", 28, COLOR_TEXT_PRIMARY, true));
        countSummaryView = makeText("0 notes", 12, COLOR_TEXT_SECONDARY, false);
        titleCol.addView(countSummaryView);
        topBar.addView(titleCol, titleColLp);

        Button settingsBtn = new Button(this);
        settingsBtn.setText("Settings");
        settingsBtn.setAllCaps(false);
        settingsBtn.setTextSize(12.5f);
        settingsBtn.setTextColor(COLOR_TEXT_PRIMARY);
        settingsBtn.setBackground(whiteGlassDrawable(16, false));
        settingsBtn.setElevation(dp(2));
        settingsBtn.setPadding(dp(14), dp(7), dp(14), dp(7));
        settingsBtn.setOnClickListener(v -> showSettingsScreen());
        applyPressFeedback(settingsBtn, false);
        topBar.addView(settingsBtn);

        page.addView(topBar);

        // Glassy Search Bar
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(whiteGlassDrawable(18, false));
        searchRow.setElevation(dp(2));
        searchRow.setPadding(dp(14), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        searchLp.topMargin = dp(16);
        searchLp.bottomMargin = dp(12);

        searchInput = new EditText(this);
        searchInput.setHint("Search");
        searchInput.setHintTextColor(COLOR_TEXT_SECONDARY);
        searchInput.setTextColor(COLOR_TEXT_PRIMARY);
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
        clearBtn.setTextColor(COLOR_TEXT_SECONDARY);
        clearBtn.setBackgroundColor(Color.TRANSPARENT);
        clearBtn.setMinimumWidth(0);
        clearBtn.setMinimumHeight(0);
        clearBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        clearBtn.setVisibility(View.GONE);
        clearBtn.setOnClickListener(v -> searchInput.setText(""));
        clearSearchBtn = clearBtn;
        searchRow.addView(clearSearchBtn);

        page.addView(searchRow, searchLp);

        // Sort Chips
        HorizontalScrollView sortScroll = new HorizontalScrollView(this);
        sortScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortScroll.addView(sortRow);

        String[] sortLabels = {"Recent", "Created", "A–Z"};
        int[] sortModes = {SORT_EDITED, SORT_CREATED, SORT_ALPHA};
        for (int i = 0; i < sortLabels.length; i++) {
            final int mode = sortModes[i];
            Button chip = new Button(this);
            chip.setText(sortLabels[i]);
            chip.setAllCaps(false);
            chip.setTextSize(12);
            boolean active = (sortMode == mode);
            chip.setTextColor(active ? Color.WHITE : COLOR_TEXT_SECONDARY);
            chip.setBackground(active ? bluePillDrawable(14) : whiteGlassDrawable(14, false));
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            chip.setMinimumHeight(0);
            chip.setOnClickListener(v -> {
                sortMode = mode;
                prefs.edit().putInt(KEY_SORT_MODE, sortMode).apply();
                showHomeScreen();
            });
            applyPressFeedback(chip, false);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            cLp.rightMargin = dp(8);
            sortRow.addView(chip, cLp);
        }
        LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sortLp.bottomMargin = dp(12);
        page.addView(sortScroll, sortLp);

        notesListContainer = new LinearLayout(this);
        notesListContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(notesListContainer);

        wrapper.addView(scroll);

        // Floating New Note (+) Button
        Button fabNewNote = new Button(this);
        fabNewNote.setText("+");
        fabNewNote.setTextSize(26);
        fabNewNote.setTextColor(Color.WHITE);
        fabNewNote.setBackground(bluePillDrawable(28));
        fabNewNote.setElevation(dp(6));
        fabNewNote.setOnClickListener(v -> openEditorForNewNote());
        applyPressFeedback(fabNewNote, true);

        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(56), dp(56));
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.rightMargin = dp(20);
        fabLp.bottomMargin = dp(24);
        wrapper.addView(fabNewNote, fabLp);

        return wrapper;
    }

    // ==================== FULL-SCREEN EDITOR (SCREENSHOT-INSPIRED) ====================
    private View buildEditorScreen() {
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screen.setPadding(dp(18), dp(22), dp(18), dp(86));

        // Top Bar: Back + Undo/Redo + More + Done[span_4](start_span)[span_4](end_span)
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        Button backBtn = new Button(this);
        backBtn.setText("←");
        backBtn.setTextSize(18);
        backBtn.setTextColor(COLOR_TEXT_PRIMARY);
        backBtn.setBackground(whiteGlassDrawable(18, false));
        backBtn.setElevation(dp(2));
        backBtn.setOnClickListener(v -> {
            performAutosave();
            hideKeyboard(editorBodyInput);
            showHomeScreen();
        });
        applyPressFeedback(backBtn, false);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        backLp.rightMargin = dp(8);
        topBar.addView(backBtn, backLp);

        Button undoBtn = new Button(this);
        undoBtn.setText("↶");
        undoBtn.setTextSize(16);
        undoBtn.setTextColor(COLOR_TEXT_PRIMARY);
        undoBtn.setBackground(whiteGlassDrawable(18, false));
        undoBtn.setElevation(dp(2));
        undoBtn.setOnClickListener(v -> performUndo());
        applyPressFeedback(undoBtn, false);
        LinearLayout.LayoutParams undoLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        undoLp.rightMargin = dp(6);
        topBar.addView(undoBtn, undoLp);

        Button redoBtn = new Button(this);
        redoBtn.setText("↷");
        redoBtn.setTextSize(16);
        redoBtn.setTextColor(COLOR_TEXT_PRIMARY);
        redoBtn.setBackground(whiteGlassDrawable(18, false));
        redoBtn.setElevation(dp(2));
        redoBtn.setOnClickListener(v -> performRedo());
        applyPressFeedback(redoBtn, false);
        topBar.addView(redoBtn, new LinearLayout.LayoutParams(dp(38), dp(38)));

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        Button moreBtn = new Button(this);
        moreBtn.setText("⋮");
        moreBtn.setTextSize(17);
        moreBtn.setTextColor(COLOR_TEXT_PRIMARY);
        moreBtn.setBackground(whiteGlassDrawable(18, false));
        moreBtn.setElevation(dp(2));
        moreBtn.setOnClickListener(v -> showEditorMoreOptions());
        applyPressFeedback(moreBtn, false);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        moreLp.rightMargin = dp(8);
        topBar.addView(moreBtn, moreLp);

        Button doneBtn = new Button(this);
        doneBtn.setText("Done");
        doneBtn.setAllCaps(false);
        doneBtn.setTextSize(13.5f);
        doneBtn.setTextColor(Color.WHITE);
        doneBtn.setBackground(bluePillDrawable(18));
        doneBtn.setElevation(dp(3));
        doneBtn.setPadding(dp(16), 0, dp(16), 0);
        doneBtn.setOnClickListener(v -> {
            saveCurrentNote(true);
            hideKeyboard(editorBodyInput);
            showHomeScreen();
        });
        applyPressFeedback(doneBtn, false);
        topBar.addView(doneBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));

        screen.addView(topBar);

        // Date & Folder Meta Line (like 24/11/2026 | Default)[span_5](start_span)[span_5](end_span)[span_6](start_span)[span_6](end_span)
        editorTimestampView = makeText(currentTimestampLabel(), 12, COLOR_TEXT_SECONDARY, false);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsLp.topMargin = dp(12);
        screen.addView(editorTimestampView, tsLp);

        // Large Title Input[span_7](start_span)[span_7](end_span)
        editorTitleInput = new EditText(this);
        editorTitleInput.setHint("Title");
        editorTitleInput.setHintTextColor(COLOR_TEXT_SECONDARY);
        editorTitleInput.setTextColor(COLOR_TEXT_PRIMARY);
        editorTitleInput.setTextSize(24);
        editorTitleInput.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        editorTitleInput.setSingleLine(true);
        editorTitleInput.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(10);
        screen.addView(editorTitleInput, titleLp);

        editorMetaView = makeText("0 words • 0 chars", 11.5f, COLOR_TEXT_SECONDARY, false);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(4);
        metaLp.bottomMargin = dp(8);
        screen.addView(editorMetaView, metaLp);

        // Full-Screen Writing Area
        editorBodyInput = new EditText(this);
        editorBodyInput.setHint("Start writing...");
        editorBodyInput.setHintTextColor(COLOR_TEXT_SECONDARY);
        editorBodyInput.setTextColor(COLOR_TEXT_PRIMARY);
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

        // Floating White-Glass Bottom Action Bar[span_8](start_span)[span_8](end_span)[span_9](start_span)[span_9](end_span)
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setBackground(whiteGlassDrawable(26, false));
        bottomBar.setElevation(dp(8));
        bottomBar.setPadding(dp(14), dp(8), dp(14), dp(8));

        Button fontQuickBtn = new Button(this);
        fontQuickBtn.setText("Aa");
        fontQuickBtn.setAllCaps(false);
        fontQuickBtn.setTextSize(14);
        fontQuickBtn.setTextColor(COLOR_TEXT_PRIMARY);
        fontQuickBtn.setBackgroundColor(Color.TRANSPARENT);
        fontQuickBtn.setOnClickListener(v -> {
            int nextSize = defaultFontSize >= 22 ? 15 : defaultFontSize + 2;
            defaultFontSize = nextSize;
            prefs.edit().putInt(KEY_FONT_SIZE, defaultFontSize).apply();
            editorBodyInput.setTextSize(defaultFontSize);
            Toast.makeText(this, "Font " + defaultFontSize + "sp", Toast.LENGTH_SHORT).show();
        });
        applyPressFeedback(fontQuickBtn, false);
        bottomBar.addView(fontQuickBtn, new LinearLayout.LayoutParams(0, dp(40), 1f));

        Button copyQuickBtn = new Button(this);
        copyQuickBtn.setText("Copy");
        copyQuickBtn.setAllCaps(false);
        copyQuickBtn.setTextSize(13);
        copyQuickBtn.setTextColor(COLOR_TEXT_PRIMARY);
        copyQuickBtn.setBackgroundColor(Color.TRANSPARENT);
        copyQuickBtn.setOnClickListener(v -> copyEditorContent());
        applyPressFeedback(copyQuickBtn, false);
        bottomBar.addView(copyQuickBtn, new LinearLayout.LayoutParams(0, dp(40), 1f));

        Button shareQuickBtn = new Button(this);
        shareQuickBtn.setText("Share");
        shareQuickBtn.setAllCaps(false);
        shareQuickBtn.setTextSize(13);
        shareQuickBtn.setTextColor(COLOR_TEXT_PRIMARY);
        shareQuickBtn.setBackgroundColor(Color.TRANSPARENT);
        shareQuickBtn.setOnClickListener(v -> shareEditorContent());
        applyPressFeedback(shareQuickBtn, false);
        bottomBar.addView(shareQuickBtn, new LinearLayout.LayoutParams(0, dp(40), 1f));

        Button saveQuickBtn = new Button(this);
        saveQuickBtn.setText("Save");
        saveQuickBtn.setAllCaps(false);
        saveQuickBtn.setTextSize(13);
        saveQuickBtn.setTextColor(COLOR_ACCENT_BLUE);
        saveQuickBtn.setBackgroundColor(Color.TRANSPARENT);
        saveQuickBtn.setOnClickListener(v -> {
            if (saveCurrentNote(true)) {
                Toast.makeText(this, "Saved to Mirror Note", Toast.LENGTH_SHORT).show();
            }
        });
        applyPressFeedback(saveQuickBtn, false);
        bottomBar.addView(saveQuickBtn, new LinearLayout.LayoutParams(0, dp(40), 1f));

        FrameLayout.LayoutParams bbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bbLp.gravity = Gravity.BOTTOM;
        bbLp.leftMargin = dp(24);
        bbLp.rightMargin = dp(24);
        bbLp.bottomMargin = dp(16);
        wrapper.addView(bottomBar, bbLp);

        return wrapper;
    }

    // ==================== SETTINGS SCREEN ====================
    private View buildSettingsScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(26), dp(20), dp(30));
        scroll.addView(page);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button backBtn = new Button(this);
        backBtn.setText("← Notes");
        backBtn.setAllCaps(false);
        backBtn.setTextSize(13.5f);
        backBtn.setTextColor(COLOR_ACCENT_BLUE);
        backBtn.setBackgroundColor(Color.TRANSPARENT);
        backBtn.setOnClickListener(v -> showHomeScreen());
        applyPressFeedback(backBtn, false);
        top.addView(backBtn);

        TextView heading = makeText("Settings", 22, COLOR_TEXT_PRIMARY, true);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.leftMargin = dp(12);
        top.addView(heading, hLp);
        page.addView(top);

        LinearLayout card1 = new LinearLayout(this);
        card1.setOrientation(LinearLayout.VERTICAL);
        card1.setBackground(whiteGlassDrawable(20, false));
        card1.setPadding(dp(16), dp(14), dp(16), dp(14));
        card1.addView(makeText("STORAGE", 11, COLOR_ACCENT_BLUE, true));
        card1.addView(makeText("Internal storage/Mirror Note", 15, COLOR_TEXT_PRIMARY, true), topSpace(4));
        settingsPermStatus = makeText("File access: Checking...", 12.5f, COLOR_TEXT_SECONDARY, false);
        settingsTotalNotes = makeText("Total notes: 0", 12.5f, COLOR_TEXT_SECONDARY, false);
        card1.addView(settingsPermStatus, topSpace(4));
        card1.addView(settingsTotalNotes, topSpace(2));

        Button permBtn = new Button(this);
        permBtn.setText("Allow / Manage File Access");
        permBtn.setAllCaps(false);
        permBtn.setTextSize(13);
        permBtn.setTextColor(Color.WHITE);
        permBtn.setBackground(bluePillDrawable(15));
        permBtn.setOnClickListener(v -> requestManageAllFilesPermission());
        applyPressFeedback(permBtn, false);
        LinearLayout.LayoutParams pBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        pBtnLp.topMargin = dp(12);
        card1.addView(permBtn, pBtnLp);
        page.addView(card1, topSpace(18));

        LinearLayout card2 = new LinearLayout(this);
        card2.setOrientation(LinearLayout.VERTICAL);
        card2.setBackground(whiteGlassDrawable(20, false));
        card2.setPadding(dp(16), dp(14), dp(16), dp(14));
        card2.addView(makeText("FONT & THEME", 11, COLOR_ACCENT_BLUE, true));
        card2.addView(makeText("White Glass + Soft Blue Smoke", 14.5f, COLOR_TEXT_PRIMARY, true), topSpace(4));
        settingsFontLabel = makeText("Editor Font: " + defaultFontSize + "sp", 13, COLOR_TEXT_SECONDARY, false);
        card2.addView(settingsFontLabel, topSpace(4));

        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fRowLp.topMargin = dp(10);

        Button smBtn = new Button(this);
        smBtn.setText("A-");
        smBtn.setAllCaps(false);
        smBtn.setTextColor(COLOR_TEXT_PRIMARY);
        smBtn.setBackground(whiteGlassDrawable(12, false));
        smBtn.setOnClickListener(v -> changeFontSize(-2));
        applyPressFeedback(smBtn, false);
        LinearLayout.LayoutParams smLp = new LinearLayout.LayoutParams(dp(52), dp(36));
        smLp.rightMargin = dp(10);
        fontRow.addView(smBtn, smLp);

        Button lgBtn = new Button(this);
        lgBtn.setText("A+");
        lgBtn.setAllCaps(false);
        lgBtn.setTextColor(COLOR_TEXT_PRIMARY);
        lgBtn.setBackground(whiteGlassDrawable(12, false));
        lgBtn.setOnClickListener(v -> changeFontSize(2));
        applyPressFeedback(lgBtn, false);
        fontRow.addView(lgBtn, new LinearLayout.LayoutParams(dp(52), dp(36)));
        card2.addView(fontRow, fRowLp);
        page.addView(card2, topSpace(14));

        LinearLayout card3 = new LinearLayout(this);
        card3.setOrientation(LinearLayout.VERTICAL);
        card3.setBackground(whiteGlassDrawable(20, false));
        card3.setPadding(dp(16), dp(14), dp(16), dp(14));
        card3.addView(makeText("ABOUT & PRIVACY", 11, COLOR_ACCENT_BLUE, true));
        card3.addView(makeText("Mirror Note • v1.0", 14, COLOR_TEXT_PRIMARY, true), topSpace(4));
        TextView priv = makeText(
                "Saved locally as UTF-8 .txt files inside Internal storage/Mirror Note. No cloud sync.",
                12.5f, COLOR_TEXT_SECONDARY, false);
        priv.setLineSpacing(dp(3), 1.15f);
        card3.addView(priv, topSpace(4));
        page.addView(card3, topSpace(14));

        return scroll;
    }

    private LinearLayout.LayoutParams topSpace(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(COLOR_DIVIDER);
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
    }

    // ==================== NAVIGATION ====================
    private void showHomeScreen() {
        editorScreen.setVisibility(View.GONE);
        settingsScreen.setVisibility(View.GONE);
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
                "Share Note",
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
                        shareEditorContent();
                    } else if (which == 4) {
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
                        lastDeletedFile = target;
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

    // ==================== CLEAN NOTES LIST ====================
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
                    ? "Tap + or Settings to allow file access."
                    : (query.isEmpty() ? "No notes yet. Tap + to write." : "No matching notes.");
            TextView empty = makeText(msg, 14, COLOR_TEXT_SECONDARY, false);
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
                TextView pHdr = makeText("PINNED", 11, COLOR_ACCENT_BLUE, true);
                LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                hLp.topMargin = dp(4);
                hLp.bottomMargin = dp(6);
                notesListContainer.addView(pHdr, hLp);
            } else if (!item.pinned && hasPinnedHeader && !hasAllHeader) {
                hasAllHeader = true;
                TextView aHdr = makeText("NOTES", 11, COLOR_TEXT_SECONDARY, true);
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
            TextView tView = makeText(prefix + item.title, 16.5f, COLOR_TEXT_PRIMARY, true);
            tView.setSingleLine(true);
            row.addView(tView);

            String cleanPreview = item.body.replace('\n', ' ').trim();
            if (cleanPreview.length() > 90) {
                cleanPreview = cleanPreview.substring(0, 90) + "…";
            }
            TextView pView = makeText(
                    cleanPreview.isEmpty() ? "No additional text" : cleanPreview,
                    13, COLOR_TEXT_SECONDARY, false);
            pView.setSingleLine(true);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pLp.topMargin = dp(3);
            row.addView(pView, pLp);

            TextView dView = makeText(sdf.format(new Date(item.lastModified)), 11, COLOR_TEXT_SECONDARY, false);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.topMargin = dp(4);
            row.addView(dView, dLp);

            row.setOnClickListener(v -> openEditorForExistingNote(item));
            row.setOnLongClickListener(v -> {
                showItemLongPressMenu(item);
                return true;
            });
            applyPressFeedback(row, false);

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
        input.setTextColor(COLOR_TEXT_PRIMARY);
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
