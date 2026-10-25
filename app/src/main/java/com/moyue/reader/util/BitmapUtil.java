package com.moyue.reader.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

public class BitmapUtil {

    /** 圆角位图（左上/左下略小，模拟书脊） */
    public static Bitmap rounded(Bitmap src, float tl, float tr, float br, float bl) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);

        android.graphics.Path path = new android.graphics.Path();
        float[] radii = {tl, tl, tr, tr, br, br, bl, bl};
        path.addRoundRect(new RectF(0, 0, w, h), radii, android.graphics.Path.Direction.CW);
        c.drawPath(path, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        c.drawBitmap(src, 0, 0, p);
        return out;
    }

    /** 居中裁剪为指定宽高比 */
    public static Bitmap centerCrop(Bitmap src, int targetW, int targetH) {
        if (src == null || targetW <= 0 || targetH <= 0) return src;
        float srcRatio = (float) src.getWidth() / src.getHeight();
        float dstRatio = (float) targetW / targetH;

        int cropW, cropH;
        if (srcRatio > dstRatio) {
            cropH = src.getHeight();
            cropW = Math.round(cropH * dstRatio);
        } else {
            cropW = src.getWidth();
            cropH = Math.round(cropW / dstRatio);
        }
        int x = (src.getWidth() - cropW) / 2;
        int y = (src.getHeight() - cropH) / 2;
        return Bitmap.createBitmap(src, x, y, Math.min(cropW, src.getWidth()), Math.min(cropH, src.getHeight()));
    }

    public static int dp(android.content.Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
