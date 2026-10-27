package com.example.callhelper;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class VoiceCommandService extends Service {
    public static final String ACTION_ANNOUNCE = "com.example.callhelper.ANNOUNCE";
    public static final String EXTRA_NUMBER = "number";

    private static final String TAG = "VoiceSvc";
    private static final String CH_ID = "callhelper";
    private static final int NOTIF_ID = 1;
    private static final float MIN_RMS = 900f;
    private static final int MIN_TEMPLATES = 2;

    private final Object lock = new Object();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile float[][][] templates = new float[0][][];
    private volatile float threshold = 0f;
    private volatile AudioRecord audioRecord;
    private volatile boolean capturing = false;
    private boolean foregroundOk = false;
    private static volatile boolean sRunning = false;
    public static boolean isRunning() { return sRunning; }

    // قراءة اسم المتصل
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile boolean speaking = false;
    private volatile boolean ringingNow = false;
    private volatile boolean announced = false;
    private String pendingSpeech = null;
    private final Runnable unknownFallback = () -> {
        if (ringingNow && !announced) announce(null);
    };

    private TelephonyManager telephonyManager;
    private PhoneStateListener legacyListener;
    private Object modernCallback;

    @TargetApi(31)
    private class CallCb extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override public void onCallStateChanged(int state) { handleCallState(state); }
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            startForegroundCompat(buildNotification());
            foregroundOk = true;
            sRunning = true;
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
            return;
        }
        loadTemplates();
        setupPhoneListener();
        if (announceEnabled()) ensureTts();
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NOTIF_ID, n);
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CH_ID, "CallHelper", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CH_ID)
                .setContentTitle("CallHelper شغال")
                .setContentText("جاهز لاستقبال المكالمات")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true)
                .build();
    }

    private void loadTemplates() {
        List<float[][]> list = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            File f = new File(getFilesDir(), "template_" + i + ".mfcc");
            if (!f.exists()) continue;
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
                Object o = ois.readObject();
                if (o instanceof float[][]) {
                    float[][] m = (float[][]) o;
                    if (m.length > 0) list.add(m);
                }
            } catch (Exception e) { Log.e(TAG, "load " + i, e); }
        }
        float[][][] arr = list.toArray(new float[0][][]);
        templates = arr;
        float base = DtwMatcher.baseScore(arr);
        float factor = DtwMatcher.factorFromSens(
                getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getInt(MainActivity.PREF_SENS, 30));
        threshold = base * factor;
        Log.d(TAG, "templates=" + arr.length + " base=" + base + " factor=" + factor + " threshold=" + threshold);
    }

    private void setupPhoneListener() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) return;
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE not granted");
            return;
        }
        telephonyManager = tm;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                CallCb cb = new CallCb();
                tm.registerTelephonyCallback(getMainExecutor(), cb);
                modernCallback = cb;
            } else {
                legacyListener = new PhoneStateListener() {
                    @Override public void onCallStateChanged(int state, String number) { handleCallState(state); }
                };
                tm.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE);
            }
        } catch (Exception e) { Log.e(TAG, "phone listener", e); }
    }

    private void handleCallState(int state) {
        CallController.setCallState(state);
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            if (!ringingNow) { ringingNow = true; announced = false; }
            loadTemplates(); // لالتقاط أي تسجيل جديد دون إعادة تشغيل الخدمة
            if (templates.length >= MIN_TEMPLATES) startCapture();
            if (!announced && announceEnabled()) {
                ensureTts();
                handler.removeCallbacks(unknownFallback);
                handler.postDelayed(unknownFallback, 2500);
            }
        } else {
            ringingNow = false;
            announced = false;
            pendingSpeech = null;
            handler.removeCallbacks(unknownFallback);
            if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
            speaking = false;
            stopCapture();
        }
    }

    // ---------------- قراءة اسم المتصل ----------------

    private boolean announceEnabled() {
        boolean on = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.PREF_ANNOUNCE, false);
        return on
                && checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    private void handleAnnounceIntent(Intent i) {
        if (!ringingNow) { ringingNow = true; announced = false; }
        announce(i.getStringExtra(EXTRA_NUMBER));
    }

    private void announce(final String number) {
        if (announced) return;
        if (!announceEnabled()) return;
        announced = true;
        handler.removeCallbacks(unknownFallback);
        new Thread(() -> {
            String text;
            if (number == null || number.trim().isEmpty()) {
                text = "اتصال من رقم مجهول";
            } else {
                String name = lookupName(number);
                text = (name != null) ? ("اتصال من " + name)
                        : ("اتصال من رقم غير محفوظ " + spaced(number));
            }
            final String say = text;
            handler.post(() -> { if (ringingNow) speak(say); });
        }, "announce").start();
    }

    private String lookupName(String number) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null;
        Cursor c = null;
        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            c = getContentResolver().query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {
            Log.w(TAG, "lookup", e);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private String spaced(String number) {
        String digits = number.replaceAll("[^0-9]", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    private void ensureTts() {
        if (tts != null) return;
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS || tts == null) {
                Log.w(TAG, "TTS init failed: " + status);
                return;
            }
            try {
                int r = tts.setLanguage(new Locale("ar"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Arabic TTS not available, using default voice");
                }
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { speaking = true; }
                    @Override public void onDone(String id) { speaking = false; }
                    @Override public void onError(String id) { speaking = false; }
                });
                ttsReady = true;
                if (pendingSpeech != null && ringingNow) {
                    String p = pendingSpeech;
                    pendingSpeech = null;
                    speakNow(p);
                }
            } catch (Exception e) { Log.e(TAG, "TTS setup", e); }
        });
    }

    private void speak(String text) {
        ensureTts();
        if (!ttsReady) { pendingSpeech = text; return; }
        speakNow(text);
    }

    private void speakNow(String text) {
        try { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "caller"); }
        catch (Exception e) { Log.e(TAG, "speak", e); }
    }

    // ---------------- الالتقاط الصوتي ----------------

    private void startCapture() {
        synchronized (lock) {
            if (capturing) return;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
            final int sr = MfccExtractor.SAMPLE_RATE;
            try {
                int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int bufBytes = Math.max(min, sr * 2 * 2);
                final AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
                if (ar.getState() != AudioRecord.STATE_INITIALIZED) { ar.release(); return; }
                audioRecord = ar;
                capturing = true;
                new Thread(() -> captureLoop(ar), "voice-capture").start();
            } catch (Exception e) {
                Log.e(TAG, "start", e);
                capturing = false;
                audioRecord = null;
            }
        }
    }

    private void captureLoop(AudioRecord ar) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        final int sr = MfccExtractor.SAMPLE_RATE;
        short[] chunk = new short[sr / 4];
        short[] window = new short[sr * 2];
        int filled = 0;
        int hits = 0;
        try {
            ar.startRecording();
            while (capturing && audioRecord == ar) {
                int r = ar.read(chunk, 0, chunk.length);
                if (r < 0) { Log.e(TAG, "read error " + r); break; }
                if (r == 0) continue;
                // أثناء نطق اسم المتصل نتجاهل الصوت حتى لا يلتقط الميكروفون صوت القارئ
                if (speaking) { filled = 0; continue; }
                System.arraycopy(window, r, window, 0, window.length - r);
                System.arraycopy(chunk, 0, window, window.length - r, r);
                filled = Math.min(window.length, filled + r);
                if (filled < window.length) continue;
                if (rms(window) < MIN_RMS) continue;

                float[][] test = MfccExtractor.extract(window);
                float thr = threshold;
                float dist = DtwMatcher.minScore(test, templates);
                Log.d(TAG, "score=" + dist + " thr=" + thr);
                hits = (dist < thr) ? hits + 1 : 0;
                if (hits >= 2) {
                    capturing = false;
                    vibrate(120);
                    CallController.answerCall(VoiceCommandService.this);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "loop", e);
        } finally {
            try { ar.stop(); } catch (Exception ignored) {}
            try { ar.release(); } catch (Exception ignored) {}
            synchronized (lock) {
                if (audioRecord == ar) { audioRecord = null; capturing = false; }
            }
        }
    }

    private void stopCapture() {
        AudioRecord ar;
        synchronized (lock) { capturing = false; ar = audioRecord; }
        if (ar != null) { try { ar.stop(); } catch (Exception ignored) {} }
    }

    private float rms(short[] s) {
        double sum = 0;
        for (short v : s) sum += (double) v * v;
        return (float) Math.sqrt(sum / s.length);
    }

    private void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent i, int f, int s) {
        if (!foregroundOk) { stopSelf(); return START_NOT_STICKY; }
        if (i != null && ACTION_ANNOUNCE.equals(i.getAction())) {
            handleAnnounceIntent(i);
            return START_STICKY;
        }
        loadTemplates();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        sRunning = false;
        handler.removeCallbacksAndMessages(null);
        stopCapture();
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
        }
        if (telephonyManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= 31 && modernCallback != null) {
                    telephonyManager.unregisterTelephonyCallback((TelephonyCallback) modernCallback);
                } else if (legacyListener != null) {
                    telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_NONE);
                }
            } catch (Exception e) { Log.w(TAG, "unregister", e); }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
