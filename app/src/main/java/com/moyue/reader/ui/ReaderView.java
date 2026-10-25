package com.moyue.reader.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.text.Layout;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.moyue.reader.R;
import com.moyue.reader.book.BookSource;
import com.moyue.reader.text.TextRange;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生分页阅读视图
 * 使用 StaticLayout 排版，Canvas 绘制，按行高切页。
 * 不使用 WebView。
 */
public class ReaderView extends View {

    public interface Listener {
        void onProgress(int chapterIndex, int pageIndex, int pageCount, float percent);
        void onCenterTap();
        void onBookmarkRequested();
        /** 用户自己翻页了（不是朗读自动翻的） */
        void onUserTurn();
        /** 长按正文：从这里开始朗读，offset 是字符位置 */
        void onReadFrom(int offset);
    }

    // ---------- 主题 ----------
    public static final int THEME_PAPER = 0;
    public static final int THEME_WHITE = 1;
    public static final int THEME_SEPIA = 2;
    public static final int THEME_NIGHT = 3;

    private static final int[] BG = {0xFFF6F2EA, 0xFFFFFFFF, 0xFFF2E8D5, 0xFF14110E};
    private static final int[] FG = {0xFF241F1A, 0xFF1A1A1A, 0xFF3A2F20, 0xFFC9BFB2};

    // ---------- 纸张纹理 ----------
    public static final int TEX_NONE = 0;
    public static final int TEX_WOOD = 1;
    public static final int TEX_LINEN = 2;
    public static final int TEX_LEATHER = 3;
    public static final int TEX_MARBLE = 4;
    public static final int TEX_ANTIQUE = 5;

    private static final int[] TEX_RES = {
            0, R.drawable.tex_wood, R.drawable.tex_linen,
            R.drawable.tex_leather, R.drawable.tex_marble, R.drawable.tex_antique
    };

    private int texture = TEX_NONE;
    private Paint texPaint;
    private Bitmap texBmp;
    private int texPaintGen = -1;

    // ---------- 翻页效果 ----------
    public static final int TURN_SLIDE = 0;   // 平滑滑动
    public static final int TURN_FOLD = 1;    // 仿真翻书（圆柱卷曲）

    private int turnMode = TURN_FOLD;

    private BookSource book;
    private Listener listener;

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final Paint pagePaint = new Paint();
    private final Paint slideShadowPaint = new Paint();
    private final Paint meshPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint curlShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int chapterIndex = 0;
    private StaticLayout layout;
    /** 当前章排好版的纯文本，朗读和长按定位都以它为准（避免每次 toString 重新分配） */
    private String plainText = "";
    private int[] pageStartLines = new int[]{0};
    private int pageIndex = 0;
    private int pageCount = 1;

    private final List<Integer> lineStarts = new ArrayList<>();

    // 版面
    private int padH, padV, headerH, footerH;
    private int contentW, contentH;

    // 设置
    private float textSizeSp = 19f;
    private float lineSpacing = 1.65f;
    private boolean serif = true;
    private int theme = THEME_PAPER;
    private int marginLevel = 1; // 0 窄 1 中 2 宽

    // 动画
    private ValueAnimator animator;
    private float animProgress = 0f;
    private int animFrom = -1;
    private int animTo = -1;
    private int animDir = 0;
    private boolean animating = false;

    /** 本轮翻页的卷曲轴倾角（弧度），由拖动方向 / 点击位置决定 */
    private float curlTilt = 0f;

    // 触摸
    private float downX, downY;
    private long downTime;
    private boolean dragging = false;      // 正在跟手拖动
    private boolean dragBlocked = false;   // 本次手势不允许跟手（无页可翻）
    private boolean gestureIgnored = false; // 动画进行中按下的手势，整体丢弃

    // 长按选朗读起点
    private static final long LONG_PRESS_MS = 420;
    private boolean longPressFired = false;
    private float lpX, lpY;
    private final Runnable longPressTask = new Runnable() {
        @Override
        public void run() {
            if (dragging || longPressFired) return;
            longPressFired = true;
            fireLongPress();
        }
    };

    // 章节权重（用于全局进度）
    private float[] cumBefore;
    private float totalWeight = 1f;

    // 视图尚未测量时，暂存待打开的章节
    private int pendingChapter = -1;
    private int pendingPage = 0;
    private float pendingRatio = -1f;

    // ---------- 卷曲渲染用的位图缓存 ----------
    private int layoutGen = 0;
    private final PageCache pageCache = new PageCache(this::buildPageBitmap);

    // 网格顶点（复用，避免每帧分配）
    private static final int MESH_COLS = 72;
    private static final int MESH_ROWS = 26;
    private float[] meshVerts;
    private int[] meshColors;
    private final float[] mapTmp = new float[2];
    private final float[] segA = new float[4];
    private final Path curlPath = new Path();
    private Bitmap whiteBmp;

    // 朗读时高亮正在念的那一句
    private int readStart = -1, readEnd = -1;
    private final Paint hlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ReaderView(Context c) {
        super(c);
        init(c);
    }

    public ReaderView(Context c, AttributeSet a) {
        super(c, a);
        init(c);
    }

