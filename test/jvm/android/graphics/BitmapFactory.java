package android.graphics;

/** 仅用于桌面 JVM 测试的占位类：不解码真实图片，只回报尺寸 */
public class BitmapFactory {

    public static class Options {
        public boolean inJustDecodeBounds;
        public int inSampleSize = 1;
        public int outWidth;
        public int outHeight;
        public Bitmap.Config inPreferredConfig;
    }

    public static Bitmap decodeByteArray(byte[] data, int off, int len, Options opts) {
        if (data == null || len <= 0) return null;
        // 粗略判断是否为图片
        if (len > 8) {
            int b0 = data[off] & 0xFF, b1 = data[off + 1] & 0xFF;
            boolean png = b0 == 0x89 && b1 == 0x50;
            boolean jpg = b0 == 0xFF && b1 == 0xD8;
            if (!png && !jpg) return null;
        }
        if (opts != null) {
            opts.outWidth = 600;
            opts.outHeight = 900;
            if (opts.inJustDecodeBounds) return null;
            return new Bitmap(600, 900);
        }
        return new Bitmap(600, 900);
    }
}
