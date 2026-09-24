package com.engageflow.af;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String GRAPH = "https://graph.facebook.com/v26.0/";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private EditText token, pageId, igId, objectId, postUrl, incoming, draft;
    private EditText adAccountId, campaignName;
    private EditText goalFollowers, goalComments, goalReactions;
    private EditText simLike, simLove, simCare, simHaha, simWow, simSad, simAngry, simComments, simFollowers;
    private TextView status, metrics, progressText, simOutput;
    private ProgressBar pFollowers, pComments, pReactions;
    private int followers = 0, comments = 0, reactions = 0;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("engageflow", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.rgb(246, 247, 251));

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(40));
        root.setBackgroundColor(Color.rgb(246, 247, 251));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root);

        root.addView(label("EngageFlow AF", 28, true));
        TextView subtitle = label("اپ اندروید برای مدیریت و سنجش تعامل واقعی حساب‌های خودت", 14, false);
        subtitle.setTextColor(Color.DKGRAY);
        root.addView(subtitle);

        status = label("آماده", 13, true);
        status.setTextColor(Color.rgb(5, 110, 70));
        root.addView(card(status));

        section(root, "۱) اتصال حساب");
        token = field("Meta Access Token", true);
        pageId = field("Facebook Page ID", false);
        igId = field("Instagram Business User ID", false);
        token.setText(prefs.getString("token", ""));
        pageId.setText(prefs.getString("pageId", ""));
        igId.setText(prefs.getString("igId", ""));
        root.addView(token); root.addView(pageId); root.addView(igId);

        Button save = button("ذخیره اتصال روی همین تلفن");
        save.setOnClickListener(v -> {
            prefs.edit()
                    .putString("token", token.getText().toString().trim())
                    .putString("pageId", pageId.getText().toString().trim())
                    .putString("igId", igId.getText().toString().trim())
                    .apply();
            toast("ذخیره شد");
        });
        root.addView(save);

        section(root, "۲) آمار زنده");
        LinearLayout r1 = row();
        Button fbAccount = button("Facebook Page");
        Button igAccount = button("Instagram");
        r1.addView(fbAccount, weight()); r1.addView(igAccount, weight());
        root.addView(r1);

        objectId = field("Post / Media ID", false);
        root.addView(objectId);

        LinearLayout r2 = row();
        Button fbPost = button("پست Facebook");
        Button igPost = button("پست Instagram");
        r2.addView(fbPost, weight()); r2.addView(igPost, weight());
        root.addView(r2);

        metrics = label("برای دریافت آمار، اتصال و شناسه را وارد کن.", 14, false);
        metrics.setTextIsSelectable(true);
        root.addView(card(metrics));

        fbAccount.setOnClickListener(v -> fetchFacebookAccount());
        igAccount.setOnClickListener(v -> fetchInstagramAccount());
        fbPost.setOnClickListener(v -> fetchFacebookPost());
        igPost.setOnClickListener(v -> fetchInstagramPost());

        section(root, "۳) هدف عددی");
        goalFollowers = numberField("هدف فالوور");
        goalComments = numberField("هدف کامنت");
        goalReactions = numberField("هدف لایک / ری‌اکشن");
        goalFollowers.setText(prefs.getString("gf", "1000"));
        goalComments.setText(prefs.getString("gc", "100"));
        goalReactions.setText(prefs.getString("gr", "500"));
        root.addView(goalFollowers);
        pFollowers = progress(); root.addView(pFollowers);
        root.addView(goalComments);
        pComments = progress(); root.addView(pComments);
        root.addView(goalReactions);
        pReactions = progress(); root.addView(pReactions);
        progressText = label("", 13, false);
        root.addView(progressText);

        Button saveGoals = button("ثبت هدف‌ها");
        saveGoals.setOnClickListener(v -> {
            prefs.edit()
                    .putString("gf", goalFollowers.getText().toString())
                    .putString("gc", goalComments.getText().toString())
                    .putString("gr", goalReactions.getText().toString())
                    .apply();
            updateProgress();
            toast("هدف‌ها ثبت شد");
        });
        root.addView(saveGoals);

        section(root, "۴) Smart Reply دری / فارسی");
        incoming = field("کامنت مخاطب را اینجا بگذار", false);
        incoming.setMinLines(2);
        draft = field("پاسخ پیشنهادی", false);
        draft.setMinLines(3);
        root.addView(incoming); root.addView(draft);

        LinearLayout r3 = row();
        Button professional = button("حرفوی");
        Button friendly = button("صمیمی");
        r3.addView(professional, weight()); r3.addView(friendly, weight());
        root.addView(r3);
        professional.setOnClickListener(v -> draft.setText(makeReply(incoming.getText().toString(), false)));
        friendly.setOnClickListener(v -> draft.setText(makeReply(incoming.getText().toString(), true)));

        Button copy = button("کاپی پاسخ");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("EngageFlow reply", draft.getText()));
            toast("کاپی شد");
        });
        root.addView(copy);



        section(root, "۵) کمپاین واقعی Meta — قابل‌دیدن برای مردم");
        adAccountId = field("Meta Ad Account ID — فقط عدد، بدون act_", false);
        campaignName = field("نام کمپاین Engagement", false);
        adAccountId.setText(prefs.getString("adAccountId", ""));
        campaignName.setText("EngageFlow Public Engagement");
        root.addView(adAccountId);
        root.addView(campaignName);

        Button createCampaign = button("ساخت کمپاین واقعی Engagement — PAUSED");
        root.addView(createCampaign);
        createCampaign.setOnClickListener(v -> createPausedEngagementCampaign());

        Button openAdsManager = button("بازکردن Meta Ads Manager برای تکمیل و فعال‌سازی");
        root.addView(openAdsManager);
        openAdsManager.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://adsmanager.facebook.com/adsmanager/manage/campaigns")));
            } catch (Exception e) {
                showError("Ads Manager باز نشد: " + e.getMessage());
            }
        });

        TextView campaignNote = label("کمپاین با Objective = OUTCOME_ENGAGEMENT و حالت PAUSED ساخته می‌شود. پس از تکمیل Ad Set / Creative و فعال‌سازی در Ads Manager، تعامل واقعی روی همان Facebook/Instagram برای مردم قابل مشاهده است.", 12, false);
        campaignNote.setTextColor(Color.DKGRAY);
        root.addView(card(campaignNote));

        section(root, "۶) Engagement Lab — کنترل عددی");
        simLike = numberField("Like");
        simLove = numberField("Love");
        simCare = numberField("Care");
        simHaha = numberField("Haha");
        simWow = numberField("Wow");
        simSad = numberField("Sad");
        simAngry = numberField("Angry");
        simComments = numberField("تعداد کامنت");
        simFollowers = numberField("تعداد فالوور");

        simLike.setText("100");
        simLove.setText("50");
        simCare.setText("25");
        simHaha.setText("20");
        simWow.setText("10");
        simSad.setText("5");
        simAngry.setText("3");
        simComments.setText("30");
        simFollowers.setText("200");

        root.addView(simLike); root.addView(simLove); root.addView(simCare);
        root.addView(simHaha); root.addView(simWow); root.addView(simSad);
        root.addView(simAngry); root.addView(simComments); root.addView(simFollowers);

        Button simulate = button("اجرای سناریو");
        root.addView(simulate);
        simOutput = label("هنوز سناریویی اجرا نشده است.", 13, false);
        simOutput.setTextIsSelectable(true);
        root.addView(card(simOutput));
        simulate.setOnClickListener(v -> runSimulation());

        section(root, "۷) بازکردن مستقیم پست");
        postUrl = field("لینک پست Facebook یا Instagram", false);
        postUrl.setText(prefs.getString("postUrl", ""));
        root.addView(postUrl);

        LinearLayout r4 = row();
        Button openFb = button("بازکردن Facebook");
        Button openIg = button("بازکردن Instagram");
        r4.addView(openFb, weight()); r4.addView(openIg, weight());
        root.addView(r4);

        openFb.setOnClickListener(v -> openPost("https://www.facebook.com/"));
        openIg.setOnClickListener(v -> openPost("https://www.instagram.com/"));

        TextView footer = label("آمار از Graph API خوانده می‌شود و هر پاسخ یا خطای دسترسی عیناً در اپ نمایش داده می‌شود.", 12, false);
        footer.setTextColor(Color.GRAY);
        footer.setPadding(0, dp(22), 0, 0);
        root.addView(footer);

        setContentView(scroll);
        updateProgress();
    }



    private void createPausedEngagementCampaign() {
        final String access = token.getText().toString().trim();
        final String account = adAccountId.getText().toString().trim().replace("act_", "");
        final String name = campaignName.getText().toString().trim().isEmpty()
                ? "EngageFlow Public Engagement"
                : campaignName.getText().toString().trim();

        if (!required(access, "Meta Access Token") || !required(account, "Ad Account ID")) return;

        prefs.edit().putString("adAccountId", account).apply();
        setStatus("در حال ساخت کمپاین واقعی…", false);

        io.execute(() -> {
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("name", name);
                params.put("objective", "OUTCOME_ENGAGEMENT");
                params.put("status", "PAUSED");
                params.put("special_ad_categories", "[]");
                params.put("access_token", access);

                String body = postForm("act_" + account + "/campaigns", params);
                JSONObject o = new JSONObject(body);
                String id = o.optString("id", "");

                main.post(() -> {
                    setStatus("کمپاین واقعی ساخته شد", false);
                    metrics.setText(
                            "Meta Engagement Campaign ساخته شد\n" +
                            "Campaign ID: " + id + "\n" +
                            "Status: PAUSED\n" +
                            "Objective: OUTCOME_ENGAGEMENT\n\n" +
                            "برای شروع تحویل واقعی، Ad Set و Creative را در Ads Manager تکمیل و فعال کن."
                    );
                });
            } catch (Exception e) {
                main.post(() -> showError(e.getMessage()));
            }
        });
    }

    private String postForm(String path, Map<String, String> params) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }

        HttpURLConnection c = (HttpURLConnection) new URL(GRAPH + path).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = c.getResponseCode();
        String response = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 400) throw new Exception("Marketing API " + code + "\n" + response);
        return response;
    }

    private void runSimulation() {
        int like = number(simLike, 0);
        int love = number(simLove, 0);
        int care = number(simCare, 0);
        int haha = number(simHaha, 0);
        int wow = number(simWow, 0);
        int sad = number(simSad, 0);
        int angry = number(simAngry, 0);
        int cm = number(simComments, 0);
        int fl = number(simFollowers, 0);

        int total = like + love + care + haha + wow + sad + angry;
        String[] first = {"احمد","محمد","علی","حسین","رضا","فاطمه","زهرا","مریم","سیدجلال","نرگس","حمید","شکیب","فرشته","سمیرا","جاوید","عارف"};
        String[] last = {"احمدی","حسینی","محمدی","رضایی","هاشمی","موسوی","کریمی","عابدی","جعفری","نوری","رحیمی","اکبری","صادقی","بهشتی","کاظمی","محسنی"};

        StringBuilder names = new StringBuilder();
        int preview = Math.min(20, Math.max(cm, fl));
        for (int i = 0; i < preview; i++) {
            String n = first[i % first.length] + " " + last[(i * 3) % last.length];
            names.append("• ").append(n);
            if (i < Math.min(cm, preview)) {
                names.append(" — ").append(sampleComment(i));
            }
            names.append("\n");
        }

        simOutput.setText(
                "سناریو اجرا شد\n\n" +
                "Like: " + like + "\n" +
                "Love: " + love + "\n" +
                "Care: " + care + "\n" +
                "Haha: " + haha + "\n" +
                "Wow: " + wow + "\n" +
                "Sad: " + sad + "\n" +
                "Angry: " + angry + "\n" +
                "Total reactions: " + total + "\n" +
                "Comments: " + cm + "\n" +
                "Followers: " + fl + "\n\n" +
                "نمونه پروفایل‌ها/کامنت‌ها:\n" + names
        );
        setStatus("سناریوی آزمایشی اجرا شد", false);
    }

    private String sampleComment(int i) {
        String[] items = {
                "عالی بود، موفق باشید.",
                "تشکر از نشر این مطلب.",
                "معلومات بسیار مفید بود.",
                "موفقیت‌های بیشتر برایتان آرزو داریم.",
                "لطفاً در این مورد بیشتر بنویسید.",
                "بسیار خوب و قابل استفاده بود.",
                "سپاس از زحمات شما.",
                "این موضوع برای ما جالب بود."
        };
        return items[i % items.length];
    }

    private void fetchFacebookAccount() {
        String id = pageId.getText().toString().trim();
        if (!required(id, "Facebook Page ID")) return;
        graphGet(id, "name,followers_count,fan_count", body -> {
            JSONObject o = new JSONObject(body);
            followers = o.optInt("followers_count", o.optInt("fan_count", 0));
            metrics.setText("Facebook Page\nنام: " + o.optString("name", "—") +
                    "\nFollowers: " + followers +
                    "\nPage Likes: " + o.optInt("fan_count", 0));
            updateProgress();
        });
    }

    private void fetchInstagramAccount() {
        String id = igId.getText().toString().trim();
        if (!required(id, "Instagram Business User ID")) return;
        graphGet(id, "username,followers_count,media_count", body -> {
            JSONObject o = new JSONObject(body);
            followers = o.optInt("followers_count", 0);
            metrics.setText("Instagram Business\n@" + o.optString("username", "—") +
                    "\nFollowers: " + followers +
                    "\nMedia: " + o.optInt("media_count", 0));
            updateProgress();
        });
    }

    private void fetchFacebookPost() {
        String id = objectId.getText().toString().trim();
        if (!required(id, "Post ID")) return;
        graphGet(id, "message,reactions.limit(0).summary(true),comments.limit(0).summary(true)", body -> {
            JSONObject o = new JSONObject(body);
            reactions = summary(o.optJSONObject("reactions"));
            comments = summary(o.optJSONObject("comments"));
            metrics.setText("Facebook Post\nReactions: " + reactions +
                    "\nComments: " + comments +
                    "\n" + o.optString("message", ""));
            updateProgress();
        });
    }

    private void fetchInstagramPost() {
        String id = objectId.getText().toString().trim();
        if (!required(id, "Media ID")) return;
        graphGet(id, "caption,like_count,comments_count", body -> {
            JSONObject o = new JSONObject(body);
            reactions = o.optInt("like_count", 0);
            comments = o.optInt("comments_count", 0);
            metrics.setText("Instagram Media\nLikes: " + reactions +
                    "\nComments: " + comments +
                    "\n" + o.optString("caption", ""));
            updateProgress();
        });
    }

    private void graphGet(String path, String fields, JsonHandler handler) {
        final String access = token.getText().toString().trim();
        if (!required(access, "Access Token")) return;
        setStatus("در حال دریافت…", false);
        io.execute(() -> {
            try {
                String url = GRAPH + path + "?fields=" +
                        URLEncoder.encode(fields, "UTF-8") +
                        "&access_token=" + URLEncoder.encode(access, "UTF-8");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
                if (code >= 400) throw new Exception("Graph API " + code + "\n" + body);
                main.post(() -> {
                    try {
                        handler.handle(body);
                        setStatus("آمار زنده دریافت شد", false);
                    } catch (Exception e) {
                        showError(e.getMessage());
                    }
                });
            } catch (Exception e) {
                main.post(() -> showError(e.getMessage()));
            }
        });
    }

    private String read(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) out.append(line);
        return out.toString();
    }

    private int summary(JSONObject box) {
        if (box == null) return 0;
        JSONObject s = box.optJSONObject("summary");
        return s == null ? 0 : s.optInt("total_count", 0);
    }

    private void updateProgress() {
        int gf = number(goalFollowers, 1);
        int gc = number(goalComments, 1);
        int gr = number(goalReactions, 1);
        pFollowers.setProgress(percent(followers, gf));
        pComments.setProgress(percent(comments, gc));
        pReactions.setProgress(percent(reactions, gr));
        progressText.setText(
                "Followers: " + followers + " / " + gf +
                "\nComments: " + comments + " / " + gc +
                "\nReactions: " + reactions + " / " + gr);
    }

    private int number(EditText e, int fallback) {
        if (e == null) return fallback;
        try { return Math.max(1, Integer.parseInt(e.getText().toString().trim())); }
        catch (Exception ex) { return fallback; }
    }

    private int percent(int value, int goal) {
        return Math.max(0, Math.min(100, (int)Math.round(value * 100.0 / Math.max(1, goal))));
    }

    private String makeReply(String text, boolean friendly) {
        String s = text == null ? "" : text.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        String base;
        if (lower.contains("تشکر") || lower.contains("ممنون") || lower.contains("سپاس")) {
            base = "سپاس از لطف و همراهی شما.";
        } else if (s.contains("؟") || s.contains("?") || lower.contains("چرا") || lower.contains("چگونه")) {
            base = "سپاس از پرسش شما. موضوع را بررسی می‌کنیم و پاسخ دقیق را با شما شریک می‌سازیم.";
        } else if (lower.contains("مشکل") || lower.contains("خراب") || lower.contains("ناراضی")) {
            base = "سپاس که موضوع را مطرح کردید. لطفاً جزئیات بیشتر را بفرستید تا دقیق بررسی کنیم.";
        } else {
            base = "سپاس از دیدگاه شما.";
        }
        return friendly ? base + " 🌿" : base;
    }

    private void openPost(String fallback) {
        String url = postUrl.getText().toString().trim();
        if (url.isEmpty()) url = fallback;
        prefs.edit().putString("postUrl", url).apply();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            showError("لینک باز نشد: " + e.getMessage());
        }
    }

    private void showError(String message) {
        setStatus("خطا", true);
        metrics.setText(message == null ? "خطای نامشخص" : message);
    }

    private void setStatus(String text, boolean error) {
        status.setText(text);
        status.setTextColor(error ? Color.rgb(185, 28, 28) : Color.rgb(5, 110, 70));
    }

    private boolean required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            toast(name + " را وارد کن");
            return false;
        }
        return true;
    }

    private void section(LinearLayout root, String text) {
        TextView t = label(text, 18, true);
        t.setPadding(0, dp(18), 0, dp(8));
        root.addView(t);
    }

    private TextView label(String text, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(Color.rgb(17, 24, 39));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.START);
        t.setPadding(dp(4), dp(4), dp(4), dp(4));
        return t;
    }

    private EditText field(String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(14);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(8));
        e.setLayoutParams(lp);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private EditText numberField(String hint) {
        EditText e = field(hint, false);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinHeight(dp(48));
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        return p;
    }

    private View card(View child) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackgroundColor(Color.WHITE);
        box.addView(child);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, dp(8));
        box.setLayoutParams(lp);
        return box;
    }

    private ProgressBar progress() {
        ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(100);
        p.setProgress(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(10));
        lp.setMargins(0, 0, 0, dp(10));
        p.setLayoutParams(lp);
        return p;
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private interface JsonHandler { void handle(String body) throws Exception; }
}
