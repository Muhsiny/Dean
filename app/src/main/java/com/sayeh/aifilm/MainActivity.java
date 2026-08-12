package com.sayeh.aifilm;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int REQ_MEDIA = 2001;
    private static final int REQ_SAVE_AUDIO = 2002;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private EditText script;
    private TextView ttsStatus;
    private TextView mediaStatus;
    private SeekBar speedBar;
    private SeekBar pitchBar;
    private Uri pendingAudioDestination;
    private File pendingAudioFile;
    private final List<Uri> selectedMedia = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(15, 48, 36));
        buildUi();
        tts = new TextToSpeech(this, this);
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
        sub.setText("هستهٔ بومی اندروید — گفتار فارسی، پروژه و رسانه");
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
        script.setText("در دل کوهستان‌های افغانستان، تاریخ همیشه با صدای جنگ روایت نشده است؛ گاهی صدای یک اندیشه، یک تصمیم و یک رهبر، از صدای سلاح ماندگارتر بوده است. آیت‌الله سیدعلی بهشتی یکی از چهره‌های مهم سیاسی و اجتماعی هزاره‌جات بود. این متن برای آزمایش گویندگی طبیعی و روان فارسی در استودیو سایه خوانده می‌شود.");
        script.setTextSize(17);
        script.setPadding(dp(12), dp(12), dp(12), dp(12));
        script.setBackgroundColor(Color.WHITE);
        root.addView(script, new LinearLayout.LayoutParams(-1, dp(210)));

        ttsStatus = new TextView(this);
        ttsStatus.setText("در حال آماده‌سازی موتور گفتار فارسی…");
        ttsStatus.setTextColor(Color.rgb(90, 90, 90));
        ttsStatus.setPadding(0, dp(10), 0, dp(8));
        root.addView(ttsStatus);

        TextView speedLabel = new TextView(this);
        speedLabel.setText("سرعت گفتار");
        root.addView(speedLabel);
        speedBar = new SeekBar(this);
        speedBar.setMax(100);
        speedBar.setProgress(40);
        root.addView(speedBar);

        TextView pitchLabel = new TextView(this);
        pitchLabel.setText("بم/زیر بودن صدا");
        pitchLabel.setPadding(0, dp(6), 0, 0);
        root.addView(pitchLabel);
        pitchBar = new SeekBar(this);
        pitchBar.setMax(100);
        pitchBar.setProgress(35);
        root.addView(pitchBar);

        Button speak = button("▶ پخش آزمایشی صدا");
        speak.setOnClickListener(v -> speakNow());
        root.addView(speak);

        Button save = button("ذخیرهٔ صدا به فایل");
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

        section(root, "وضعیت");
        TextView note = new TextView(this);
        note.setText("این نسخه، هستهٔ واقعی و قابل‌نصب اندروید است. تولید و ذخیرهٔ گفتار روی خود دستگاه انجام می‌شود و رسانه‌ها از حافظهٔ تلفن مستقیماً وارد پروژه می‌شوند.");
        note.setTextSize(14);
        note.setTextColor(Color.DKGRAY);
        note.setGravity(Gravity.RIGHT);
        root.addView(note);

        setContentView(scroll);
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

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            ttsStatus.setText("موتور گفتار دستگاه راه‌اندازی نشد.");
            return;
        }

        Locale faIR = new Locale("fa", "IR");
        int lang = tts.setLanguage(faIR);
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Locale fa = new Locale("fa");
            lang = tts.setLanguage(fa);
        }

        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            ttsStatus.setText("صدای فارسی در موتور گفتار این تلفن نصب نیست.");
            return;
        }

        Voice best = choosePersianVoice(tts.getVoices());
        if (best != null) {
            tts.setVoice(best);
            ttsStatus.setText("آماده | صدای فارسی: " + best.getName() + (best.isNetworkConnectionRequired() ? " (آنلاین)" : " (آفلاین)"));
        } else {
            ttsStatus.setText("آماده | صدای فارسی پیش‌فرض دستگاه");
        }
        ttsReady = true;

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }
            @Override public void onError(String utteranceId) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "خطا در تولید صدا", Toast.LENGTH_LONG).show());
            }
            @Override public void onDone(String utteranceId) {
                if ("save_audio".equals(utteranceId)) copyFinishedAudio();
            }
        });
    }

    private Voice choosePersianVoice(Set<Voice> voices) {
        if (voices == null) return null;
        return voices.stream()
                .filter(v -> v.getLocale() != null && "fa".equalsIgnoreCase(v.getLocale().getLanguage()))
                .sorted(Comparator
                        .comparing((Voice v) -> v.isNetworkConnectionRequired())
                        .thenComparing(Voice::getQuality, Comparator.reverseOrder()))
                .findFirst().orElse(null);
    }

    private void applyVoiceSettings() {
        float speed = 0.65f + (speedBar.getProgress() / 100f) * 0.95f;
        float pitch = 0.70f + (pitchBar.getProgress() / 100f) * 0.65f;
        tts.setSpeechRate(speed);
        tts.setPitch(pitch);
    }

    private void speakNow() {
        String text = script.getText().toString().trim();
        if (!ttsReady) {
            Toast.makeText(this, "موتور فارسی هنوز آماده نیست.", Toast.LENGTH_LONG).show();
            return;
        }
        if (text.isEmpty()) {
            Toast.makeText(this, "متن را وارد کن.", Toast.LENGTH_SHORT).show();
            return;
        }
        applyVoiceSettings();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), "preview");
    }

    private void chooseAudioDestination() {
        if (!ttsReady) {
            Toast.makeText(this, "موتور فارسی هنوز آماده نیست.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/wav");
        i.putExtra(Intent.EXTRA_TITLE, "sayeh-persian-voice.wav");
        startActivityForResult(i, REQ_SAVE_AUDIO);
    }

    private void synthesizeAudio() {
        try {
            String text = script.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "متن خالی است.", Toast.LENGTH_SHORT).show();
                return;
            }
            applyVoiceSettings();
            pendingAudioFile = new File(getCacheDir(), "sayeh_tts_output.wav");
            if (pendingAudioFile.exists()) pendingAudioFile.delete();
            int result = tts.synthesizeToFile(text, new Bundle(), pendingAudioFile, "save_audio");
            if (result == TextToSpeech.SUCCESS) {
                Toast.makeText(this, "در حال ساخت فایل صوتی…", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "شروع تولید صدا ناموفق بود.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "خطا: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void copyFinishedAudio() {
        Uri destination = pendingAudioDestination;
        File source = pendingAudioFile;
        if (destination == null || source == null || !source.exists()) return;
        try (InputStream in = new FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            out.flush();
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "فایل صوتی ذخیره شد.", Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "ذخیره فایل ناموفق بود: " + e.getMessage(), Toast.LENGTH_LONG).show());
        } finally {
            pendingAudioDestination = null;
            if (source.exists()) source.delete();
        }
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
            if (pendingAudioDestination != null) synthesizeAudio();
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
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
