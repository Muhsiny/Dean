package com.sayeh.aifilm;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_MEDIA = 2001;
    private static final int REQ_SAVE_AUDIO = 2002;
    private static final String MODEL_DIR = "vits-piper-fa_IR-amir-medium";

    private OfflineTts tts;
    private volatile boolean ttsReady = false;
    private volatile boolean synthesizing = false;
    private EditText script;
    private TextView ttsStatus;
    private TextView mediaStatus;
    private SeekBar speedBar;
    private Uri pendingAudioDestination;
    private File generatedAudioFile;
    private MediaPlayer mediaPlayer;
    private final List<Uri> selectedMedia = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(15, 48, 36));
        buildUi();
        initializeEmbeddedPersianTts();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(Color.rgb(245, 248, 246));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("استودیو سایه | فیلم‌ساز هوش مصنوعی");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(15, 60, 43));
        title.setGravity(Gravity.RIGHT);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("موتور فارسی داخلی و آفلاین — بدون وابستگی به صدای سیستم");
        sub.setTextSize(14);
        sub.setTextColor(Color.DKGRAY);
        sub.setGravity(Gravity.RIGHT);
        sub.setPadding(0, 0, 0, dp(16));
        root.addView(sub);

        section(root, "متن و گویندگی فارسی");

        script = new EditText(this);
        script.setMinLines(8);
        script.setGravity(Gravity.TOP | Gravity.RIGHT);
        script.setTextDirection(View.TEXT_DIRECTION_RTL);
        script.setText("در دل کوهستان‌های افغانستان، تاریخ همیشه با صدای جنگ روایت نشده است؛ گاهی صدای یک اندیشه، یک تصمیم و یک رهبر، از صدای سلاح ماندگارتر بوده است. آیت‌الله سیدعلی بهشتی یکی از چهره‌های مهم سیاسی و اجتماعی هزاره‌جات بود. این متن برای آزمایش گویندگی فارسی در استودیو سایه خوانده می‌شود.");
        script.setTextSize(17);
        script.setPadding(dp(12), dp(12), dp(12), dp(12));
        script.setBackgroundColor(Color.WHITE);
        root.addView(script, new LinearLayout.LayoutParams(-1, dp(210)));

        ttsStatus = new TextView(this);
        ttsStatus.setText("در حال بارگذاری موتور فارسی داخلی…");
        ttsStatus.setTextColor(Color.rgb(90, 90, 90));
        ttsStatus.setPadding(0, dp(10), 0, dp(8));
        root.addView(ttsStatus);

        TextView voiceLabel = new TextView(this);
        voiceLabel.setText("صدا: فارسی — Amir (Piper / Sherpa-ONNX، آفلاین)");
        voiceLabel.setTextColor(Color.rgb(50, 80, 65));
        voiceLabel.setPadding(0, dp(4), 0, dp(8));
        root.addView(voiceLabel);

        TextView speedLabel = new TextView(this);
        speedLabel.setText("سرعت گفتار");
        root.addView(speedLabel);
        speedBar = new SeekBar(this);
        speedBar.setMax(100);
        speedBar.setProgress(42);
        root.addView(speedBar);

        Button speak = button("▶ تولید و پخش آزمایشی صدا");
        speak.setOnClickListener(v -> synthesize(true));
        root.addView(speak);

        Button save = button("ذخیرهٔ صدا به WAV");
        save.setOnClickListener(v -> chooseAudioDestination());
        root.addView(save);

        section(root, "تصویر و ویدیو");
        Button addMedia = button("+ افزودن تصویر یا ویدیو");
        addMedia.setOnClickListener(v -> pickMedia());
        root.addView(addMedia);

        mediaStatus = new TextView(this);
        mediaStatus.setText("هنوز رسانه‌ای انتخاب نشده است.");
        mediaStatus.setTextSize(15);
        mediaStatus.setGravity(Gravity.RIGHT);
        mediaStatus.setPadding(0, dp(10), 0, dp(10));
        root.addView(mediaStatus);

        Button clear = button("پاک‌کردن رسانه‌های پروژه");
        clear.setOnClickListener(v -> {
            selectedMedia.clear();
            updateMediaStatus();
        });
        root.addView(clear);

        section(root, "وضعیت نسخه");
        TextView note = new TextView(this);
        note.setText("گویندگی فارسی این نسخه داخل خود برنامه اجرا می‌شود و برای تولید صدا به Google TTS، موتور گفتار تلفن یا اینترنت وابسته نیست. مدل و موتور محاسباتی همراه APK بسته‌بندی شده‌اند.");
        note.setTextSize(14);
        note.setTextColor(Color.DKGRAY);
        note.setGravity(Gravity.RIGHT);
        root.addView(note);

        setContentView(scroll);
    }

    private void initializeEmbeddedPersianTts() {
        new Thread(() -> {
            try {
                runOnUiThread(() -> ttsStatus.setText("در حال آماده‌سازی داده‌های تلفظ فارسی…"));

                File dataRoot = new File(getFilesDir(), "sherpa-fa-data");
                File espeakDir = new File(dataRoot, "espeak-ng-data");
                File marker = new File(dataRoot, ".ready-v2");
                if (!marker.exists()) {
                    deleteRecursively(dataRoot);
                    if (!dataRoot.mkdirs() && !dataRoot.exists()) {
                        throw new IllegalStateException("ساخت پوشهٔ داده ممکن نشد");
                    }
                    copyAssetTree(MODEL_DIR + "/espeak-ng-data", espeakDir);
                    if (!marker.createNewFile()) {
                        throw new IllegalStateException("ثبت آماده‌سازی مدل ممکن نشد");
                    }
                }

                OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
                vits.setModel(MODEL_DIR + "/fa_IR-amir-medium.onnx");
                vits.setTokens(MODEL_DIR + "/tokens.txt");
                vits.setDataDir(espeakDir.getAbsolutePath());

                OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
                modelConfig.setVits(vits);
                modelConfig.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
                modelConfig.setDebug(false);
                modelConfig.setProvider("cpu");

                OfflineTtsConfig config = new OfflineTtsConfig();
                config.setModel(modelConfig);
                config.setMaxNumSentences(1);
                config.setSilenceScale(0.2f);

                tts = new OfflineTts(getAssets(), config);
                generatedAudioFile = new File(getFilesDir(), "sayeh-persian-generated.wav");
                ttsReady = true;

                runOnUiThread(() -> {
                    ttsStatus.setText("✓ موتور فارسی داخلی آماده است | آفلاین");
                    ttsStatus.setTextColor(Color.rgb(18, 110, 65));
                });
            } catch (Throwable e) {
                ttsReady = false;
                runOnUiThread(() -> {
                    ttsStatus.setText("خطای موتور داخلی: " + safeMessage(e));
                    ttsStatus.setTextColor(Color.rgb(170, 30, 30));
                    Toast.makeText(MainActivity.this, "راه‌اندازی موتور فارسی داخلی ناموفق بود.", Toast.LENGTH_LONG).show();
                });
            }
        }, "sayeh-fa-tts-init").start();
    }

    private void synthesize(boolean autoPlay) {
        final String text = script.getText().toString().trim();
        if (!ttsReady || tts == null) {
            Toast.makeText(this, "موتور فارسی داخلی هنوز بارگذاری نشده است.", Toast.LENGTH_LONG).show();
            return;
        }
        if (synthesizing) {
            Toast.makeText(this, "تولید صدا در حال انجام است.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (text.isEmpty()) {
            Toast.makeText(this, "متن را وارد کن.", Toast.LENGTH_SHORT).show();
            return;
        }

        synthesizing = true;
        ttsStatus.setText("در حال تولید گفتار فارسی روی دستگاه…");
        final float speed = 0.72f + (speedBar.getProgress() / 100f) * 0.78f;

        new Thread(() -> {
            try {
                GeneratedAudio audio = tts.generate(text, 0, speed);
                if (audio == null || audio.getSamples() == null || audio.getSamples().length == 0) {
                    throw new IllegalStateException("خروجی صوتی خالی بود");
                }
                if (generatedAudioFile.exists()) generatedAudioFile.delete();
                boolean saved = audio.save(generatedAudioFile.getAbsolutePath());
                if (!saved || !generatedAudioFile.exists() || generatedAudioFile.length() == 0) {
                    throw new IllegalStateException("ذخیرهٔ WAV ناموفق بود");
                }

                if (pendingAudioDestination != null) {
                    copyGeneratedAudioToDestination();
                }

                runOnUiThread(() -> {
                    ttsStatus.setText("✓ گفتار فارسی تولید شد | آفلاین");
                    ttsStatus.setTextColor(Color.rgb(18, 110, 65));
                    if (autoPlay) playGeneratedAudio();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    ttsStatus.setText("خطا در تولید صدا: " + safeMessage(e));
                    ttsStatus.setTextColor(Color.rgb(170, 30, 30));
                    Toast.makeText(MainActivity.this, "خطا در تولید گفتار فارسی.", Toast.LENGTH_LONG).show();
                });
            } finally {
                synthesizing = false;
            }
        }, "sayeh-fa-tts-generate").start();
    }

    private void playGeneratedAudio() {
        try {
            if (generatedAudioFile == null || !generatedAudioFile.exists()) return;
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.release();
            }
            mediaPlayer = MediaPlayer.create(this, Uri.fromFile(generatedAudioFile));
            if (mediaPlayer == null) throw new IllegalStateException("پخش‌کننده ایجاد نشد");
            mediaPlayer.start();
        } catch (Throwable e) {
            Toast.makeText(this, "پخش صدا ناموفق بود: " + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private void chooseAudioDestination() {
        if (!ttsReady) {
            Toast.makeText(this, "موتور فارسی داخلی هنوز بارگذاری نشده است.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/wav");
        i.putExtra(Intent.EXTRA_TITLE, "sayeh-persian-voice.wav");
        startActivityForResult(i, REQ_SAVE_AUDIO);
    }

    private void copyGeneratedAudioToDestination() throws Exception {
        Uri destination = pendingAudioDestination;
        if (destination == null || generatedAudioFile == null || !generatedAudioFile.exists()) return;
        try (InputStream in = new FileInputStream(generatedAudioFile);
             OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
            if (out == null) throw new IllegalStateException("مسیر ذخیره باز نشد");
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            out.flush();
        }
        pendingAudioDestination = null;
        runOnUiThread(() -> Toast.makeText(MainActivity.this, "فایل صوتی WAV ذخیره شد.", Toast.LENGTH_LONG).show());
    }

    private void copyAssetTree(String assetPath, File destination) throws Exception {
        AssetManager assets = getAssets();
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (InputStream in = assets.open(assetPath); OutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            return;
        }

        if (!destination.exists() && !destination.mkdirs()) {
            throw new IllegalStateException("ساخت پوشهٔ داده ممکن نشد: " + destination.getName());
        }
        for (String child : children) {
            copyAssetTree(assetPath + "/" + child, new File(destination, child));
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private void pickMedia() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_MEDIA);
    }

    private void updateMediaStatus() {
        if (selectedMedia.isEmpty()) {
            mediaStatus.setText("هنوز رسانه‌ای انتخاب نشده است.");
            return;
        }
        StringBuilder b = new StringBuilder();
        b.append("رسانه‌های پروژه: ").append(selectedMedia.size()).append(" فایل\n");
        for (int x = 0; x < selectedMedia.size(); x++) {
            b.append(x + 1).append(". ").append(selectedMedia.get(x).getLastPathSegment()).append("\n");
        }
        mediaStatus.setText(b.toString());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_SAVE_AUDIO) {
            pendingAudioDestination = data.getData();
            if (pendingAudioDestination != null) synthesize(false);
            return;
        }

        if (requestCode == REQ_MEDIA) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int x = 0; x < clip.getItemCount(); x++) {
                    Uri uri = clip.getItemAt(x).getUri();
                    if (uri != null && !selectedMedia.contains(uri)) selectedMedia.add(uri);
                }
            } else if (data.getData() != null && !selectedMedia.contains(data.getData())) {
                selectedMedia.add(data.getData());
            }
            updateMediaStatus();
        }
    }

    @Override
    protected void onDestroy() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Throwable ignored) { }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (tts != null) {
            try { tts.release(); } catch (Throwable ignored) { }
            tts = null;
        }
        super.onDestroy();
    }

    private void section(LinearLayout root, String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(18);
        v.setTextColor(Color.rgb(20, 92, 62));
        v.setPadding(0, dp(20), 0, dp(8));
        v.setGravity(Gravity.RIGHT);
        root.addView(v);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.setMargins(0, dp(6), 0, 0);
        b.setLayoutParams(p);
        return b;
    }

    private String safeMessage(Throwable e) {
        String m = e == null ? null : e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