    private void init(Context c) {
        setWillNotDraw(false);
        float d = c.getResources().getDisplayMetrics().density;
        padV = Math.round(14 * d);
        headerH = Math.round(26 * d);
        footerH = Math.round(22 * d);

        headerPaint.setTextSize(11f * c.getResources().getDisplayMetrics().scaledDensity);
        headerPaint.setLetterSpacing(0.06f);

        applyTextPaint();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setBook(BookSource b) {
        this.book = b;
        computeWeights();
    }

    private void computeWeights() {
        if (book == null) return;
        int n = book.getChapterCount();
        cumBefore = new float[n + 1];
        float acc = 0;
        for (int i = 0; i < n; i++) {
            cumBefore[i] = acc;
            acc += book.getChapterWeight(i);
        }
        cumBefore[n] = acc;
        totalWeight = Math.max(1f, acc);
    }

    private void applyTextPaint() {
        float sd = getResources().getDisplayMetrics().scaledDensity;
        paint.setTextSize(textSizeSp * sd);
        paint.setColor(FG[theme]);
        paint.setTypeface(serif
                ? Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                : Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        paint.setSubpixelText(true);
        headerPaint.setColor(adjustAlpha(FG[theme], 0.42f));
        headerPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
    }

    private static int adjustAlpha(int color, float factor) {
        int a = Math.round(Color.alpha(color) * factor);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    // ---------- 设置 ----------

    public void setTextSize(float sp) {
        this.textSizeSp = sp;
        applyTextPaint();
        relayoutKeepPosition();
    }

    public float getTextSize() {
        return textSizeSp;
    }

    public void setLineSpacing(float mult) {
        this.lineSpacing = mult;
        relayoutKeepPosition();
    }

    public float getLineSpacing() {
        return lineSpacing;
    }

    public void setSerif(boolean s) {
        this.serif = s;
        applyTextPaint();
        relayoutKeepPosition();
    }

    public boolean isSerif() {
        return serif;
    }

    public void setTheme(int t) {
        this.theme = t;
        applyTextPaint();
        texPaintGen = -1;      // 夜间要换浅色着色
        layoutGen++;
        invalidate();
    }

    public int getTheme() {
        return theme;
    }

    public void setMarginLevel(int level) {
        this.marginLevel = level;
        onSizeChanged(getWidth(), getHeight(), 0, 0);
    }

    public int getMarginLevel() {
        return marginLevel;
    }

    public void setTexture(int t) {
        if (texture == t) return;
        texture = (t < 0 || t >= TEX_RES.length) ? TEX_NONE : t;
        // 旧贴图交给 GC，不 recycle——旧的 shader 可能还引用着它，
        // 回收了就会重现"用已回收位图绘制"的崩溃
        texBmp = null;
        texPaint = null;
        texPaintGen = -1;
        layoutGen++;            // 页面位图里烘了纹理，必须重做
        releasePageCache();
        invalidate();
    }

    public int getTexture() {
        return texture;
    }

    /** 底色 + 纹理。翻页时的整页位图也走这里，纹理才会跟着纸一起卷 */
    private void drawPaper(Canvas canvas) {
        canvas.drawColor(BG[theme]);
        if (texture == TEX_NONE) return;
        Paint p = texturePaint();
        if (p != null) canvas.drawRect(0, 0, getWidth(), getHeight(), p);
    }

    /**
     * 纹理用白色 RGB + alpha 的贴图，这里着色成深棕（夜间用浅灰），
     * 再压一个整体透明度——它是背景，不能抢文字。
     */
    private Paint texturePaint() {
        if (texPaintGen == layoutGen && texPaint != null) return texPaint;
        texPaintGen = layoutGen;

        if (texBmp == null || texBmp.isRecycled()) {
            try {
                texBmp = BitmapFactory.decodeResource(getResources(), TEX_RES[texture]);
            } catch (Throwable t) {
                texBmp = null;
            }
        }
        if (texBmp == null) {
            texPaint = null;
            return null;
        }

        BitmapShader shader = new BitmapShader(texBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        // 贴图按 xxhdpi 画：低密度屏上缩一点，免得纹理显得比字还大
        float scale = Math.max(0.55f, Math.min(1.4f,
                getResources().getDisplayMetrics().density / 3f));
        if (Math.abs(scale - 1f) > 0.01f) {
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            shader.setLocalMatrix(m);
        }

        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setShader(shader);
        p.setColorFilter(new PorterDuffColorFilter(
                theme == THEME_NIGHT ? 0xFFB9AC9A : 0xFF5A3C22, PorterDuff.Mode.SRC_IN));
        p.setAlpha(theme == THEME_NIGHT ? 80 : 140);
        texPaint = p;
        return p;
    }

    public void setTurnMode(int mode) {
        this.turnMode = (mode == TURN_SLIDE) ? TURN_SLIDE : TURN_FOLD;
    }

    public int getTurnMode() {
        return turnMode;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static int blend(int from, int to, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * r);
        int rr = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * r);
        int gg = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * r);
        int bb = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * r);
        return Color.argb(a, rr, gg, bb);
    }

    /** 翻过去那一面的纸色：比正面略暗（夜间则略亮） */
    private int paperBackColor() {
        int bg = BG[theme];
        if (theme == THEME_NIGHT) return blend(bg, 0xFFFFFFFF, 0.05f);
        return blend(bg, 0xFF000000, 0.06f);
    }

    public int getThemeBg() {
        return BG[theme];
    }

    public int getThemeFg() {
        return FG[theme];
    }

    // ---------- 尺寸 ----------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float d = getResources().getDisplayMetrics().density;
        int extra = marginLevel == 0 ? 12 : marginLevel == 1 ? 24 : 38;
        padH = Math.round(extra * d);

        releasePageCache();
        layoutGen++;

        if (pendingChapter >= 0 && w > 0 && h > 0) {
            int c = pendingChapter;
            float r = pendingRatio;
            int p = pendingPage;
            pendingChapter = -1;
            if (r >= 0) openChapter(c, r);
            else openChapter(c, p);
            return;
        }
        relayoutKeepPosition();
    }

