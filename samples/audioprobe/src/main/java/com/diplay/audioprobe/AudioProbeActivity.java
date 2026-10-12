package com.diplay.audioprobe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Plays a tone on each Android audio stream type and usage, and shows live stream volumes.
 * Rotate the head unit's volume knob while a tone plays to see which stream it controls.
 */
public class AudioProbeActivity extends Activity {

    private static final int SAMPLE_RATE = 48000;

    // Legacy stream types (android.media.AudioManager).
    private static final int[] STREAMS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final String[] STREAM_NAMES = {
            "0  VOICE_CALL", "1  SYSTEM", "2  RING", "3  MUSIC", "4  ALARM",
            "5  NOTIFICATION", "6  BLUETOOTH_SCO", "7  SYSTEM_ENFORCED", "8  DTMF",
            "9  TTS", "10 ACCESSIBILITY", "11 ASSISTANT",
    };

    // Usages (android.media.AudioAttributes).
    private static final int[] USAGES = {1, 2, 4, 5, 12, 13, 16};
    private static final String[] USAGE_NAMES = {
            "USAGE_MEDIA (1)", "USAGE_VOICE_COMMUNICATION (2)", "USAGE_ALARM (4)",
            "USAGE_NOTIFICATION (5)", "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE (12)",
            "USAGE_ASSISTANCE_SONIFICATION (13)", "USAGE_ASSISTANT (16)",
    };

    private AudioManager audioManager;
    private TextView volumeView;
    private TextView statusView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile AudioTrack activeTrack;
    private Runnable volumeTick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Audio Probe\n测试每条音频通道能否发声");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        list.addView(title);

        TextView hint = new TextView(this);
        hint.setText("点一个通道 → 播放提示音（循环）。\n"
                + "播放时转车机音量旋钮，看下面哪条流的音量在变 → 就是车机把它路由到的地方。\n"
                + "再点一次同一个按钮 = 停止。");
        hint.setTextSize(12);
        hint.setPadding(0, dp(6), 0, dp(10));
        list.addView(hint);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setPadding(0, 0, 0, dp(8));
        list.addView(statusView);

        Button stop = new Button(this);
        stop.setText("停止所有声音");
        stop.setAllCaps(false);
        stop.setOnClickListener(v -> stopTone());
        list.addView(stop, matchWrap());

        // ---- 实时音量 ----
        TextView volTitle = new TextView(this);
        volTitle.setText("实时音量（转旋钮看哪条变）");
        volTitle.setTextSize(15);
        volTitle.setTypeface(Typeface.DEFAULT_BOLD);
        volTitle.setPadding(0, dp(14), 0, dp(4));
        list.addView(volTitle);

        volumeView = new TextView(this);
        volumeView.setTextSize(13);
        volumeView.setTypeface(Typeface.MONOSPACE);
        volumeView.setPadding(0, 0, 0, dp(8));
        list.addView(volumeView);

        // ---- 播放测试：legacy streams ----
        TextView sTitle = new TextView(this);
        sTitle.setText("按 stream type（0–11）播放");
        sTitle.setTextSize(15);
        sTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sTitle.setPadding(0, dp(10), 0, dp(4));
        list.addView(sTitle);
        for (int i = 0; i < STREAMS.length; i++) {
            addPlayButton(list, "stream  " + STREAM_NAMES[i], STREAMS[i], null);
        }

        // ---- 播放测试：usages ----
        TextView uTitle = new TextView(this);
        uTitle.setText("按 usage 播放");
        uTitle.setPadding(0, dp(14), 0, dp(4));
        uTitle.setTextSize(15);
        uTitle.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(uTitle);
        for (int i = 0; i < USAGES.length; i++) {
            addPlayButton(list, "usage  " + USAGE_NAMES[i], null, USAGES[i]);
        }

