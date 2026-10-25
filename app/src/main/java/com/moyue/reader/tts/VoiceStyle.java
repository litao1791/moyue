package com.moyue.reader.tts;

/**
 * 朗读角色。
 *
 * 系统 TTS 没有"换音色"的接口，能调的只有音调和语速。
 * 所以这里的角色是一组音调预设——同一个语音引擎，用不同音高念出来，
 * 听感上就是不同的人在读。音调控制在 0.7–1.4 之间：
 * 再往外就会明显失真，变成"电子音"而不是"另一个人"。
 *
 * 语速不放进角色里。语速是用户自己的阅读习惯，换角色不该把它一起改掉。
 */
public final class VoiceStyle {

    /** 音调的安全区间，超出会明显失真 */
    public static final float MIN_PITCH = 0.5f;
    public static final float MAX_PITCH = 2.0f;

    public static final VoiceStyle[] ALL = {
            new VoiceStyle(0, "原声", 1.00f),
            new VoiceStyle(1, "女声", 1.22f),
            new VoiceStyle(2, "男声", 0.85f),
            new VoiceStyle(3, "低沉男", 0.72f),
            new VoiceStyle(4, "少年", 1.34f),
            new VoiceStyle(5, "老人", 0.92f),
    };

    public final int id;
    public final String label;
    public final float pitch;

    private VoiceStyle(int id, String label, float pitch) {
        this.id = id;
        this.label = label;
        this.pitch = clampPitch(pitch);
    }

    public static float clampPitch(float p) {
        if (Float.isNaN(p)) return 1f;
        if (p < MIN_PITCH) return MIN_PITCH;
        if (p > MAX_PITCH) return MAX_PITCH;
        return p;
    }

    /** 找不到就返回原声，绝不返回 null */
    public static VoiceStyle byId(int id) {
        for (VoiceStyle s : ALL) {
            if (s.id == id) return s;
        }
        return ALL[0];
    }

    public static String labelOf(int id) {
        return byId(id).label;
    }
}