    private int currentContentW() {
        return Math.max(120, getWidth() - padH * 2);
    }

    private int currentContentH() {
        return Math.max(120, getHeight() - padV * 2 - headerH - footerH);
    }

    // ---------- 排版 ----------

    public void openChapter(int index, int targetPage) {
        if (book == null) return;
        if (getWidth() <= 0 || getHeight() <= 0) {
            pendingChapter = index;
            pendingPage = targetPage;
            pendingRatio = -1f;
            return;
        }
        chapterIndex = Math.max(0, Math.min(index, book.getChapterCount() - 1));
        buildLayout();
        pageIndex = Math.max(0, Math.min(targetPage, pageCount - 1));
        invalidate();
        notifyProgress();
    }

    public void openChapter(int index, float pageRatio) {
        if (book == null) return;
        if (getWidth() <= 0 || getHeight() <= 0) {
            pendingChapter = index;
            pendingPage = 0;
            pendingRatio = pageRatio;
            return;
        }
        chapterIndex = Math.max(0, Math.min(index, book.getChapterCount() - 1));
        buildLayout();
        pageIndex = Math.max(0, Math.min(Math.round(pageRatio * (pageCount - 1)), pageCount - 1));
        invalidate();
        notifyProgress();
    }

    private void relayoutKeepPosition() {
        if (pendingChapter >= 0) return;
        if (book == null || layout == null) {
            if (getWidth() > 0 && book != null) {
                buildLayout();
                invalidate();
            }
            return;
        }
        float ratio = pageCount <= 1 ? 0f : (float) pageIndex / (pageCount - 1);
        buildLayout();
        pageIndex = Math.max(0, Math.min(Math.round(ratio * (pageCount - 1)), pageCount - 1));
        invalidate();
        notifyProgress();
    }

    private void buildLayout() {
        contentW = currentContentW();
        contentH = currentContentH();

        String html = book.getChapterHtml(chapterIndex);
        Spanned text;
        try {
            text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY, imageGetter, null);
        } catch (Throwable t) {
            text = Html.fromHtml(html == null ? "" : html);
        }

        String plain = text.toString();
        if (plain.trim().isEmpty()) {
            text = Html.fromHtml("<p>（本章无可显示内容）</p>", Html.FROM_HTML_MODE_LEGACY);
        }

        int extra = Math.round((lineSpacing - 1f) * paint.getTextSize());
        StaticLayout.Builder b = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, contentW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(extra, 1f)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        layout = b.build();

