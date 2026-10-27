package com.example.callhelper;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    public static final String EXTRA_AUTOSTART = "autostart";
    public static final String PREFS = "callhelper";
    public static final String PREF_WANT_RUNNING = "want_running";
    public static final String PREF_ANNOUNCE = "announce_caller";
    public static final String PREF_SENS = "sensitivity";
    private static final int REQ_MAIN = 100;
    private static final int REQ_ANNOUNCE = 200;

    private static class Row {
        String emoji;
        TextView chip;
        TextView sub;
        Button btn;
    }

    private TextView circle, statusTitle, statusSub, progressText, announceSub;
    private final View[] segs = new View[3];
    private Row rowPerm, rowVoice, rowA11y;
    private Switch announceSwitch;
    private boolean updatingUi = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        Ui.systemBars(this);
        setContentView(buildUi());
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_ANNOUNCE) {
            boolean ok = results.length > 0;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            setAnnouncePref(ok);
            if (!ok) Toast.makeText(this, "الميزة تحتاج إذن جهات الاتصال وسجل المكالمات", Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_AUTOSTART, false)) {
            intent.removeExtra(EXTRA_AUTOSTART);
            if (!VoiceCommandService.isRunning()) startSvc();
        }
    }

    // ---------------- UI ----------------

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int p = Ui.dp(this, 18);
        root.setPadding(p, Ui.dp(this, 22), p, p);

        // الرأس: الأيقونة + الاسم
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher_art);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setBackground(Ui.rounded(this, Ui.PRIMARY, 14));
        icon.setClipToOutline(true);
        header.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, "CallHelper", 24, Ui.TEXT, true));
        titles.addView(Ui.text(this, "ردّ على مكالماتك بصوتك", 13, Ui.MUTED, false));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        tp.setMarginStart(Ui.dp(this, 12));
        header.addView(titles, tp);
        root.addView(header, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 18));

        // بطاقة الحالة (الدائرة)
        LinearLayout hero = Ui.card(this);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(Ui.dp(this, 16), Ui.dp(this, 26), Ui.dp(this, 16), Ui.dp(this, 22));
        circle = new TextView(this);
        circle.setText("📞");
        circle.setTextSize(52);
        circle.setGravity(Gravity.CENTER);
        circle.setElevation(Ui.dp(this, 6));
        circle.setOnClickListener(v -> toggleSvc());
        hero.addView(circle, new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 150)));
        statusTitle = Ui.text(this, "", 22, Ui.TEXT, true);
        statusTitle.setGravity(Gravity.CENTER);
        hero.addView(statusTitle, Ui.lp(this, Ui.WRAP, Ui.WRAP, 18, 0));
        statusSub = Ui.text(this, "", 14, Ui.MUTED, false);
        statusSub.setGravity(Gravity.CENTER);
        hero.addView(statusSub, Ui.lp(this, Ui.WRAP, Ui.WRAP, 4, 0));
        root.addView(hero);

        // بطاقة الإعداد
        LinearLayout setup = Ui.card(this);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "الإعداد", 18, Ui.TEXT, true), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        progressText = Ui.text(this, "0/3", 14, Ui.MUTED, true);
        head.addView(progressText);
        setup.addView(head);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            View s = new View(this);
            s.setBackground(Ui.rounded(this, Ui.LINE, 4));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, Ui.dp(this, 6), 1f);
            sp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            bar.addView(s, sp);
            segs[i] = s;
        }
        setup.addView(bar, Ui.lp(this, Ui.MATCH, Ui.WRAP, 10, 6));
        rowPerm = makeRow(setup, "🔐", "الأذونات", v -> requestAll());
        setup.addView(Ui.divider(this));
        rowVoice = makeRow(setup, "🎙️", "بصمة صوتك", v -> startActivity(new Intent(this, VoiceTrainerActivity.class)));
        setup.addView(Ui.divider(this));
        rowA11y = makeRow(setup, "👆", "أزرار الصوت (Accessibility)",
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(setup);

        // بطاقة الميزات
        LinearLayout feat = Ui.card(this);
        feat.addView(Ui.text(this, "الميزات", 18, Ui.TEXT, true));
        Row rowTest = makeRow(feat, "🎚️", "اختبار البصمة والأزرار",
                v -> startActivity(new Intent(this, VoiceTestActivity.class)));
        rowTest.sub.setText("اضبط الحساسية وافحص وصول أزرار الصوت");
        rowTest.btn.setText("فتح");
        feat.addView(Ui.divider(this));
        LinearLayout frow = new LinearLayout(this);
        frow.setOrientation(LinearLayout.HORIZONTAL);
        frow.setGravity(Gravity.CENTER_VERTICAL);
        frow.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        frow.addView(Ui.chip(this, "🗣️", Ui.PRIMARY));
        LinearLayout ftexts = new LinearLayout(this);
        ftexts.setOrientation(LinearLayout.VERTICAL);
        ftexts.addView(Ui.text(this, "قراءة اسم المتصل", 16, Ui.TEXT, true));
        announceSub = Ui.text(this, "", 13, Ui.MUTED, false);
        ftexts.addView(announceSub);
        LinearLayout.LayoutParams ftp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        ftp.setMarginStart(Ui.dp(this, 12));
        ftp.setMarginEnd(Ui.dp(this, 12));
        frow.addView(ftexts, ftp);
        announceSwitch = new Switch(this);
        announceSwitch.setOnCheckedChangeListener((btn, checked) -> onAnnounceToggled(checked));
        frow.addView(announceSwitch);
        feat.addView(frow);
        root.addView(feat);

        // بطاقة الاختصارات
        LinearLayout tips = Ui.card(this);
        tips.addView(Ui.text(this, "اختصارات أزرار الصوت", 18, Ui.TEXT, true));
        TextView t = Ui.text(this,
                "🔊 رفع الصوت أثناء الرنين ← ردّ\n"
                + "🔊 رفع الصوت أثناء المكالمة ← تبديل السماعة\n"
                + "🔉 خفض الصوت أثناء المكالمة ← إنهاء المكالمة\n"
                + "🔉 خفض الصوت أثناء الرنين ← كتم الرنين (سلوك النظام)",
                14, Ui.MUTED, false);
        t.setLineSpacing(Ui.dp(this, 4), 1f);
        tips.addView(t, Ui.lp(this, Ui.WRAP, Ui.WRAP, 8, 0));
        root.addView(tips);

        TextView foot = Ui.text(this, "CallHelper • الإصدار 7", 12, Ui.MUTED, false);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, Ui.lp(this, Ui.MATCH, Ui.WRAP, 4, 8));

        sv.addView(root);
        return sv;
    }

    private Row makeRow(LinearLayout parent, String emoji, String title, View.OnClickListener click) {
        Row r = new Row();
        r.emoji = emoji;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));

        r.chip = Ui.chip(this, emoji, Ui.PRIMARY);
        row.addView(r.chip);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, title, 16, Ui.TEXT, true));
        r.sub = Ui.text(this, "", 13, Ui.MUTED, false);
        texts.addView(r.sub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        tp.setMarginStart(Ui.dp(this, 12));
        tp.setMarginEnd(Ui.dp(this, 12));
        row.addView(texts, tp);

        r.btn = Ui.button(this, "", Ui.PRIMARY, android.graphics.Color.WHITE);
        r.btn.setTextSize(14);
        r.btn.setMinHeight(0);
        r.btn.setMinimumHeight(0);
        r.btn.setMinWidth(0);
        r.btn.setMinimumWidth(0);
        r.btn.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        r.btn.setOnClickListener(click);
        row.addView(r.btn, new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40)));

        parent.addView(row);
        return r;
    }

    private void setRow(Row r, boolean ok, String sub, String btnText) {
        r.chip.setText(ok ? "✅" : r.emoji);
        r.chip.setBackground(Ui.rounded(this, Ui.alpha(ok ? Ui.GREEN : Ui.PRIMARY, 40), 12));
        r.sub.setText(sub);
        if (btnText == null) {
            r.btn.setVisibility(View.GONE);
        } else {
            r.btn.setVisibility(View.VISIBLE);
            r.btn.setText(btnText);
        }
    }

    private void refresh() {
        boolean perms = hasAllPerms();
        int samples = countSamples();
        boolean a11yOn = a11yEnabled();
        boolean a11y = a11yOn && InCallAccessibilityService.isConnected();
        boolean running = VoiceCommandService.isRunning();
        boolean voiceOk = samples >= 2;

        setRow(rowPerm, perms,
                perms ? "كل الأذونات ممنوحة" : "مطلوبة: الميكروفون والهاتف والمكالمات",
                perms ? null : "منح");
        setRow(rowVoice, voiceOk,
                samples + " من 3 عينات" + (voiceOk ? "" : " (تحتاج عينتين على الأقل)"),
                samples >= 3 ? "إعادة" : "تسجيل");
        setRow(rowA11y, a11y,
                a11y ? "مفعّلة ومتصلة"
                     : (a11yOn ? "مفعّلة لكنها غير متصلة: أطفئها ثم شغّلها من جديد"
                               : "غير مفعّلة (أندرويد 13+: اسمح بـ«الإعدادات المقيّدة» من معلومات التطبيق أولاً)"),
                a11y ? null : (a11yOn ? "إصلاح" : "تفعيل"));

        int done = (perms ? 1 : 0) + (voiceOk ? 1 : 0) + (a11y ? 1 : 0);
        progressText.setText(done + "/3");
        for (int i = 0; i < 3; i++) segs[i].setBackground(Ui.rounded(this, done > i ? Ui.GREEN : Ui.LINE, 4));

        if (running) {
            circle.setBackground(Ui.circle(this, Ui.alpha(Ui.GREEN, 70), Ui.GREEN, 6));
            statusTitle.setText("الخدمة شغّالة");
            statusTitle.setTextColor(Ui.GREEN);
            statusSub.setText("جاهز لاستقبال المكالمات • اضغط الدائرة للإيقاف");
        } else {
            circle.setBackground(Ui.circle(this, Ui.alpha(Ui.MUTED, 40), Ui.alpha(Ui.MUTED, 110), 6));
            statusTitle.setText("الخدمة متوقفة");
            statusTitle.setTextColor(Ui.TEXT);
            statusSub.setText(perms && voiceOk
                    ? "اضغط الدائرة لتشغيل الرد الصوتي"
                    : "أكمل الإعداد في الأسفل ثم اضغط الدائرة");
        }

        boolean contactsOk = has(Manifest.permission.READ_CONTACTS) && has(Manifest.permission.READ_CALL_LOG);
        boolean on = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ANNOUNCE, false) && contactsOk;
        updatingUi = true;
        announceSwitch.setChecked(on);
        updatingUi = false;
        announceSub.setText(contactsOk ? "ينطق اسم المتصل عند الرنين"
                : "ينطق اسم المتصل (يطلب إذن جهات الاتصال وسجل المكالمات)");
    }

    // ---------------- Logic ----------------

    private void onAnnounceToggled(boolean checked) {
        if (updatingUi) return;
        if (!checked) { setAnnouncePref(false); return; }
        if (has(Manifest.permission.READ_CONTACTS) && has(Manifest.permission.READ_CALL_LOG)) {
            setAnnouncePref(true);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG}, REQ_ANNOUNCE);
        }
    }

    private void setAnnouncePref(boolean v) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ANNOUNCE, v).apply();
    }

    private void toggleSvc() {
        if (VoiceCommandService.isRunning()) stopSvc(); else startSvc();
    }

    private void startSvc() {
        if (!hasAllPerms()) {
            Toast.makeText(this, "امنح الأذونات أولاً", Toast.LENGTH_LONG).show();
            requestAll();
            return;
        }
        try {
            startForegroundService(new Intent(this, VoiceCommandService.class));
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_WANT_RUNNING, true).apply();
        } catch (Exception e) {
            Toast.makeText(this, "تعذر تشغيل الخدمة: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        circle.postDelayed(this::refresh, 800);
    }

    private void stopSvc() {
        stopService(new Intent(this, VoiceCommandService.class));
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_WANT_RUNNING, false).apply();
        circle.postDelayed(this::refresh, 500);
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAllPerms() {
        return has(Manifest.permission.RECORD_AUDIO)
                && has(Manifest.permission.READ_PHONE_STATE)
                && has(Manifest.permission.ANSWER_PHONE_CALLS);
    }

    private int countSamples() {
        int c = 0;
        for (int i = 1; i <= 3; i++) if (new File(getFilesDir(), "template_" + i + ".mfcc").exists()) c++;
        return c;
    }

    private boolean a11yEnabled() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            ComponentName cn = new ComponentName(this, InCallAccessibilityService.class);
            String full = cn.flattenToString();
            String shortName = cn.flattenToShortString();
            for (String part : s.split(":")) {
                if (part.equalsIgnoreCase(full) || part.equalsIgnoreCase(shortName)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void requestAll() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.READ_PHONE_STATE);
        perms.add(Manifest.permission.ANSWER_PHONE_CALLS);
        if (Build.VERSION.SDK_INT >= 33) perms.add("android.permission.POST_NOTIFICATIONS");
        requestPermissions(perms.toArray(new String[0]), REQ_MAIN);
    }
}
