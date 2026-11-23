package com.mirrornote.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.text.InputType;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private EditText titleInput, bodyInput;
    private TextView statusText, permissionText;
    private LinearLayout notesList;
    private File notesFolder;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        checkPermissionAndSetup();
    }


    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density); }

    private android.graphics.drawable.GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView label(String text, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(0xFFFFFFFF);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void pressFeedback(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.975f).scaleY(0.975f).alpha(0.88f)
                        .setDuration(110).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(220).setInterpolator(new android.view.animation.OvershootInterpolator(1.15f)).start();
                    break;
            }
            return false;
        });
    }

    private LinearLayout glassCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(0x18FFFFFF, 0x29FFFFFF, 22));
        card.setElevation(dp(5));
        return card;
    }

    private void buildUi() {
        getWindow().setStatusBarColor(0xFF06070A);
        getWindow().setNavigationBarColor(0xFF06070A);
        getWindow().getDecorView().setSystemUiVisibility(0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(18));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(30), dp(20), dp(28));
        page.setBackgroundColor(0xFF06070A);
        scroll.addView(page);

        // Soft ambient glows behind the content.
        TextView glow = new TextView(this);
        glow.setBackground(new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            new int[]{0x332B4D86, 0x110F1728, 0x0006070A}));
        LinearLayout.LayoutParams glowParams = new LinearLayout.LayoutParams(-1, dp(120));
        glowParams.bottomMargin = -dp(90);
        page.addView(glow, glowParams);

        TextView brand = label("Mirror Note", 30, true);
        brand.setLetterSpacing(0.01f);
        page.addView(brand);

        TextView sub = label("A quiet space for your thoughts.", 14, false);
        sub.setTextColor(0xFFA1A1AA);
        LinearLayout.LayoutParams subP = new LinearLayout.LayoutParams(-1, -2);
        subP.topMargin = dp(5);
        subP.bottomMargin = dp(22);
        page.addView(sub, subP);

        LinearLayout permissionCard = glassCard();
        permissionText = label("Checking file access…", 13, false);
        permissionText.setTextColor(0xFFA1A1AA);
        permissionCard.addView(permissionText);

        Button access = new Button(this);
        access.setText("Allow file access");
        access.setAllCaps(false);
        access.setTextColor(0xFF06070A);
        access.setTextSize(14);
        access.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        access.setBackground(rounded(0xFFFFFFFF, 0xFFFFFFFF, 18));
        access.setOnClickListener(v -> requestFileAccess());
        pressFeedback(access);
        LinearLayout.LayoutParams accessP = new LinearLayout.LayoutParams(-1, dp(48));
        accessP.topMargin = dp(14);
        permissionCard.addView(access, accessP);
        LinearLayout.LayoutParams pcP = new LinearLayout.LayoutParams(-1, -2);
        pcP.bottomMargin = dp(20);
        page.addView(permissionCard, pcP);

        LinearLayout editorCard = glassCard();
        TextView titleLabel = label("NOTE TITLE", 11, true);
        titleLabel.setTextColor(0xFFA1A1AA);
        editorCard.addView(titleLabel);

        titleInput = new EditText(this);
        titleInput.setSingleLine(true);
        titleInput.setHint("e.g. My first note");
        titleInput.setTextSize(15);
        titleInput.setTextColor(0xFFFFFFFF);
        titleInput.setHintTextColor(0xFF777C87);
        titleInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        titleInput.setBackground(rounded(0x14FFFFFF, 0x29FFFFFF, 16));
        LinearLayout.LayoutParams titleP = new LinearLayout.LayoutParams(-1, dp(50));
        titleP.topMargin = dp(8);
        editorCard.addView(titleInput, titleP);

        TextView bodyLabel = label("NOTE CONTENT", 11, true);
        bodyLabel.setTextColor(0xFFA1A1AA);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(18);
        editorCard.addView(bodyLabel, blp);

        bodyInput = new EditText(this);
        bodyInput.setHint("Write something…");
        bodyInput.setTextColor(0xFFFFFFFF);
        bodyInput.setHintTextColor(0xFF777C87);
        bodyInput.setGravity(Gravity.TOP | Gravity.START);
        bodyInput.setTextSize(15);
        bodyInput.setMinLines(8);
        bodyInput.setPadding(dp(14), dp(13), dp(14), dp(13));
        bodyInput.setBackground(rounded(0x14FFFFFF, 0x29FFFFFF, 16));
        bodyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams bodyP = new LinearLayout.LayoutParams(-1, -2);
        bodyP.topMargin = dp(8);
        editorCard.addView(bodyInput, bodyP);

        Button save = new Button(this);
        save.setText("Save note as .txt");
        save.setAllCaps(false);
        save.setTextColor(0xFF06070A);
        save.setTextSize(15);
        save.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        save.setBackground(rounded(0xFFFFFFFF, 0xFFFFFFFF, 19));
        save.setOnClickListener(v -> saveNote());
        pressFeedback(save);
        LinearLayout.LayoutParams saveP = new LinearLayout.LayoutParams(-1, dp(52));
        saveP.topMargin = dp(16);
        editorCard.addView(save, saveP);

        statusText = label("", 12, false);
        statusText.setTextColor(0xFFA1A1AA);
        LinearLayout.LayoutParams statusP = new LinearLayout.LayoutParams(-1, -2);
        statusP.topMargin = dp(10);
        editorCard.addView(statusText, statusP);

        LinearLayout.LayoutParams editorP = new LinearLayout.LayoutParams(-1, -2);
        editorP.bottomMargin = dp(26);
        page.addView(editorCard, editorP);

        TextView recent = label("SAVED NOTES", 12, true);
        recent.setTextColor(0xFFA1A1AA);
        LinearLayout.LayoutParams recentP = new LinearLayout.LayoutParams(-1, -2);
        recentP.bottomMargin = dp(12);
        page.addView(recent, recentP);

        notesList = new LinearLayout(this);
        notesList.setOrientation(LinearLayout.VERTICAL);
        page.addView(notesList);

        setContentView(scroll);
        page.setAlpha(0f);
        page.setTranslationY(dp(12));
        page.animate().alpha(1f).translationY(0f).setDuration(420)
            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(6);
        return p;
    }

    private void checkPermissionAndSetup() {
        if (BuildVersionAtLeast30() && !Environment.isExternalStorageManager()) {
            permissionText.setText("File access is not enabled. Allow it to create /Mirror Note.");
        } else {
            setupFolder();
        }
    }

    private boolean BuildVersionAtLeast30() { return android.os.Build.VERSION.SDK_INT >= 30; }

    private void requestFileAccess() {
        try {
            if (BuildVersionAtLeast30()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                setupFolder();
            }
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (permissionText != null && (!BuildVersionAtLeast30() || Environment.isExternalStorageManager())) setupFolder();
    }

    private void setupFolder() {
        if (BuildVersionAtLeast30() && !Environment.isExternalStorageManager()) return;
        File base = Environment.getExternalStorageDirectory();
        notesFolder = new File(base, "Mirror Note");
        if (!notesFolder.exists() && !notesFolder.mkdirs()) {
            permissionText.setText("Could not create Mirror Note folder. Check storage access.");
            return;
        }
        permissionText.setText("Ready • /Internal storage/Mirror Note");
        refreshNotes();
    }

    private String safeFileName(String raw) {
        String input = raw.trim();
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '/' || c == '\\\\' || c == ':' || c == '*' || c == '?' ||
                c == '"' || c == '<' || c == '>' || c == '|') {
                cleaned.append('_');
            } else {
                cleaned.append(c);
            }
        }
        String s = cleaned.toString().replaceAll("[. ]+$", "");
        if (s.isEmpty()) s = "Untitled";
        if (s.length() > 80) s = s.substring(0, 80);
        return s;
    }

    private void saveNote() {
        if (BuildVersionAtLeast30() && !Environment.isExternalStorageManager()) {
            statusText.setText("Please allow file access first.");
            requestFileAccess(); return;
        }
        setupFolder();
        if (notesFolder == null || !notesFolder.canWrite()) {
            statusText.setText("Folder is not writable."); return;
        }
        String title = titleInput.getText().toString().trim();
        String body = bodyInput.getText().toString();
        if (title.isEmpty()) { titleInput.setError("Enter a title"); return; }
        File out = new File(notesFolder, safeFileName(title) + ".txt");
        String content = title + "\n" +
                "Saved: " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()) +
                "\n\n" + body;
        try (FileOutputStream stream = new FileOutputStream(out, false)) {
            stream.write(content.getBytes(StandardCharsets.UTF_8));
            statusText.setText("Saved: " + out.getName());
            refreshNotes();
        } catch (Exception e) {
            statusText.setText("Save failed: " + e.getMessage());
        }
    }


    private void refreshNotes() {
        if (notesList == null || notesFolder == null || !notesFolder.exists()) return;
        notesList.removeAllViews();
        File[] files = notesFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (files == null || files.length == 0) {
            TextView empty = label("No notes saved yet.", 14, false);
            empty.setTextColor(0xFFA1A1AA);
            LinearLayout emptyCard = glassCard();
            emptyCard.addView(empty);
            notesList.addView(emptyCard);
            return;
        }
        java.util.Arrays.sort(files, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(16), dp(14), dp(16), dp(14));
            item.setBackground(rounded(0x18FFFFFF, 0x29FFFFFF, 19));
            item.setElevation(dp(3));

            TextView name = label(f.getName().replaceFirst("(?i)\\\\.txt$", ""), 15, true);
            name.setTextColor(0xFFFFFFFF);
            item.addView(name);

            TextView hint = label("Tap to open and edit", 11, false);
            hint.setTextColor(0xFFA1A1AA);
            LinearLayout.LayoutParams hintP = new LinearLayout.LayoutParams(-1, -2);
            hintP.topMargin = dp(4);
            item.addView(hint, hintP);

            item.setOnClickListener(v -> openNote(f));
            pressFeedback(item);
            LinearLayout.LayoutParams itemP = new LinearLayout.LayoutParams(-1, -2);
            itemP.bottomMargin = dp(10);
            notesList.addView(item, itemP);
        }
    }

    private void openNote(File f) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            String text = new String(bytes, StandardCharsets.UTF_8);
            int split = text.indexOf("\n\n");
            String heading = text.split("\\n", 2)[0];
            titleInput.setText(heading);
            bodyInput.setText(split >= 0 ? text.substring(split + 2) : text);
            statusText.setText("Loaded: " + f.getName() + " • Save to update");
        } catch (Exception e) {
            statusText.setText("Could not open note.");
        }
    }
}
