package com.tracktimer.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Track timer: the timer UI is the same HTML page (assets/index.html).
 * All timer sounds (beeps + voice) go to the ALARM stream, so the phone's
 * volume keys and the music app only change music, not the timer.
 */
public class MainActivity extends Activity {

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    // second engine used only to record phrases to files, so they can be played back louder
    private TextToSpeech synth;
    private boolean synthReady = false;
    private final Set<String> pendingVoice = ConcurrentHashMap.newKeySet();
    private volatile AudioTrack voiceTrack;
    private static final float VOICE_GAIN = 2.0f;   // voice is played about 2x louder than plain TTS
    private float volume = 1f;          // 0..1, set from the page's volume slider
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService audioPool = Executors.newFixedThreadPool(3);

    private static final AudioAttributes ALARM_ATTR = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // crash report: if the app crashed last time, show the error once so it can be sent for fixing
        final File crash = new File(getFilesDir(), "crash.txt");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(crash))) {
                pw.println("Android " + Build.VERSION.RELEASE + " / " + Build.MODEL);
                ex.printStackTrace(pw);
            } catch (Exception ignored) { }
            if (prev != null) prev.uncaughtException(th, ex);
        });
        if (crash.exists()) {
            String msg = "";
            try (FileInputStream in = new FileInputStream(crash)) { byte[] b = new byte[(int) crash.length()]; int n = in.read(b); msg = new String(b, 0, Math.max(0, n), "UTF-8"); } catch (Exception ignored) { }
            crash.delete();
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setTextIsSelectable(true);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(12);
            tv.setPadding(40, 120, 40, 120);
            tv.setText("앱이 지난번에 오류로 꺼졌습니다. 이 화면을 캡처해서 보내주세요.\n앱을 다시 열면 정상 화면으로 시작합니다.\n\n" + msg);
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.addView(tv);
            setContentView(sv);
            return;
        }

        // keep the screen on and show over the lock screen while the timer is open
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        // hardware volume keys keep controlling media (music)
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        try {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                try {
                    tts.setLanguage(Locale.KOREAN);
                    tts.setAudioAttributes(ALARM_ATTR);
                    tts.setSpeechRate(1.1f);
                    ttsReady = true;
                } catch (Exception ignored) { }
            }
        });
        } catch (Exception ignored) { }

        try {
        synth = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            try {
            synth.setLanguage(Locale.KOREAN);
            synth.setSpeechRate(1.1f);
            synth.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) {
                    if (id == null || !id.startsWith("syn|")) return;
                    String text = id.substring(4);
                    File f = voiceFile(text);
                    File tmp = new File(f.getPath() + ".tmp");
                    if (tmp.length() > 44) tmp.renameTo(f);
                    pendingVoice.remove(text);
                }
                @Override public void onError(String id) {
                    if (id != null && id.startsWith("syn|")) pendingVoice.remove(id.substring(4));
                }
            });
            synthReady = true;
            // record the phrases used most often
            cacheVoice("시작");
            String[] counts = {"하나","둘","셋","넷","다섯","여섯","일곱","여덟","아홉","열"};
            for (String c : counts) cacheVoice(c);
            for (int i = 1; i <= 100; i++) cacheVoice(lapKo(i) + " 바퀴");
            } catch (Exception ignored) { }
        });
        } catch (Exception ignored) { }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setBackgroundColor(0xFF0a1429);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        // keep the page running at full priority when the screen is off or the app is in the background
        try { if (Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false); } catch (Exception ignored) { }
        // Android 13+: ask once so the "running" notification can be shown
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
        } catch (Exception ignored) { }
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    /** Methods callable from the page as window.AndroidBridge.xxx(...) */
    private class Bridge {
        @JavascriptInterface
        public void setVolume(int percent) {
            volume = Math.max(0f, Math.min(1f, percent / 100f));
        }

        @JavascriptInterface
        public void beep(double freq, int durMs, int delayMs) {
            final float vol = volume;
            if (vol <= 0f) return;
            main.postDelayed(() -> audioPool.execute(() -> playTone(freq, durMs, vol)), Math.max(0, delayMs));
        }

        @JavascriptInterface
        public void speak(String text) {
            if (text == null || text.isEmpty() || volume <= 0f) return;
            stopVoiceTrack();
            File f = voiceFile(text);
            if (f.exists()) {
                if (ttsReady) tts.stop();
                final float g = volume * VOICE_GAIN;
                audioPool.execute(() -> playWav(f, g));
                return;
            }
            // not recorded yet: speak normally this time, record it for next time
            if (ttsReady) {
                Bundle p = new Bundle();
                p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, "tt" + System.nanoTime());
            }
            cacheVoice(text);
        }

        @JavascriptInterface
        public void stopSpeak() {
            stopVoiceTrack();
            if (ttsReady) tts.stop();
        }

        @JavascriptInterface
        public void vibrate(String csv) {
            try {
                String[] parts = csv.split(",");
                long[] pattern = new long[parts.length + 1];
                pattern[0] = 0;
                for (int i = 0; i < parts.length; i++) pattern[i + 1] = Long.parseLong(parts[i].trim());
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                else v.vibrate(pattern, -1);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public void keepAlive(boolean on) {
            main.post(() -> {
                Intent svc = new Intent(MainActivity.this, TimerService.class);
                try {
                    if (on) {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                        else startService(svc);
                    } else {
                        stopService(svc);
                    }
                } catch (Exception ignored) { }
            });
        }
    }

    private File voiceFile(String text) {
        return new File(getCacheDir(), "v_" + Integer.toHexString(text.hashCode()) + "_" + text.length() + ".wav");
    }

    private void cacheVoice(String text) {
        if (!synthReady) return;
        File f = voiceFile(text);
        if (f.exists() || !pendingVoice.add(text)) return;
        File tmp = new File(f.getPath() + ".tmp");
        synth.synthesizeToFile(text, new Bundle(), tmp, "syn|" + text);
    }

    private static String lapKo(int n) {
        String[] ones = {"", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉"};
        String[] tens = {"", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔"};
        String[] hund = {"", "백", "이백", "삼백"};
        int h = Math.min(3, n / 100), r = n % 100;
        int t = r / 10, o = r % 10;
        return hund[h] + ((t == 2 && o == 0) ? "스무" : tens[t]) + ones[o];
    }

    private void stopVoiceTrack() {
        AudioTrack t = voiceTrack;
        voiceTrack = null;
        if (t != null) {
            try { t.pause(); t.flush(); } catch (Exception ignored) { }
        }
    }

    /** Play a recorded phrase on the alarm stream with extra gain and a soft limiter. */
    private void playWav(File f, float gain) {
        AudioTrack track = null;
        try {
            byte[] data = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, r;
                while (off < data.length && (r = in.read(data, off, data.length - off)) > 0) off += r;
            }
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int rate = 22050, channels = 1, bits = 16, dataOff = -1, dataLen = 0;
            int p = 12;
            while (p + 8 <= data.length) {
                String id = new String(data, p, 4, "US-ASCII");
                int len = bb.getInt(p + 4);
                if (id.equals("fmt ")) {
                    channels = bb.getShort(p + 10);
                    rate = bb.getInt(p + 12);
                    bits = bb.getShort(p + 22);
                } else if (id.equals("data")) {
                    dataOff = p + 8;
                    dataLen = Math.min(len < 0 ? data.length : len, data.length - dataOff);
                    break;
                }
                p += 8 + len + (len & 1);
            }
            if (dataOff < 0 || bits != 16 || dataLen <= 0) return;
            int n = dataLen / 2;
            short[] pcm = new short[n];
            for (int i = 0; i < n; i++) {
                double x = bb.getShort(dataOff + i * 2) / 32768.0 * gain;
                double y = Math.tanh(x);   // loud but no harsh clipping
                pcm[i] = (short) (y * 32767);
            }
            track = new AudioTrack.Builder()
                    .setAudioAttributes(ALARM_ATTR)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(n * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(pcm, 0, n);
            voiceTrack = track;
            track.play();
            long ms = (long) n * 1000L / ((long) rate * Math.max(1, channels));
            Thread.sleep(ms + 80);
        } catch (Exception ignored) {
        } finally {
            if (track != null) {
                if (voiceTrack == track) voiceTrack = null;
                try { track.stop(); } catch (Exception ignored) { }
                track.release();
            }
        }
    }

    /** Square-wave beep on the alarm stream, with short fades to avoid clicks. */
    private void playTone(double freq, int durMs, float vol) {
        int rate = 44100;
        int n = Math.max(1, rate * durMs / 1000);
        short[] buf = new short[n];
        int fade = Math.min(n / 4, rate / 200);
        double period = rate / freq;
        for (int i = 0; i < n; i++) {
            double env = 1.0;
            if (i < fade) env = i / (double) fade;
            else if (i > n - fade) env = (n - i) / (double) fade;
            double sq = ((i % period) < period / 2) ? 1.0 : -1.0;
            buf[i] = (short) (sq * env * 1.0 * vol * Short.MAX_VALUE); // full scale
        }
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(ALARM_ATTR)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(n * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(buf, 0, n);
            track.play();
            Thread.sleep(durMs + 60);
        } catch (Exception ignored) {
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) { }
                track.release();
            }
        }
    }

    @Override
    protected void onPause() {
        // do not pause the WebView: the timer keeps counting if the screen goes off
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.resumeTimers();
    }

    @Override
    public void onBackPressed() {
        // back button goes to the home screen instead of closing a running timer
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        stopService(new Intent(this, TimerService.class));
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (synth != null) { synth.stop(); synth.shutdown(); }
        audioPool.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