        scroll.addView(list);
        setContentView(scroll);
    }

    private void addPlayButton(LinearLayout list, String label, Integer stream, Integer usage) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(v -> {
            String key = keyOf(stream, usage);
            if (key.equals(activeKey)) {
                stopTone();
                statusView.setText("已停止");
            } else {
                playTone(stream, usage, key, label);
            }
        });
        list.addView(button, matchWrap());
    }

    private volatile String activeKey = "";

    private String keyOf(Integer stream, Integer usage) {
        return stream != null ? ("s" + stream) : ("u" + usage);
    }

    private void playTone(Integer stream, Integer usage, String key, String label) {
        stopTone();
        try {
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            AudioAttributes attrs;
            if (stream != null) {
                attrs = new AudioAttributes.Builder().setLegacyStreamType(stream).build();
                try {
                    audioManager.setStreamVolume(stream, audioManager.getStreamMaxVolume(stream), 0);
                } catch (Throwable ignore) {
                    // Volume change is best effort.
                }
            } else {
                attrs = new AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
            }
            int minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf <= 0 ? 4096 : minBuf, SAMPLE_RATE / 2);
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                statusView.setTextColor(Color.parseColor("#C62828"));
                statusView.setText("✗ 无法创建 AudioTrack：" + label);
                return;
            }
            track.play();
            activeTrack = track;
            activeKey = key;
            final short[] block = beepBlock();
            Thread thread = new Thread(() -> {
                try {
                    while (activeTrack == track) {
                        int written = track.write(block, 0, block.length);
                        if (written <= 0) break;
                    }
                } catch (Throwable ignore) {
                    // Stopped or released while writing.
                }
            }, "audioprobe-tone");
            thread.start();
            statusView.setTextColor(Color.parseColor("#1565C0"));
            statusView.setText("▶ 正在播放：" + label + "\n（转旋钮看下面哪条流的音量在变）");
        } catch (Throwable t) {
            activeKey = "";
            statusView.setTextColor(Color.parseColor("#C62828"));
            statusView.setText("✗ " + label + " 失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (" " + t.getMessage())));
        }
    }

    private void stopTone() {
        activeKey = "";
        AudioTrack track = activeTrack;
        activeTrack = null;
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
            } catch (Throwable ignore) {
                // Already stopped.
            }
            try {
                track.release();
            } catch (Throwable ignore) {
                // Already released.
            }
        }
    }

    /** A 660 Hz beep: 0.4 s tone + 0.2 s silence, looped. */
    private short[] beepBlock() {
        int on = (int) (SAMPLE_RATE * 0.4);
        int off = (int) (SAMPLE_RATE * 0.2);
        short[] out = new short[on + off];
        double freq = 660.0;
        int ramp = (int) (SAMPLE_RATE * 0.01);
        for (int i = 0; i < on; i++) {
            double env = Math.min(1.0, Math.min(i, on - i) / (double) Math.max(1, ramp));
            out[i] = (short) (Math.sin(2 * Math.PI * freq * i / SAMPLE_RATE) * 0.5 * 32767 * env);
        }
        return out;
    }

    @Override
    protected void onResume() {
        super.onResume();
        startVolumeMonitor();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (volumeTick != null) {
            handler.removeCallbacks(volumeTick);
        }
        stopTone();
    }

    private void startVolumeMonitor() {
        volumeTick = new Runnable() {
            @Override
            public void run() {
                volumeView.setText(volumeSnapshot());
                handler.postDelayed(this, 500);
            }
        };
        handler.post(volumeTick);
    }

    private String volumeSnapshot() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < STREAMS.length; i++) {
            int stream = STREAMS[i];
            try {
                int value = audioManager.getStreamVolume(stream);
                int max = audioManager.getStreamMaxVolume(stream);
                sb.append(String.format(java.util.Locale.US, "%-24s %2d / %d%n",
                        STREAM_NAMES[i], value, max));
            } catch (Throwable t) {
                sb.append(String.format("%-24s n/a%n", STREAM_NAMES[i]));
            }
        }
        return sb.toString().trim();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
