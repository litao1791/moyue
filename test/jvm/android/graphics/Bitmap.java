package android.graphics;

/** 仅用于桌面 JVM 测试 EpubBook 解析逻辑的占位类 */
public class Bitmap {
    public enum Config { RGB_565, ARGB_8888 }

    public enum CompressFormat { JPEG, PNG }

    private final int w, h;
    private boolean recycled;

    public Bitmap(int w, int h) {
        this.w = w;
        this.h = h;
    }

    public static Bitmap createBitmap(int w, int h, Config c) {
        return new Bitmap(w, h);
    }

    public int getWidth() {
        return w;
    }

    public int getHeight() {
        return h;
    }

    public boolean isRecycled() {
        return recycled;
    }

    public void recycle() {
        recycled = true;
    }

    public boolean compress(CompressFormat f, int q, java.io.OutputStream os) {
        return true;
    }
}
