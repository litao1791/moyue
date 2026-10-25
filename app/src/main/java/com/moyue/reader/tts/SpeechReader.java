package com.moyue.reader.tts;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import com.moyue.reader.text.TextRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 系统 TTS 的薄封装：把一章正文切成句子，一句一句念，边念边回报进度。
 *
 * 不一次性把整章塞进 TTS 队列——队列太长有些引擎会截断，
 * 而且拿不到"念到第几句"的回调，没法做自动翻页。
 */
public final class SpeechReader implements TextToSpeech.OnInitListener {

    public interface Listener {
        /** 引擎是否可用（缺中文语音包时 ok=false） */
        void onReady(boolean ok, String message);

        /** 开始念第 index 句，charOffset 是这句在整章里的起始位置，charLen 是长度 */
        void onSegment(int index, int total, int charOffset, int charLen);

        /** 本章念完 */
        void onDone();

        void onError(String message);
    }

    /** 单句最长字数，太长的句子拆开，避免一口气念不完 */
    private static final int MAX_SEGMENT = TextRange.DEFAULT_MAX_SEGMENT;

    private TextToSpeech tts;
    private boolean ready = false;
    private boolean released = false;

    /** 切句结果与它对应的原文（偏移量是相对 source 的） */
    private final List<TextRange.Seg> segs = new ArrayList<>();
    private String source = "";
    private int index = 0;
    private boolean speaking = false;

    private float rate = 1.0f;
    private float pitch = 1.0f;
    /** 引擎里装的所有中文语音包（有的设备有好几个，音色确实不一样） */
    private final List<Voice> chineseVoices = new ArrayList<>();
    private String voiceName = "";
    private Listener listener;

    public void init(Context ctx) {
        if (tts != null) return;
        tts = new TextToSpeech(ctx.getApplicationContext(), this);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isSpeaking() {
        return speaking;
    }

    @Override
    public void onInit(int status) {
        if (released) return;
        if (status != TextToSpeech.SUCCESS) {
            ready = false;
            if (listener != null) listener.onReady(false, "朗读引擎初始化失败");
            return;
        }
        int r = tts.setLanguage(Locale.CHINA);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            // 退回默认语言，至少别崩
            tts.setLanguage(Locale.getDefault());
            ready = false;
            if (listener != null) listener.onReady(false, "系统缺少中文语音包，请先在系统设置里安装");
            return;
        }
        tts.setSpeechRate(rate);
        tts.setPitch(pitch);
        collectVoices();
        applyVoice();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                // 进度在 speakNext 里已经回报过了
            }

            @Override
            public void onDone(String utteranceId) {
                if (released || !speaking) return;
                index++;
                speakNext();
            }

            @Override
            public void onError(String utteranceId) {
                if (released) return;
                speaking = false;
                if (listener != null) listener.onError("朗读出错，试试换个语音引擎");
            }
        });
        ready = true;
        if (listener != null) listener.onReady(true, null);
    }

    public void setRate(float r) {
        rate = Math.max(0.5f, Math.min(2.0f, r));
        if (tts != null && ready) tts.setSpeechRate(rate);
    }

    public float getRate() {
        return rate;
    }

    /** 音调，用来区分朗读角色（女声/男声/低沉男…） */
    public void setPitch(float p) {
        pitch = VoiceStyle.clampPitch(p);
        if (tts != null && ready) tts.setPitch(pitch);
    }

    public float getPitch() {
        return pitch;
    }

    /** 引擎里的中文语音包名字；只有一个或零个时，换音色只能靠音调 */
    public List<String> getVoiceNames() {
        List<String> names = new ArrayList<>();
        for (Voice v : chineseVoices) names.add(v.getName());
        return names;
    }

    public String getVoiceName() {
        return voiceName;
    }

    /** 指定语音包（传空串表示用引擎默认）；名字不存在就忽略 */
    public void setVoiceName(String name) {
        voiceName = name == null ? "" : name;
        if (tts != null && ready) applyVoice();
    }

    private void collectVoices() {
        chineseVoices.clear();
        try {
            Set<Voice> all = tts.getVoices();
            if (all == null) return;
            for (Voice v : all) {
                if (v == null || v.getLocale() == null) continue;
                if ("zh".equals(v.getLocale().getLanguage())) chineseVoices.add(v);
            }
            // 名字排序，保证每次进来顺序一致
            Collections.sort(chineseVoices, new Comparator<Voice>() {
                @Override
                public int compare(Voice a, Voice b) {
                    return a.getName().compareTo(b.getName());
                }
            });
        } catch (Throwable ignored) {
            // 有些引擎不支持枚举，当作只有一个语音包
        }
    }

    private void applyVoice() {
        if (tts == null || voiceName.isEmpty()) return;
        for (Voice v : chineseVoices) {
            if (voiceName.equals(v.getName())) {
                try {
                    tts.setVoice(v);
                } catch (Throwable ignored) {
                }
                return;
            }
        }
    }

    /** 从 fromOffset 开始念（按句对齐：从包含该位置的那一句开头起念） */
    public void speak(String text, int fromOffset) {
        if (tts == null || !ready) {
            if (listener != null) listener.onReady(false, "朗读还没准备好");
            return;
        }
        source = text == null ? "" : text;
        segs.clear();
        segs.addAll(TextRange.split(source, MAX_SEGMENT));
        if (segs.isEmpty()) return;

        int i = TextRange.indexAt(segs, fromOffset);
        if (i < 0) return;
        index = i;
        speaking = true;
        speakNext();
    }

    public void pause() {
        speaking = false;
        if (tts != null) tts.stop();
    }

    public void resume() {
        if (segs.isEmpty()) return;
        if (index >= segs.size()) index = segs.size() - 1;
        speaking = true;
        speakNext();
    }

    public void stop() {
        speaking = false;
        index = 0;
        segs.clear();
        source = "";
        if (tts != null) tts.stop();
    }

    public void release() {
        released = true;
        speaking = false;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        ready = false;
    }

    // ---------- 内部 ----------

    private void speakNext() {
        if (!speaking || tts == null) return;
        if (index >= segs.size()) {
            speaking = false;
            if (listener != null) listener.onDone();
            return;
        }
        TextRange.Seg seg = segs.get(index);
        String body = source.substring(seg.start, seg.end);
        if (listener != null) {
            listener.onSegment(index, segs.size(), seg.start, seg.length());
        }

        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
        tts.speak(body, TextToSpeech.QUEUE_FLUSH, params, "moyue-" + index);
    }
}