        plainText = layout.getText().toString();
        layoutGen++;
        releasePageCache();
        paginate();
    }

    private void paginate() {
        lineStarts.clear();
        int lines = layout.getLineCount();
        if (lines <= 0) {
            lineStarts.add(0);
            pageStartLines = new int[]{0};
            pageCount = 1;
            return;
        }

        int start = 0;
        int topY = 0;
        for (int line = 0; line < lines; line++) {
            int bottom = layout.getLineBottom(line);
            if (bottom - topY > contentH) {
                if (line > start) {
                    lineStarts.add(start);
                    start = line;
                    topY = layout.getLineTop(line);
                } else {
                    // 单行超过一页（超大图片行）
                    lineStarts.add(start);
                    start = Math.min(line + 1, lines - 1);
                    topY = layout.getLineTop(start);
                }
            }
        }
        lineStarts.add(start);

        pageStartLines = new int[lineStarts.size()];
        for (int i = 0; i < lineStarts.size(); i++) pageStartLines[i] = lineStarts.get(i);
        pageCount = pageStartLines.length;
    }

    private final Html.ImageGetter imageGetter = new Html.ImageGetter() {
        @Override
        public Drawable getDrawable(String source) {
            if (source == null || !source.startsWith("moyue-img:")) return null;
            try {
                int idx = Integer.parseInt(source.substring("moyue-img:".length()));
                Bitmap bmp = book.getChapterImage(idx, Math.max(200, contentW));
                if (bmp == null) return null;

                float scale = Math.min(1f, (float) contentW / bmp.getWidth());
                int w = Math.max(1, Math.round(bmp.getWidth() * scale));
                int h = Math.max(1, Math.round(bmp.getHeight() * scale));
                int maxH = Math.round(contentH * 1.35f);
                if (h > maxH) {
                    w = Math.max(1, Math.round(w * (float) maxH / h));
                    h = maxH;
                }
                BitmapDrawable d = new BitmapDrawable(getResources(), bmp);
                d.setBounds(0, 0, w, h);
                return d;
            } catch (Throwable t) {
                return null;
            }
        }
    };

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas canvas) {
        pageCache.beginFrame();
        drawPaper(canvas);
        if (layout == null) return;

        boolean turning = animating && animFrom >= 0 && animTo >= 0;

        if (turning && turnMode == TURN_FOLD) {
            drawCurlTurn(canvas);
            return;   // 页眉页脚已经烘进页面位图，跟着一起卷
        }

        if (turning) {
            drawSlideTurn(canvas);
        } else {
            drawPageAt(canvas, pageIndex, 0);
            drawReadHighlight(canvas);
        }
        drawChrome(canvas);
    }

    // ---------- 朗读高亮 ----------

    /** 高亮正在朗读的那一段（字符区间），-1 表示不高亮 */
    public void setReadHighlight(int start, int end) {
        if (readStart == start && readEnd == end) return;
        readStart = start;
        readEnd = end;
        invalidate();
    }

    public void clearReadHighlight() {
        setReadHighlight(-1, -1);
    }

    private void drawReadHighlight(Canvas canvas) {
        if (layout == null || readStart < 0 || readEnd <= readStart) return;
        if (pageStartLines.length == 0) return;

        int len = layout.getText().length();
        if (len <= 0) return;
        int s = Math.max(0, Math.min(readStart, len - 1));
        int e = Math.max(s + 1, Math.min(readEnd, len));

        int first = pageStartLines[pageIndex];
        int last = (pageIndex + 1 < pageStartLines.length)
                ? pageStartLines[pageIndex + 1] - 1
                : layout.getLineCount() - 1;

        int l0 = Math.max(first, layout.getLineForOffset(s));
        int l1 = Math.min(last, layout.getLineForOffset(e - 1));
        if (l1 < l0) return;

        int top = layout.getLineTop(first);
        hlPaint.setColor(theme == THEME_NIGHT ? 0x33FFD98A : 0x38E8A33D);

        canvas.save();
        canvas.clipRect(padH, padV + headerH, padH + contentW, padV + headerH + contentH);
        canvas.translate(padH, padV + headerH - top);
        float r = dp(2.5f);
        for (int line = l0; line <= l1; line++) {
            float left = layout.getLineLeft(line);
            float right = layout.getLineRight(line);
            if (right - left < 1f) continue;
            canvas.drawRoundRect(left, layout.getLineTop(line) + dp(1),
                    right, layout.getLineBottom(line) - dp(1), r, r, hlPaint);
        }
        canvas.restore();
    }

    // ---------- 平滑滑动 ----------

    private void drawSlideTurn(Canvas canvas) {
        float w = getWidth();
        float p = animProgress;
        if (animDir > 0) {
            drawPageAt(canvas, animFrom, -p * w);
            drawPageAt(canvas, animTo, (1 - p) * w);
            drawPageShadow(canvas, (1 - p) * w, true);
        } else {
            drawPageAt(canvas, animFrom, p * w);
            drawPageAt(canvas, animTo, (p - 1) * w);
            drawPageShadow(canvas, (p - 1) * w, false);
        }
    }

    // ---------- 仿真翻书：圆柱卷曲 ----------

    /**
     * 和 iBooks 同一套模型：页面绕一根卷曲轴弯成圆柱。
     * 几何全在 {@link CurlGeometry} 里，这里只负责贴图。
     */
    private void drawCurlTurn(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        final boolean forward = animDir > 0;

        final float radius = Math.max(dp(34), w * 0.105f);
        final CurlGeometry g = CurlGeometry.of(w, h, animProgress, forward, curlTilt, radius);

        final int topPage = forward ? animFrom : animTo;      // 正在卷的那页
        final int bottomPage = forward ? animTo : animFrom;   // 底下露出来的那页

        Bitmap bottom = pageBitmap(bottomPage);
        Bitmap top = pageBitmap(topPage);
        // 位图分配失败、或（理论上不会）拿到已回收的位图，都退回滑动，绝不拿它去画
        if (bottom == null || top == null || bottom.isRecycled() || top.isRecycled()) {
            drawSlideTurn(canvas);
            return;
        }

        // 1. 底下那页
        canvas.drawBitmap(bottom, 0, 0, null);

        // 2. 卷筒在底下那页上投的影
        drawCurlShadow(canvas, g, w, h);

        // 3. 正面（网格形变，纸被压向折痕）
        drawCurledFront(canvas, g, top, w, h);

        // 4. 纸背（卷过最高点之后看到的那一面）
        drawCurledBack(canvas, g, w, h);
    }

    /** 正面：整页做网格形变，卷过最高点的部分收成一条线（正面不可见） */
    private void drawCurledFront(Canvas canvas, CurlGeometry g, Bitmap bmp, int w, int h) {
        final int cols = MESH_COLS, rows = MESH_ROWS;
        final int n = (cols + 1) * (rows + 1);
        ensureMesh(n);

        // drawBitmapMesh 的纹理就是整张位图按网格均分，所以只给目标顶点
        int k = 0, ci = 0;
        for (int j = 0; j <= rows; j++) {
            float y = (float) h * j / rows;
            for (int i = 0; i <= cols; i++) {
                float x = (float) w * i / cols;
                float phi = g.map(x, y, mapTmp);
                meshVerts[k] = mapTmp[0];
                meshVerts[k + 1] = mapTmp[1];
                // 纸面越接近竖直（φ 越大），越背离视线，压暗一点
                float shade = 1f - 0.30f * Math.min(1f, phi / CurlGeometry.HALF_PI);
                int v = Math.max(0, Math.min(255, Math.round(255 * shade)));
                meshColors[ci] = 0xFF000000 | (v << 16) | (v << 8) | v;
                k += 2;
                ci++;
            }
        }
        canvas.drawBitmapMesh(bmp, cols, rows, meshVerts, 0, meshColors, 0, meshPaint);
    }

    /**
     * 纸背。同样走网格——不能画成"两条平行线之间的带子"：
     * dMax 只在页面某一个角取到，折痕一倾斜，带子就会画到纸实际不存在的地方。
     * 网格的边界严格等于纸张矩形的像，任何角度都不会漏出来。
     *
     * 明暗按物理来：圆柱上 φ=π/2 处纸面竖直（掠射，最暗），φ=π 处正对视线（最亮）。
     */
    private void drawCurledBack(Canvas canvas, CurlGeometry g, int w, int h) {
        if (!g.showsBack()) return;
        Bitmap tex = whiteBitmap();
        if (tex == null) return;

        final int cols = MESH_COLS, rows = MESH_ROWS;
        final int n = (cols + 1) * (rows + 1);
        ensureMesh(n);

        final float cut = CurlGeometry.HALF_PI;
        final int base = paperBackColor();

        int k = 0, ci = 0;
        for (int j = 0; j <= rows; j++) {
            float y = (float) h * j / rows;
            for (int i = 0; i <= cols; i++) {
                float x = (float) w * i / cols;
                float phi = g.mapBack(x, y, mapTmp);
                meshVerts[k] = mapTmp[0];
                meshVerts[k + 1] = mapTmp[1];

                float t = Math.min(1f, Math.max(0f, (phi - cut) / cut));   // 卷顶 0 → 平铺 1
                meshColors[ci] = scaleColor(base, 0.80f + 0.20f * t);
                k += 2;
                ci++;
            }
        }
        canvas.drawBitmapMesh(tex, cols, rows, meshVerts, 0, meshColors, 0, meshPaint);
    }

    private static int scaleColor(int color, float f) {
        int r = Math.min(255, Math.round(Color.red(color) * f));
        int gg = Math.min(255, Math.round(Color.green(color) * f));
        int b = Math.min(255, Math.round(Color.blue(color) * f));
        return Color.argb(Color.alpha(color), r, gg, b);
    }

    /** 卷筒悬空，在底下那页上压出一道渐隐的影 */
    private void drawCurlShadow(Canvas canvas, CurlGeometry g, int w, int h) {
        if (g.dMax <= 0f) return;
        if (!g.paperSegment(g.dSilhouette(), null, segA)) return;

        float o = dp(42);
        curlPath.reset();
        curlPath.moveTo(segA[0], segA[1]);
        curlPath.lineTo(segA[2], segA[3]);
        curlPath.lineTo(segA[2] + g.nx * o, segA[3] + g.ny * o);
        curlPath.lineTo(segA[0] + g.nx * o, segA[1] + g.ny * o);
        curlPath.close();

        int dark = theme == THEME_NIGHT ? 0x66000000 : 0x3D000000;
        curlShadowPaint.setShader(new LinearGradient(
                segA[0], segA[1], segA[0] + g.nx * o, segA[1] + g.ny * o,
                new int[]{dark, 0x00000000}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(curlPath, curlShadowPaint);
    }

    /** 纯白小位图，给纸背网格当纹理（颜色由顶点色决定） */
    private Bitmap whiteBitmap() {
        if (whiteBmp == null || whiteBmp.isRecycled()) {
            try {
                whiteBmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
                whiteBmp.eraseColor(0xFFFFFFFF);
            } catch (Throwable t) {
                return null;
            }
        }
        return whiteBmp;
    }

    private void ensureMesh(int n) {
        if (meshVerts == null || meshVerts.length != n * 2) {
            meshVerts = new float[n * 2];
            meshColors = new int[n];
        }
    }

    // ---------- 页面位图 ----------

    private void releasePageCache() {
        pageCache.releaseAll();
    }

    private Bitmap pageBitmap(int idx) {
        pageCache.setGeneration(layoutGen);
        return pageCache.acquire(idx);
    }

    /** 把某一页（含页眉页脚）烘成一张全屏位图，供卷曲网格使用 */
    private Bitmap buildPageBitmap(int idx) {
        Bitmap b;
        try {
            b = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return null;
        }
        Canvas c = new Canvas(b);
        drawPaper(c);
        drawPageAt(c, idx, 0);
        drawChrome(c, idx);
        return b;
    }

    private void drawPageAt(Canvas canvas, int idx, float dx) {
        if (idx < 0 || idx >= pageStartLines.length) return;
        int top = layout.getLineTop(pageStartLines[idx]);

        canvas.save();
        canvas.clipRect(padH + dx, padV + headerH,
                padH + contentW + dx, padV + headerH + contentH);
        canvas.translate(padH + dx, padV + headerH - top);
        layout.draw(canvas);
        canvas.restore();
    }

    private void drawPageShadow(Canvas canvas, float x, boolean fromRight) {
        int w = Math.round(14 * getResources().getDisplayMetrics().density);
        int top = padV + headerH;
        int bottom = top + contentH;
        int edge = Math.round(padH + (fromRight ? 0 : contentW) + x);
        int transparent = Color.TRANSPARENT;
        int dark = adjustAlpha(0xFF000000, theme == THEME_NIGHT ? 0.5f : 0.13f);
        if (fromRight) {
            slideShadowPaint.setShader(new LinearGradient(edge - w, 0, edge, 0,
                    new int[]{transparent, dark}, null, Shader.TileMode.CLAMP));
        } else {
            slideShadowPaint.setShader(new LinearGradient(edge, 0, edge + w, 0,
                    new int[]{dark, transparent}, null, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(fromRight ? edge - w : edge, top,
                fromRight ? edge : edge + w, bottom, slideShadowPaint);
    }

    private void drawChrome(Canvas canvas) {
        int shownPage = pageIndex;
        if (animating && animFrom >= 0 && animTo >= 0) {
            shownPage = animProgress > 0.55f ? animTo : animFrom;
        }
        drawChrome(canvas, shownPage);
    }

    private void drawChrome(Canvas canvas, int pageForFooter) {
        // 页眉：章节标题
        String title = book != null ? book.getChapterTitle(chapterIndex) : "";
        if (title != null && !title.isEmpty()) {
            canvas.save();
            float ty = padV + headerH * 0.62f;
            canvas.drawText(ellipsize(title, headerPaint, contentW), padH, ty, headerPaint);
            canvas.restore();
        }

        // 页脚：页码
        String pageStr = (pageForFooter + 1) + " / " + pageCount;
        float fy = getHeight() - padV - footerH * 0.3f;
        float tw = headerPaint.measureText(pageStr);
        canvas.drawText(pageStr, (getWidth() - tw) / 2f, fy, headerPaint);
    }

    private String ellipsize(String s, Paint p, int maxW) {
        if (p.measureText(s) <= maxW) return s;
        String ell = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.measureText(s.substring(0, mid) + ell) <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, Math.max(0, lo)) + ell;
    }

    // ---------- 翻页 ----------

    public boolean nextPage() {
        return nextPage(0.5f);
    }

    /**
     * @param anchorY 触发翻页的触点在屏幕上的纵向位置（0–1），
     *                用来决定卷曲轴往哪边歪——从屏幕下沿翻和从中间翻，卷起来不一样。
     */
    public boolean nextPage(float anchorY) {
        if (layout == null) return false;
        if (pageIndex < pageCount - 1) {
            if (listener != null) listener.onUserTurn();
            animateTo(pageIndex + 1, 1, tiltForAnchor(anchorY, true));
            return true;
        }
        if (chapterIndex < book.getChapterCount() - 1) {
            if (listener != null) listener.onUserTurn();
            openChapter(chapterIndex + 1, 0);
            return true;
        }
        return false;
    }

    public boolean prevPage() {
        return prevPage(0.5f);
    }

    public boolean prevPage(float anchorY) {
        if (layout == null) return false;
        if (pageIndex > 0) {
            if (listener != null) listener.onUserTurn();
            animateTo(pageIndex - 1, -1, tiltForAnchor(anchorY, false));
            return true;
        }
        if (chapterIndex > 0) {
            if (listener != null) listener.onUserTurn();
            int prevCh = chapterIndex - 1;
            openChapter(prevCh, 1f);
            return true;
        }
        return false;
    }

    /** 朗读自动翻页：相邻页走动画，跳页直接切。动画中也不放弃，先落地再翻 */
    public void turnToPage(int page) {
        if (layout == null) return;
        if (animating && animator != null) {
            animator.cancel();
            animating = false;
            animFrom = -1;
            animProgress = 0f;
            curlTilt = 0f;
        }
        int p = Math.max(0, Math.min(page, pageCount - 1));
        if (p == pageIndex) return;
        if (Math.abs(p - pageIndex) == 1) {
            animateTo(p, p > pageIndex ? 1 : -1, 0f);
        } else {
            setPageQuiet(p);
        }
    }

    /** 触点越靠下，卷曲轴越倾斜，像是捏住下角在翻 */
    private static float tiltForAnchor(float anchorY, boolean forward) {
        float t = (anchorY - 0.5f) * 0.26f;
        return forward ? t : -t;
    }

    private void animateTo(int target, int dir, float tilt) {
        animFrom = pageIndex;
        animTo = target;
        animDir = dir;
        animProgress = 0f;
        curlTilt = tilt;
        animating = true;
        runSettle(0f, 1f, true);
    }

    /** 手指按下并横向拖动：进入跟手卷曲 */
    private void beginDrag(int dir, float dx, float dy) {
        animFrom = pageIndex;
        animTo = pageIndex + dir;
        animDir = dir;
        animProgress = 0f;
        // 卷曲方向 ⟂ 拖动方向：前翻 n = -v，后翻 n = +v
        float vx = dir > 0 ? -dx : dx;
        float vy = dir > 0 ? -dy : dy;
        float len = (float) Math.hypot(vx, vy);
        if (len < 1f) {
            curlTilt = 0f;
        } else {
            float t = (float) Math.atan2(vy, vx);
            curlTilt = Math.max(-0.62f, Math.min(0.62f, t));
        }
        animating = true;
        dragging = true;
    }

    /** 松手后收尾：卷过三成就翻过去，否则弹回原页 */
    private void settleAfterDrag() {
        if (animProgress >= 0.28f) runSettle(animProgress, 1f, true);
        else runSettle(animProgress, 0f, false);
    }

    private void runSettle(float from, float to, boolean commit) {
        if (animator != null) animator.cancel();
        final boolean fold = turnMode == TURN_FOLD;
        long base = fold ? 320 : 200;
        long dur = Math.max(70, Math.round(Math.abs(to - from) * base));

        animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(dur);
        animator.setInterpolator(to > from
                ? new DecelerateInterpolator(fold ? 1.05f : 1.6f)
                : new DecelerateInterpolator(2f));
        animator.addUpdateListener(a -> {
            animProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                animating = false;
                if (commit) pageIndex = animTo;
                animFrom = -1;
                animProgress = 0f;
                curlTilt = 0f;
                invalidate();
                notifyProgress();
            }
        });
        animator.start();
    }

    private void notifyProgress() {
        if (listener != null) {
            listener.onProgress(chapterIndex, pageIndex, pageCount, globalPercent());
        }
    }

    public float globalPercent() {
        if (book == null || cumBefore == null) return 0f;
        int n = book.getChapterCount();
        if (chapterIndex >= n) return 1f;
        float w = cumBefore[Math.min(chapterIndex + 1, n)] - cumBefore[chapterIndex];
        float within = pageCount <= 1 ? 1f : (float) (pageIndex + 1) / pageCount;
        float v = (cumBefore[chapterIndex] + w * within) / totalWeight;
        return Math.max(0f, Math.min(1f, v));
    }

    public void goToPercent(float p) {
        if (book == null) return;
        p = Math.max(0f, Math.min(1f, p));
        float target = p * totalWeight;
        int n = book.getChapterCount();
        int ch = 0;
        for (int i = 0; i < n; i++) {
            if (target >= cumBefore[i] && target < cumBefore[i + 1]) {
                ch = i;
                break;
            }
            if (i == n - 1) ch = n - 1;
        }
        float w = cumBefore[ch + 1] - cumBefore[ch];
        float within = w <= 0 ? 0 : (target - cumBefore[ch]) / w;
        openChapter(ch, within);
    }

    public int getChapterIndex() {
        return chapterIndex;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public int getPageCount() {
        return pageCount;
    }

    public float getPageRatio() {
        return pageCount <= 1 ? 0f : (float) pageIndex / (pageCount - 1);
    }

    // ---------- 朗读用：正文与页码的对应 ----------

    /** 当前章排好版的纯文本，朗读念的就是它 */
    public String getPlainText() {
        return layout == null ? "" : plainText;
    }

    /** 某个字符位置落在第几页 */
    public int pageForOffset(int offset) {
        if (layout == null) return 0;
        int len = plainText.length();
        int line = layout.getLineForOffset(Math.max(0, Math.min(offset, Math.max(0, len - 1))));
        return TextRange.pageOfLine(pageStartLines, line);
    }

    /**
     * 朗读跟读：把画面挪到正在念的那一句，再把那句点亮。
     *
     * 这两步必须一起做、而且不能被动画吞掉——否则声音已经念到下一页，
     * 高亮还留在上一页（甚至干脆不显示），就是"翻到哪页读哪页"做不到的原因。
     */
    public void followReading(int start, int end) {
        if (layout == null) return;

        if (animating) {
            // 朗读优先：正在翻的动画立刻落地，不能让它把这次追页挡掉
            if (animator != null) animator.cancel();
            animating = false;
            animFrom = -1;
            animProgress = 0f;
            curlTilt = 0f;
        }

        int target = pageForOffset(start);
        if (target != pageIndex) {
            if (Math.abs(target - pageIndex) == 1) {
                animateTo(target, target > pageIndex ? 1 : -1, 0f);
            } else {
                setPageQuiet(target);
            }
        }
        setReadHighlight(start, end);
        invalidate();
    }

    /** 屏幕坐标 → 字符位置（限制在当前页内，按在页边也不会跑到别的页去） */
    public int charOffsetAt(float x, float y) {
        if (layout == null || plainText.isEmpty()) return 0;
        if (pageStartLines.length == 0) return 0;

        int first = pageStartLines[Math.min(pageIndex, pageStartLines.length - 1)];
        int last = lastLineOfPage(pageIndex);

        float top = layout.getLineTop(first);
        float ly = y - (padV + headerH) + top;
        int line = layout.getLineForVertical(Math.max(0, Math.round(ly)));
        line = TextRange.clamp(line, first, last);

        float lx = Math.max(0, x - padH);
        int off = layout.getOffsetForHorizontal(line, lx);
        // getOffsetForHorizontal 可能给到行尾之外，夹回本行范围
        int ls = layout.getLineStart(line);
        int le = layout.getLineEnd(line);
        return TextRange.clamp(off, ls, Math.max(ls, le - 1));
    }

    private int lastLineOfPage(int page) {
        int n = layout.getLineCount();
        if (pageStartLines.length == 0) return Math.max(0, n - 1);
        int p = TextRange.clamp(page, 0, pageStartLines.length - 1);
        int last = (p + 1 < pageStartLines.length) ? pageStartLines[p + 1] - 1 : n - 1;
        return TextRange.clamp(last, 0, Math.max(0, n - 1));
    }

    /** 长按：把按住那一段点亮，然后让外面从这句开始念 */
    private void fireLongPress() {
        if (layout == null) return;
        int off = charOffsetAt(lpX, lpY);
        int ps = TextRange.paragraphStart(plainText, off);
        int pe = TextRange.paragraphEnd(plainText, off);
        if (pe <= ps) pe = Math.min(plainText.length(), ps + 1);

        // 先把整段点亮：手指按下去立刻看到"就这儿"，不用等引擎起播
        setReadHighlight(ps, pe);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

        if (listener != null) listener.onReadFrom(off);
    }

    /** 某一页从第几个字符开始 */
    public int offsetForPage(int page) {
        if (layout == null || pageStartLines.length == 0) return 0;
        int p = Math.max(0, Math.min(page, pageStartLines.length - 1));
        return layout.getLineStart(pageStartLines[p]);
    }

    /** 朗读自动翻页用：直接跳到某页，不播动画 */
    public void setPageQuiet(int page) {
        if (layout == null) return;
        int p = Math.max(0, Math.min(page, pageCount - 1));
        if (p == pageIndex) return;
        pageIndex = p;
        invalidate();
        notifyProgress();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(longPressTask);
        releasePageCache();
    }

    // ---------- 触摸 ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (animating) {          // 上一次动画还没走完，这次手势整个丢掉
                    gestureIgnored = true;
                    return true;
                }
                gestureIgnored = false;
                dragging = false;
                dragBlocked = false;
                longPressFired = false;
                downX = e.getX();
                downY = e.getY();
                downTime = System.currentTimeMillis();
                lpX = downX;
                lpY = downY;
                removeCallbacks(longPressTask);
                postDelayed(longPressTask, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (gestureIgnored) return true;
                if (longPressFired) return true;   // 已经触发长按，这次手势不再翻页

                float dx = e.getX() - downX;
                float dy = e.getY() - downY;

                if (Math.abs(dx) > dp(10) || Math.abs(dy) > dp(10)) {
                    removeCallbacks(longPressTask);   // 动了就不是长按
                }

                if (!dragging && !dragBlocked
                        && Math.abs(dx) > dp(10) && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                    int dir = dx < 0 ? 1 : -1;
                    if ((dir > 0 && pageIndex < pageCount - 1)
                            || (dir < 0 && pageIndex > 0)) {
                        beginDrag(dir, dx, dy);
                    } else {
                        // 本章没有相邻页了，交给松手后的翻章逻辑
                        dragBlocked = true;
                    }
                }
                if (dragging) {
                    float span = Math.max(1f, getWidth() * 0.82f);
                    animProgress = Math.min(1f, Math.abs(dx) / span);
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPressTask);
                boolean cancelled = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (gestureIgnored) {
                    gestureIgnored = false;
                    return true;
                }
                if (longPressFired) {
                    longPressFired = false;
                    return true;          // 长按已经把这次点击吃掉了
                }
                if (dragging) {
                    dragging = false;
                    if (cancelled) runSettle(animProgress, 0f, false);
                    else settleAfterDrag();
                    return true;
                }
                if (cancelled) return true;

                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                float d = getResources().getDisplayMetrics().density;
                long dt = System.currentTimeMillis() - downTime;

                if (Math.abs(dx) > 48 * d && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    float ay = downY / Math.max(1, getHeight());
                    if (dx < 0) nextPage(ay);
                    else prevPage(ay);
                    return true;
                }
                if (Math.abs(dx) < 16 * d && Math.abs(dy) < 16 * d && dt < 600) {
                    float w = getWidth();
                    float ay = e.getY() / Math.max(1, getHeight());
                    if (e.getX() < w * 0.28f) prevPage(ay);
                    else if (e.getX() > w * 0.72f) nextPage(ay);
                    else if (listener != null) listener.onCenterTap();
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }
}
