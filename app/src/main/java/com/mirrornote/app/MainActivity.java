package com.mirrornote.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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

    private static final int COLOR_BG_MAIN = 0xFF090B10;
    private static final int COLOR_BG_SEC = 0xFF0C0F16;
    private static final int COLOR_TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int COLOR_TEXT_SECONDARY = 0xFFA1A1AA;
    private static final int COLOR_ACCENT = 0xFFAAB8FF;
    private static final int COLOR_GLASS_FILL = 0x12FFFFFF;
    private static final int COLOR_GLASS_BORDER = 0x21FFFFFF;

    private static final String PREFS_NAME = "mirror_note_prefs";
    private static final String KEY_PINNED = "pinned_files";
    private static final String KEY_FAV = "fav_files";
    private static final String KEY_CREATED_MAP = "created_map_json";
    private static final String KEY_SORT_MODE = "sort_mode";
    private static final String KEY_FONT_SIZE = "font_size_sp";
    private static final String KEY_GLASS_INTENSITY = "glass_intensity";

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
    private View homeScreen;
    private View editorScreen;
    private View settingsScreen;

    private TextView countSummaryView;
    private TextView permissionBannerText;
    private Button permissionGrantBtn;
    private EditText searchInput;
    private View clearSearchBtn;
    private LinearLayout notesListContainer;
    private Button fabNewNote;

    private EditText editorTitleInput;
    private EditText editorBodyInput;
    private TextView editorMetaView;
    private TextView editorStatusView;

    private TextView settingsPermStatus;
    private TextView settingsTotalNotes;
    private TextView settingsFontLabel;
    private TextView settingsGlassLabel;

    private SharedPreferences prefs;
    private File notesFolder;
    private List<NoteEntry> allNotes = new ArrayList<>();

    private int sortMode = SORT_EDITED;
    private int defaultFontSize = 16;
    private int glassIntensity = 1; // 0 = Subtle, 1 = Balanced, 2 = Frosted

    private String currentEditingFileName = null;
    private String lastSavedSignature = "";
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
        defaultFontSize = prefs.getInt(KEY_FONT_SIZE, 16);
        glassIntensity = prefs.getInt(KEY_GLASS_INTENSITY, 1);

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
        getWindow().setStatusBarColor(COLOR_BG_MAIN);
        getWindow().setNavigationBarColor(COLOR_BG_MAIN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable glassDrawable(int radiusDp, boolean highlight) {
        GradientDrawable d = new GradientDrawable();
        int alphaHex = glassIntensity == 0 ? 0x0E : (glassIntensity == 2 ? 0x1C : 0x13);
        int fill = (alphaHex << 24) | 0x00FFFFFF;
        int stroke = highlight ? 0x55AAB8FF : COLOR_GLASS_BORDER;
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private GradientDrawable accentPillDrawable(int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(COLOR_ACCENT);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private void applyPressAnimation(View v, boolean isFab) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate()
                            .scaleX(isFab ? 0.92f : 0.97f)
                            .scaleY(isFab ? 0.92f : 0.97f)
                            .alpha(0.88f)
                            .setDuration(110)
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(200)
                            .start();
                    break;
            }
            return false;
        });
    }

    private void animateScreenIn(View screen) {
        screen.setVisibility(View.VISIBLE);
        screen.setAlpha(0f);
        screen.setTranslationY(dp(14));
        screen.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240)
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
        rootContainer.setBackgroundColor(COLOR_BG_MAIN);
        rootContainer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Subtle ambient mirror-lavender top glow
        View ambientGlow = new View(this);
        GradientDrawable glowGrad = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0x26AAB8FF, 0x10182238, 0x00090B10});
        ambientGlow.setBackground(glowGrad);
        rootContainer.addView(ambientGlow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

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
        scroll.setPadding(0, 0, 0, dp(96));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(26), dp(18), dp(24));
        scroll.addView(page);

        // Top Bar: Logo + Notes + Settings
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView logoBadge = makeText("M", 16, COLOR_BG_MAIN, true);
        logoBadge.setGravity(Gravity.CENTER);
        logoBadge.setBackground(accentPillDrawable(14));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        logoLp.rightMargin = dp(10);
        topBar.addView(logoBadge, logoLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleColLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleCol.addView(makeText("Notes", 26, COLOR_TEXT_PRIMARY, true));
        countSummaryView = makeText("0 notes", 12.5f, COLOR_TEXT_SECONDARY, false);
        titleCol.addView(countSummaryView);
        topBar.addView(titleCol, titleColLp);

        Button settingsBtn = new Button(this);
        settingsBtn.setText("Settings");
        settingsBtn.setAllCaps(false);
        settingsBtn.setTextSize(12.5f);
        settingsBtn.setTextColor(COLOR_TEXT_PRIMARY);
        settingsBtn.setBackground(glassDrawable(16, false));
        settingsBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
        settingsBtn.setOnClickListener(v -> showSettingsScreen());
        applyPressAnimation(settingsBtn, false);
        topBar.addView(settingsBtn);

        page.addView(topBar);

        // Search Bar
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(glassDrawable(18, false));
        searchRow.setPadding(dp(14), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        searchLp.topMargin = dp(16);
        searchLp.bottomMargin = dp(12);

        searchInput = new EditText(this);
        searchInput.setHint("Search title or content (English / हिन्दी)...");
        searchInput.setHintTextColor(COLOR_TEXT_SECONDARY);
        searchInput.setTextColor(COLOR_TEXT_PRIMARY);
        searchInput.setTextSize(14);
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

        // Sort Chips Row
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
            chip.setTextColor(active ? COLOR_BG_MAIN : COLOR_TEXT_SECONDARY);
            chip.setBackground(active ? accentPillDrawable(14) : glassDrawable(14, false));
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            chip.setMinimumHeight(0);
            chip.setOnClickListener(v -> {
                sortMode = mode;
                prefs.edit().putInt(KEY_SORT_MODE, sortMode).apply();
                showHomeScreen();
            });
            applyPressAnimation(chip, false);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            cLp.rightMargin = dp(8);
            sortRow.addView(chip, cLp);
        }
        LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sortLp.bottomMargin = dp(14);
        page.addView(sortScroll, sortLp);

        // Permission Banner (shown when permission needed)
        LinearLayout permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        permBox.setBackground(glassDrawable(18, true));
        permBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams permLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        permLp.bottomMargin = dp(14);

        permissionBannerText = makeText(
                "Grant file access to store notes in Internal Storage / Mirror Note.",
                12.5f, COLOR_TEXT_SECONDARY, false);
        permBox.addView(permissionBannerText);

        permissionGrantBtn = new Button(this);
        permissionGrantBtn.setText("Allow File Access");
        permissionGrantBtn.setAllCaps(false);
        permissionGrantBtn.setTextSize(13);
        permissionGrantBtn.setTextColor(COLOR_BG_MAIN);
        permissionGrantBtn.setBackground(accentPillDrawable(14));
        permissionGrantBtn.setOnClickListener(v -> requestManageAllFilesPermission());
        applyPressAnimation(permissionGrantBtn, false);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        pbLp.topMargin = dp(8);
        permBox.addView(permissionGrantBtn, pbLp);
        page.addView(permBox, permLp);

        // Notes List Container
        notesListContainer = new LinearLayout(this);
        notesListContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(notesListContainer);

        wrapper.addView(scroll);

        // Floating New Note (+) Button
        fabNewNote = new Button(this);
        fabNewNote.setText("+");
        fabNewNote.setTextSize(26);
        fabNewNote.setTextColor(COLOR_BG_MAIN);
        fabNewNote.setBackground(accentPillDrawable(28));
        fabNewNote.setElevation(dp(8));
        fabNewNote.setOnClickListener(v -> openEditorForNewNote());
        applyPressAnimation(fabNewNote, true);

        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(58), dp(58));
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.rightMargin = dp(20);
        fabLp.bottomMargin = dp(24);
        wrapper.addView(fabNewNote, fabLp);

        return wrapper;
    }

    // ==================== FULL-SCREEN EDITOR ====================
    private View buildEditorScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(COLOR_BG_MAIN);
        screen.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screen.setPadding(dp(16), dp(22), dp(16), dp(14));

        // Top Bar: Back | Save | More
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        Button backBtn = new Button(this);
        backBtn.setText("← Notes");
        backBtn.setAllCaps(false);
        backBtn.setTextSize(13);
        backBtn.setTextColor(COLOR_TEXT_PRIMARY);
        backBtn.setBackground(glassDrawable(16, false));
        backBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        backBtn.setOnClickListener(v -> {
            performAutosave();
            hideKeyboard(editorBodyInput);
            showHomeScreen();
        });
        applyPressAnimation(backBtn, false);
        topBar.addView(backBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        Button saveBtn = new Button(this);
        saveBtn.setText("Save");
        saveBtn.setAllCaps(false);
        saveBtn.setTextSize(13);
        saveBtn.setTextColor(COLOR_BG_MAIN);
        saveBtn.setBackground(accentPillDrawable(16));
        saveBtn.setPadding(dp(16), dp(6), dp(16), dp(6));
        saveBtn.setOnClickListener(v -> {
            boolean ok = saveCurrentNote(true);
            if (ok) {
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            }
        });
        applyPressAnimation(saveBtn, false);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        saveLp.rightMargin = dp(8);
        topBar.addView(saveBtn, saveLp);

        Button moreBtn = new Button(this);
        moreBtn.setText("•••");
        moreBtn.setAllCaps(false);
        moreBtn.setTextSize(13);
        moreBtn.setTextColor(COLOR_TEXT_PRIMARY);
        moreBtn.setBackground(glassDrawable(16, false));
        moreBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        moreBtn.setOnClickListener(v -> showEditorMoreOptions());
        applyPressAnimation(moreBtn, false);
        topBar.addView(moreBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));

        screen.addView(topBar);

        // Title Input
        editorTitleInput = new EditText(this);
        editorTitleInput.setHint("Title");
        editorTitleInput.setHintTextColor(COLOR_TEXT_SECONDARY);
        editorTitleInput.setTextColor(COLOR_TEXT_PRIMARY);
        editorTitleInput.setTextSize(22);
        editorTitleInput.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        editorTitleInput.setSingleLine(true);
        editorTitleInput.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(14);
        titleLp.bottomMargin = dp(6);
        screen.addView(editorTitleInput, titleLp);

        // Word & Character Meta Line
        editorMetaView = makeText("0 words • 0 chars", 11.5f, COLOR_TEXT_SECONDARY, false);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.bottomMargin = dp(8);
        screen.addView(editorMetaView, metaLp);

        View divider = new View(this);
        divider.setBackgroundColor(COLOR_GLASS_BORDER);
        screen.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Full-Screen Content Area
        editorBodyInput = new EditText(this);
        editorBodyInput.setHint("Start writing...");
        editorBodyInput.setHintTextColor(COLOR_TEXT_SECONDARY);
        editorBodyInput.setTextColor(COLOR_TEXT_PRIMARY);
        editorBodyInput.setTextSize(defaultFontSize);
        editorBodyInput.setLineSpacing(dp(4), 1.15f);
        editorBodyInput.setGravity(Gravity.TOP | Gravity.START);
        editorBodyInput.setBackgroundColor(Color.TRANSPARENT);
        editorBodyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateEditorCounts();
                scheduleAutosave();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        editorTitleInput.addTextChangedListener(watcher);
        editorBodyInput.addTextChangedListener(watcher);

        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(10);
        screen.addView(editorBodyInput, bodyLp);

        editorStatusView = makeText("Mirror Note • UTF-8", 11.5f, COLOR_TEXT_SECONDARY, false);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(6);
        screen.addView(editorStatusView, stLp);

        return screen;
    }

    // ==================== SETTINGS SCREEN ====================
    private View buildSettingsScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG_MAIN);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(26), dp(18), dp(30));
        scroll.addView(page);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button backBtn = new Button(this);
        backBtn.setText("← Back");
        backBtn.setAllCaps(false);
        backBtn.setTextSize(13);
        backBtn.setTextColor(COLOR_TEXT_PRIMARY);
        backBtn.setBackground(glassDrawable(16, false));
        backBtn.setPadding(dp(14), dp(6), dp(14), dp(6));
        backBtn.setOnClickListener(v -> showHomeScreen());
        applyPressAnimation(backBtn, false);
        top.addView(backBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));

        TextView heading = makeText("Settings", 22, COLOR_TEXT_PRIMARY, true);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.leftMargin = dp(14);
        top.addView(heading, hLp);
        page.addView(top);

        // Storage & Permission Card
        LinearLayout storageCard = new LinearLayout(this);
        storageCard.setOrientation(LinearLayout.VERTICAL);
        storageCard.setBackground(glassDrawable(20, false));
        storageCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scLp.topMargin = dp(18);

        storageCard.addView(makeText("STORAGE & PERMISSION", 11, COLOR_ACCENT, true));
        storageCard.addView(makeText("Location: Internal storage/Mirror Note", 13.5f, COLOR_TEXT_PRIMARY, true));
        settingsPermStatus = makeText("Permission: Checking...", 12.5f, COLOR_TEXT_SECONDARY, false);
        settingsTotalNotes = makeText("Total Notes: 0", 12.5f, COLOR_TEXT_SECONDARY, false);
        storageCard.addView(settingsPermStatus);
        storageCard.addView(settingsTotalNotes);

        Button permBtn = new Button(this);
        permBtn.setText("Manage File Permission");
        permBtn.setAllCaps(false);
        permBtn.setTextSize(13);
        permBtn.setTextColor(COLOR_BG_MAIN);
        permBtn.setBackground(accentPillDrawable(15));
        permBtn.setOnClickListener(v -> requestManageAllFilesPermission());
        applyPressAnimation(permBtn, false);
        LinearLayout.LayoutParams pBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        pBtnLp.topMargin = dp(12);
        storageCard.addView(permBtn, pBtnLp);
        page.addView(storageCard, scLp);

        // Appearance Card
        LinearLayout appearCard = new LinearLayout(this);
        appearCard.setOrientation(LinearLayout.VERTICAL);
        appearCard.setBackground(glassDrawable(20, false));
        appearCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acLp.topMargin = dp(14);

        appearCard.addView(makeText("APPEARANCE", 11, COLOR_ACCENT, true));
        settingsFontLabel = makeText("Editor Font Size: " + defaultFontSize + "sp", 13.5f, COLOR_TEXT_PRIMARY, true);
        appearCard.addView(settingsFontLabel);

        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fRowLp.topMargin = dp(8);
        fRowLp.bottomMargin = dp(12);

        Button smallerBtn = new Button(this);
        smallerBtn.setText("A- Smaller");
        smallerBtn.setAllCaps(false);
        smallerBtn.setTextColor(COLOR_TEXT_PRIMARY);
        smallerBtn.setBackground(glassDrawable(14, false));
        smallerBtn.setOnClickListener(v -> changeFontSize(-2));
        applyPressAnimation(smallerBtn, false);
        LinearLayout.LayoutParams smLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        smLp.rightMargin = dp(8);
        fontRow.addView(smallerBtn, smLp);

        Button largerBtn = new Button(this);
        largerBtn.setText("A+ Larger");
        largerBtn.setAllCaps(false);
        largerBtn.setTextColor(COLOR_TEXT_PRIMARY);
        largerBtn.setBackground(glassDrawable(14, false));
        largerBtn.setOnClickListener(v -> changeFontSize(2));
        applyPressAnimation(largerBtn, false);
        fontRow.addView(largerBtn, new LinearLayout.LayoutParams(0, dp(38), 1f));
        appearCard.addView(fontRow, fRowLp);

        settingsGlassLabel = makeText("Glass Surface: " + glassIntensityLabel(), 13.5f, COLOR_TEXT_PRIMARY, true);
        appearCard.addView(settingsGlassLabel);

        Button glassBtn = new Button(this);
        glassBtn.setText("Cycle Glass Intensity");
        glassBtn.setAllCaps(false);
        glassBtn.setTextColor(COLOR_TEXT_PRIMARY);
        glassBtn.setBackground(glassDrawable(14, false));
        glassBtn.setOnClickListener(v -> {
            glassIntensity = (glassIntensity + 1) % 3;
            prefs.edit().putInt(KEY_GLASS_INTENSITY, glassIntensity).apply();
            refreshSettingsLabels();
            showHomeScreen();
        });
        applyPressAnimation(glassBtn, false);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        gLp.topMargin = dp(8);
        appearCard.addView(glassBtn, gLp);
        page.addView(appearCard, acLp);

        // About & Privacy Card
        LinearLayout aboutCard = new LinearLayout(this);
        aboutCard.setOrientation(LinearLayout.VERTICAL);
        aboutCard.setBackground(glassDrawable(20, false));
        aboutCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abLp.topMargin = dp(14);

        aboutCard.addView(makeText("ABOUT & PRIVACY", 11, COLOR_ACCENT, true));
        aboutCard.addView(makeText("Mirror Note • Version 1.0", 14, COLOR_TEXT_PRIMARY, true));
        TextView privText = makeText(
                "All notes stay locally on your device as plain UTF-8 .txt files inside Internal storage/Mirror Note. No cloud upload or tracking.",
                12.5f, COLOR_TEXT_SECONDARY, false);
        privText.setLineSpacing(dp(3), 1.1f);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prLp.topMargin = dp(6);
        aboutCard.addView(privText, prLp);
        page.addView(aboutCard, abLp);

        return scroll;
    }

    private String glassIntensityLabel() {
        if (glassIntensity == 0) return "Subtle";
        if (glassIntensity == 2) return "Frosted";
        return "Balanced";
    }

    private void changeFontSize(int delta) {
        defaultFontSize = Math.max(13, Math.min(24, defaultFontSize + delta));
        prefs.edit().putInt(KEY_FONT_SIZE, defaultFontSize).apply();
        if (editorBodyInput != null) {
            editorBodyInput.setTextSize(defaultFontSize);
        }
        refreshSettingsLabels();
    }

    private void refreshSettingsLabels() {
        if (settingsPermStatus != null) {
            settingsPermStatus.setText("Permission: " + (hasStorageAccess() ? "Granted" : "Not Granted"));
        }
        if (settingsTotalNotes != null) {
            settingsTotalNotes.setText("Total Notes: " + allNotes.size());
        }
        if (settingsFontLabel != null) {
            settingsFontLabel.setText("Editor Font Size: " + defaultFontSize + "sp");
        }
        if (settingsGlassLabel != null) {
            settingsGlassLabel.setText("Glass Surface: " + glassIntensityLabel());
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
        currentEditingFileName = null;
        editorTitleInput.setText("");
        editorBodyInput.setText("");
        editorBodyInput.setTextSize(defaultFontSize);
        lastSavedSignature = "";
        editorStatusView.setText("New note • Saves as <Title>.txt");
        updateEditorCounts();

        homeScreen.setVisibility(View.GONE);
        settingsScreen.setVisibility(View.GONE);
        animateScreenIn(editorScreen);

        editorTitleInput.requestFocus();
        showKeyboard(editorTitleInput);
    }

    private void openEditorForExistingNote(NoteEntry entry) {
        currentEditingFileName = entry.fileName;
        editorTitleInput.setText(entry.title);
        editorBodyInput.setText(entry.body);
        editorBodyInput.setTextSize(defaultFontSize);
        lastSavedSignature = entry.title + "\n" + entry.body;
        editorStatusView.setText("Editing " + entry.fileName);
        updateEditorCounts();

        homeScreen.setVisibility(View.GONE);
        settingsScreen.setVisibility(View.GONE);
        animateScreenIn(editorScreen);
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
                    Toast.makeText(this, "Open Settings > Apps > Mirror Note > Files", Toast.LENGTH_LONG).show();
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
        boolean granted = hasStorageAccess();
        if (granted) {
            boolean ready = ensureMirrorNoteFolder();
            permissionBannerText.setText(ready
                    ? "Active • Internal storage/Mirror Note"
                    : "Unable to create Mirror Note folder.");
            permissionGrantBtn.setVisibility(View.GONE);
            loadNotesFromDisk();
        } else {
            permissionBannerText.setText("File access not granted. Tap below to allow access to Internal storage/Mirror Note.");
            permissionGrantBtn.setVisibility(View.VISIBLE);
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
                Toast.makeText(this, "Allow file access first to save .txt files.", Toast.LENGTH_LONG).show();
                requestManageAllFilesPermission();
            }
            return false;
        }

        String rawTitle = editorTitleInput.getText().toString().trim();
        String body = editorBodyInput.getText().toString();

        if (rawTitle.isEmpty() && body.trim().isEmpty()) {
            if (showValidationToast) {
                Toast.makeText(this, "Empty note — write a title or note first.", Toast.LENGTH_SHORT).show();
            }
            return false;
        }

        String baseTitle = sanitizeBaseName(rawTitle.isEmpty() ? firstLineOrUntitled(body) : rawTitle);
        String targetFileName = baseTitle + ".txt";

        // Avoid accidental overwrite of another note if creating new or renaming to an existing file
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

        // If title changed on an existing note, move metadata and delete old file
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
        String timeStr = new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date());
        editorStatusView.setText("Saved " + targetFileName + " • " + timeStr);
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
                    String title = editorTitleInput.getText().toString().trim();
                    String body = editorBodyInput.getText().toString();
                    if (which == 0) {
                        if (saveCurrentNote(false) && currentEditingFileName != null) {
                            toggleSetPref(KEY_PINNED, currentEditingFileName);
                            Toast.makeText(this, "Pin status updated", Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 1) {
                        if (saveCurrentNote(false) && currentEditingFileName != null) {
                            toggleSetPref(KEY_FAV, currentEditingFileName);
                            Toast.makeText(this, "Favourite status updated", Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 2) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText(title.isEmpty() ? "Note" : title, body));
                            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 3) {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("text/plain");
                        share.putExtra(Intent.EXTRA_SUBJECT, title.isEmpty() ? "Note" : title);
                        share.putExtra(Intent.EXTRA_TEXT, (title.isEmpty() ? "" : title + "\n\n") + body);
                        startActivity(Intent.createChooser(share, "Share note"));
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
                .setMessage("Delete \"" + target.getName() + "\" from Mirror Note folder?")
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
                .setTitle("Note Deleted")
                .setMessage("Deleted " + lastDeletedName + ". Undo?")
                .setPositiveButton("Undo", (d, w) -> {
                    if (ensureMirrorNoteFolder()) {
                        File restored = new File(notesFolder, lastDeletedName);
                        try (FileOutputStream fos = new FileOutputStream(restored, false)) {
                            fos.write(lastDeletedBytes);
                            fos.flush();
                            ensureCreatedTimestamp(lastDeletedName);
                            checkPermissionAndLoad(false);
                            Toast.makeText(this, "Restored " + lastDeletedName, Toast.LENGTH_SHORT).show();
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNegativeButton("OK", null)
                .show();
    }

    // ==================== LIST RENDERING ====================
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
            TextView empty = makeText(
                    query.isEmpty()
                            ? "No notes yet. Tap + to create your first note."
                            : "No matching notes found.",
                    13.5f, COLOR_TEXT_SECONDARY, false);
            empty.setPadding(dp(4), dp(24), dp(4), dp(24));
            notesListContainer.addView(empty);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
        boolean hasPinnedHeader = false;
        boolean hasAllHeader = false;

        for (NoteEntry item : filtered) {
            if (item.pinned && !hasPinnedHeader) {
                hasPinnedHeader = true;
                TextView pHdr = makeText("PINNED", 11, COLOR_ACCENT, true);
                LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                hLp.bottomMargin = dp(8);
                notesListContainer.addView(pHdr, hLp);
            } else if (!item.pinned && hasPinnedHeader && !hasAllHeader) {
                hasAllHeader = true;
                TextView aHdr = makeText("NOTES", 11, COLOR_TEXT_SECONDARY, true);
                LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                hLp.topMargin = dp(10);
                hLp.bottomMargin = dp(8);
                notesListContainer.addView(aHdr, hLp);
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(glassDrawable(18, item.pinned));
            row.setPadding(dp(15), dp(13), dp(15), dp(13));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = dp(10);

            String badgePrefix = (item.pinned ? "📌 " : "") + (item.favourite ? "★ " : "");
            TextView tView = makeText(badgePrefix + item.title, 15.5f, COLOR_TEXT_PRIMARY, true);
            tView.setSingleLine(true);
            row.addView(tView);

            String cleanPreview = item.body.replace('\n', ' ').trim();
            if (cleanPreview.length() > 95) {
                cleanPreview = cleanPreview.substring(0, 95) + "…";
            }
            TextView pView = makeText(
                    cleanPreview.isEmpty() ? "Empty note" : cleanPreview,
                    13, COLOR_TEXT_SECONDARY, false);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pLp.topMargin = dp(3);
            row.addView(pView, pLp);

            TextView dView = makeText(
                    sdf.format(new Date(item.lastModified)),
                    11, COLOR_TEXT_SECONDARY, false);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.topMargin = dp(6);
            row.addView(dView, dLp);

            row.setOnClickListener(v -> openEditorForExistingNote(item));
            row.setOnLongClickListener(v -> {
                showItemLongPressMenu(item);
                return true;
            });
            applyPressAnimation(row, false);

            notesListContainer.addView(row, rLp);
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
