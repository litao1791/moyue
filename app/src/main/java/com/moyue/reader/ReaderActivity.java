package com.moyue.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.moyue.reader.db.LibraryDb;
import com.moyue.reader.book.BookSource;
import com.moyue.reader.book.TocEntry;
import com.moyue.reader.model.BookRecord;
import com.moyue.reader.ui.ReaderView;
import com.moyue.reader.tts.SpeechReader;
import com.moyue.reader.tts.VoiceStyle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ReaderActivity extends Activity implements ReaderView.Listener {

    public static final String EXTRA_UUID = "uuid";

    private ReaderView reader;
    private View topBar, bottomBar, scrim, drawer, sheet;
    private TextView topTitle, topChapter, tvPage, tvPercent, drawerTitle;
    private SeekBar seek;
    private ListView tocList;
    private ImageButton btnBookmark;

    // 朗读
    private SpeechReader speech;
    private TextView tvSpeak;
    private int speechState = 0;          // 0 停 / 1 读 / 2 暂停
    private boolean pendingSpeak = false; // 引擎还没就绪，先记下要读
    private int pendingOffset = -1;       // 挂起时记下从哪儿开始念
    private int lastReadStart = -1;       // 当前念到哪一句的开头，「继续」从这里接
    private int lastReadChapter = -1;     // 上面那个位置属于哪一章（换章后作废）

    // 悬浮朗读条
    private View speechBar;
    private TextView tvSpeechState, btnSpeechToggle, btnSpeechStop;
    private boolean speechBarShown = false;

    private BookSource book;
    private BookRecord record;
    private LibraryDb db;

    private boolean barsVisible = false;
    private boolean drawerOpen = false;
    private boolean sheetOpen = false;

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private List<Bookmark> bookmarks = new ArrayList<>();

    private static class Bookmark {
        int chapter;
        int page;
        String label;
        float percent;

        String key() {
            return chapter + ":" + page;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        db = new LibraryDb(this);

        String uuid = getIntent().getStringExtra(EXTRA_UUID);
        record = uuid == null ? null : db.findByUuid(uuid);
        if (record == null) {
            Toast.makeText(this, "找不到这本书", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        File f = new File(new File(getFilesDir(), "books"), record.fileName);
        try {
            book = BookSource.open(f);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开：文件可能已损坏", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        bindViews();
        applySettings();
        loadBookmarks();

        reader.setBook(book);
        reader.setListener(this);

        // 恢复进度
        int ch = Math.max(0, Math.min(record.chapterIndex, book.getChapterCount() - 1));
        reader.openChapter(ch, record.pageIndex);

        topTitle.setText(record.title);
        drawerTitle.setText("目录 · " + book.getChapterCount() + " 章");
        setupToc();

        // 全屏沉浸
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void bindViews() {
        reader = findViewById(R.id.reader);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        scrim = findViewById(R.id.scrim);
        drawer = findViewById(R.id.drawer);
        sheet = findViewById(R.id.sheet);
        topTitle = findViewById(R.id.top_title);
        topChapter = findViewById(R.id.top_chapter);
        tvPage = findViewById(R.id.tv_page);
        tvPercent = findViewById(R.id.tv_percent);
        drawerTitle = findViewById(R.id.drawer_title);
        seek = findViewById(R.id.seek);
        tocList = findViewById(R.id.toc_list);
        btnBookmark = findViewById(R.id.btn_bookmark);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.drawer_close).setOnClickListener(v -> closeDrawer());
        scrim.setOnClickListener(v -> {
            closeDrawer();
            closeSheet();
        });

        findViewById(R.id.btn_toc).setOnClickListener(v -> {
            hideBars();
            openDrawer();
        });
        findViewById(R.id.btn_settings).setOnClickListener(v -> {
            hideBars();
            openSheet();
        });
        btnBookmark.setOnClickListener(v -> toggleBookmark());

        tvSpeak = findViewById(R.id.tv_speak);
        View btnSpeak = findViewById(R.id.btn_speak);
        btnSpeak.setOnClickListener(v -> onSpeakButton());
        btnSpeak.setOnLongClickListener(v -> {
            showSpeechOptions();
            return true;
        });

        // 悬浮朗读条：朗读时常驻，暂停/继续 + 停止都在明面上
        speechBar = findViewById(R.id.speech_bar);
        tvSpeechState = findViewById(R.id.tv_speech_state);
        btnSpeechToggle = findViewById(R.id.btn_speech_toggle);
        btnSpeechStop = findViewById(R.id.btn_speech_stop);

        btnSpeechToggle.setOnClickListener(v -> {
            if (speechState == 1) {
                if (speech != null) speech.pause();
                speechState = 2;
                updateSpeakUi();
            } else {
                startSpeakingAt(lastReadStart);
            }
        });
        btnSpeechStop.setOnClickListener(v -> {
            stopSpeaking();
            Toast.makeText(this, "已停止朗读", Toast.LENGTH_SHORT).show();
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                tvPercent.setText((progress / 10) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                reader.goToPercent(sb.getProgress() / 1000f);
            }
        });

        setupSettingsPanel();
    }

    // ---------- 目录 ----------

    private void setupToc() {
        final List<TocEntry> toc = new ArrayList<>();
        for (TocEntry t : book.getToc()) {
            if (t.spineIndex >= 0) toc.add(t);
        }
        if (toc.isEmpty()) {
            for (int i = 0; i < book.getChapterCount(); i++) {
                TocEntry t = new TocEntry();
                t.label = book.getChapterTitle(i);
                t.spineIndex = i;
                t.depth = 0;
                toc.add(t);
            }
        }

        tocList.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return toc.size();
            }

            @Override
            public Object getItem(int position) {
                return toc.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv;
                if (convertView instanceof TextView) {
                    tv = (TextView) convertView;
                } else {
                    tv = new TextView(ReaderActivity.this);
                    tv.setLayoutParams(new ListView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    tv.setTextSize(13.5f);
                    tv.setPadding(dp(20), dp(11), dp(16), dp(11));
                }
                TocEntry t = toc.get(position);
                tv.setText(t.label);
                tv.setPadding(dp(20 + t.depth * 14), dp(11), dp(16), dp(11));

                boolean current = t.spineIndex == reader.getChapterIndex();
                if (current) {
                    tv.setTextColor(0xFF96571F);
                    tv.setBackgroundColor(0x14B87333);
                    tv.setTypeface(null, android.graphics.Typeface.BOLD);
                } else if (t.depth > 0) {
                    tv.setTextColor(0xFF9C9184);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                    tv.setTypeface(null, android.graphics.Typeface.NORMAL);
                } else {
                    tv.setTextColor(0xFF3D352C);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                    tv.setTypeface(null, android.graphics.Typeface.NORMAL);
                }

                tv.setOnClickListener(v -> {
                    if (speechState == 1) {
                        speech.pause();
                        speechState = 2;
                    }
                    lastReadStart = -1;
                    lastReadChapter = -1;
                    reader.clearReadHighlight();
                    reader.openChapter(t.spineIndex, 0);
                    closeDrawer();
                    updateSpeakUi();
                });
                return tv;
            }
        });
    }

    // ---------- 设置面板 ----------

    private void setupSettingsPanel() {
        TextView tvFontSize = findViewById(R.id.tv_font_size);
        tvFontSize.setText(String.valueOf(Math.round(reader.getTextSize())));

        findViewById(R.id.btn_font_minus).setOnClickListener(v -> {
            float s = Math.max(13f, reader.getTextSize() - 1f);
            reader.setTextSize(s);
            tvFontSize.setText(String.valueOf(Math.round(s)));
            saveSettings();
        });
        findViewById(R.id.btn_font_plus).setOnClickListener(v -> {
            float s = Math.min(30f, reader.getTextSize() + 1f);
            reader.setTextSize(s);
            tvFontSize.setText(String.valueOf(Math.round(s)));
            saveSettings();
        });

        findViewById(R.id.sp_tight).setOnClickListener(v -> {
            reader.setLineSpacing(1.4f);
            refreshSegment();
            saveSettings();
        });
        findViewById(R.id.sp_normal).setOnClickListener(v -> {
            reader.setLineSpacing(1.65f);
            refreshSegment();
            saveSettings();
        });
        findViewById(R.id.sp_loose).setOnClickListener(v -> {
            reader.setLineSpacing(1.95f);
            refreshSegment();
            saveSettings();
        });

        findViewById(R.id.font_serif).setOnClickListener(v -> {
            reader.setSerif(true);
            refreshSegment();
            saveSettings();
        });
        findViewById(R.id.font_sans).setOnClickListener(v -> {
            reader.setSerif(false);
            refreshSegment();
            saveSettings();
        });

        findViewById(R.id.theme_paper).setOnClickListener(v -> applyReaderTheme(ReaderView.THEME_PAPER));
        findViewById(R.id.theme_white).setOnClickListener(v -> applyReaderTheme(ReaderView.THEME_WHITE));
        findViewById(R.id.theme_sepia).setOnClickListener(v -> applyReaderTheme(ReaderView.THEME_SEPIA));
        findViewById(R.id.theme_night).setOnClickListener(v -> applyReaderTheme(ReaderView.THEME_NIGHT));

        findViewById(R.id.mg_narrow).setOnClickListener(v -> {
            reader.setMarginLevel(0);
            refreshSegment();
            saveSettings();
        });
        findViewById(R.id.mg_mid).setOnClickListener(v -> {
            reader.setMarginLevel(1);
            refreshSegment();
            saveSettings();
        });
        findViewById(R.id.mg_wide).setOnClickListener(v -> {
            reader.setMarginLevel(2);
            refreshSegment();
            saveSettings();
        });

        findViewById(R.id.tex_none).setOnClickListener(v -> applyTexture(ReaderView.TEX_NONE));
        findViewById(R.id.tex_wood).setOnClickListener(v -> applyTexture(ReaderView.TEX_WOOD));
        findViewById(R.id.tex_linen).setOnClickListener(v -> applyTexture(ReaderView.TEX_LINEN));
        findViewById(R.id.tex_leather).setOnClickListener(v -> applyTexture(ReaderView.TEX_LEATHER));
        findViewById(R.id.tex_marble).setOnClickListener(v -> applyTexture(ReaderView.TEX_MARBLE));
        findViewById(R.id.tex_antique).setOnClickListener(v -> applyTexture(ReaderView.TEX_ANTIQUE));

        findViewById(R.id.turn_fold).setOnClickListener(v -> {
            reader.setTurnMode(ReaderView.TURN_FOLD);
            refreshSegment();
            saveSettings();
        });
        findViewById(R.id.turn_slide).setOnClickListener(v -> {
            reader.setTurnMode(ReaderView.TURN_SLIDE);
            refreshSegment();
            saveSettings();
        });
    }

    private void applyTexture(int t) {
        reader.setTexture(t);
        refreshSegment();
        saveSettings();
    }

    private void refreshSegment() {
        boolean serif = reader.isSerif();
        float ls = reader.getLineSpacing();
        int mg = reader.getMarginLevel();

        styleSeg(R.id.font_serif, serif);
        styleSeg(R.id.font_sans, !serif);
        styleSeg(R.id.sp_tight, ls < 1.5f);
        styleSeg(R.id.sp_normal, ls >= 1.5f && ls < 1.8f);
        styleSeg(R.id.sp_loose, ls >= 1.8f);
        styleSeg(R.id.mg_narrow, mg == 0);
        styleSeg(R.id.mg_mid, mg == 1);
        styleSeg(R.id.mg_wide, mg == 2);

        int th = reader.getTheme();
        styleTheme(R.id.theme_paper, th == ReaderView.THEME_PAPER);
        styleTheme(R.id.theme_white, th == ReaderView.THEME_WHITE);
        styleTheme(R.id.theme_sepia, th == ReaderView.THEME_SEPIA);
        styleTheme(R.id.theme_night, th == ReaderView.THEME_NIGHT);

        int tm = reader.getTurnMode();
        styleSeg(R.id.turn_fold, tm == ReaderView.TURN_FOLD);
        styleSeg(R.id.turn_slide, tm == ReaderView.TURN_SLIDE);

        int tx = reader.getTexture();
        styleSeg(R.id.tex_none, tx == ReaderView.TEX_NONE);
        styleSeg(R.id.tex_wood, tx == ReaderView.TEX_WOOD);
        styleSeg(R.id.tex_linen, tx == ReaderView.TEX_LINEN);
        styleSeg(R.id.tex_leather, tx == ReaderView.TEX_LEATHER);
        styleSeg(R.id.tex_marble, tx == ReaderView.TEX_MARBLE);
        styleSeg(R.id.tex_antique, tx == ReaderView.TEX_ANTIQUE);
    }

    private void styleSeg(int id, boolean active) {
        TextView tv = findViewById(id);
        if (tv == null) return;
        if (active) {
            tv.setBackgroundColor(0x1FB87333);
            tv.setTextColor(0xFF96571F);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            tv.setBackgroundColor(0xFFF0EADF);
            tv.setTextColor(0xFF6B6055);
            tv.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    private void styleTheme(int id, boolean active) {
        TextView tv = findViewById(id);
        if (tv == null) return;
        tv.setAlpha(active ? 1f : 0.72f);
        tv.setScaleX(active ? 1f : 0.94f);
        tv.setScaleY(active ? 1f : 0.94f);
        tv.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void applyReaderTheme(int theme) {
        reader.setTheme(theme);
        refreshSegment();
        applyChromeTheme();
        saveSettings();
    }

    private void applyChromeTheme() {
        int bg = reader.getThemeBg();
        int fg = reader.getThemeFg();
        boolean dark = reader.getTheme() == ReaderView.THEME_NIGHT;

        findViewById(R.id.reader_root).setBackgroundColor(bg);

        int barBg = dark ? 0xF21E1A16 : 0xF2FAF7F2;
        topBar.setBackgroundColor(barBg);
        bottomBar.setBackgroundColor(barBg);

        int sub = dark ? 0xFF8A8075 : 0xFF9C9184;
        int main = dark ? 0xFFE4DCD0 : 0xFF241F1A;

        topTitle.setTextColor(main);
        topChapter.setTextColor(sub);
        tvPage.setTextColor(sub);
        tvPercent.setTextColor(sub);

        // 朗读条跟着主题走
        if (speechBar != null) {
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(17));
            gd.setColor(dark ? 0xF21E1A16 : 0xF7F3EC);
            gd.setStroke(dp(1), dark ? 0x26FFFFFF : 0x1A000000);
            speechBar.setBackground(gd);
            tvSpeechState.setTextColor(dark ? 0xFFB7AC9E : 0xFF6B6055);
            btnSpeechToggle.setTextColor(dark ? 0xFFE8C08A : 0xFF96571F);
            btnSpeechStop.setTextColor(dark ? 0xFF9C9184 : 0xFF8A7E70);
        }

        // 状态栏图标明暗
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View dv = getWindow().getDecorView();
            int flags = dv.getSystemUiVisibility();
            if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            dv.setSystemUiVisibility(flags);
        }
    }

    // ---------- 设置读写 ----------

    private void applySettings() {
        float size = prefs.getFloat("text_size", 19f);
        float ls = prefs.getFloat("line_spacing", 1.65f);
        boolean serif = prefs.getBoolean("serif", true);
        int theme = prefs.getInt("theme", ReaderView.THEME_PAPER);
        int margin = prefs.getInt("margin", 1);
        int turn = prefs.getInt("turn_mode", ReaderView.TURN_FOLD);
        int tex = prefs.getInt("texture", ReaderView.TEX_NONE);

        reader.setTextSize(size);
        reader.setLineSpacing(ls);
        reader.setSerif(serif);
        reader.setTheme(theme);
        reader.setMarginLevel(margin);
        reader.setTurnMode(turn);
        reader.setTexture(tex);

        refreshSegment();
        applyChromeTheme();
    }

    private void saveSettings() {
        prefs.edit()
                .putFloat("text_size", reader.getTextSize())
                .putFloat("line_spacing", reader.getLineSpacing())
                .putBoolean("serif", reader.isSerif())
                .putInt("theme", reader.getTheme())
                .putInt("margin", reader.getMarginLevel())
                .putInt("turn_mode", reader.getTurnMode())
                .putInt("texture", reader.getTexture())
                .apply();
    }

    // ---------- 书签 ----------

    private void loadBookmarks() {
        bookmarks.clear();
        String raw = prefs.getString("bm_" + record.uuid, "");
        if (raw == null || raw.isEmpty()) return;
        for (String line : raw.split("\n")) {
            String[] p = line.split("\\|");
            if (p.length < 4) continue;
            try {
                Bookmark b = new Bookmark();
                b.chapter = Integer.parseInt(p[0]);
                b.page = Integer.parseInt(p[1]);
                b.percent = Float.parseFloat(p[2]);
                b.label = p[3];
                bookmarks.add(b);
            } catch (Throwable ignored) {
            }
        }
        updateBookmarkIcon();
    }

    private void saveBookmarks() {
        StringBuilder sb = new StringBuilder();
        for (Bookmark b : bookmarks) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(b.chapter).append('|').append(b.page).append('|')
                    .append(b.percent).append('|').append(b.label);
        }
        prefs.edit().putString("bm_" + record.uuid, sb.toString()).apply();
    }

    private void toggleBookmark() {
        int ch = reader.getChapterIndex();
        int pg = reader.getPageIndex();
        String key = ch + ":" + pg;

        for (int i = 0; i < bookmarks.size(); i++) {
            if (bookmarks.get(i).key().equals(key)) {
                bookmarks.remove(i);
                saveBookmarks();
                updateBookmarkIcon();
                Toast.makeText(this, "已移除书签", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Bookmark b = new Bookmark();
        b.chapter = ch;
        b.page = pg;
        b.percent = reader.globalPercent();
        b.label = book.getChapterTitle(ch);
        bookmarks.add(b);
        saveBookmarks();
        updateBookmarkIcon();
        Toast.makeText(this, "已添加书签", Toast.LENGTH_SHORT).show();
    }

    private void updateBookmarkIcon() {
        String key = reader.getChapterIndex() + ":" + reader.getPageIndex();
        boolean marked = false;
        for (Bookmark b : bookmarks) {
            if (b.key().equals(key)) {
                marked = true;
                break;
            }
        }
        btnBookmark.setColorFilter(marked ? 0xFFB87333 : 0xFF6B6055);
        btnBookmark.setImageResource(R.drawable.ic_bookmark);
        btnBookmark.setAlpha(marked ? 1f : 0.85f);
    }

    // ---------- 工具条 / 抽屉 ----------

    private void toggleBars() {
        if (barsVisible) hideBars();
        else showBars();
    }

    private void showBars() {
        barsVisible = true;
        hideSpeechBar();          // 工具栏出来了，朗读条先让位，别叠在一起
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);

        float topOff = topBar.getHeight() > 0 ? -topBar.getHeight() : -dp(72);
        float botOff = bottomBar.getHeight() > 0 ? bottomBar.getHeight() : dp(112);

        topBar.setTranslationY(topOff);
        topBar.setAlpha(0f);
        bottomBar.setTranslationY(botOff);
        bottomBar.setAlpha(0f);

        topBar.animate().translationY(0f).alpha(1f).setDuration(240)
                .setInterpolator(new DecelerateInterpolator()).start();
        bottomBar.animate().translationY(0f).alpha(1f).setDuration(240)
                .setInterpolator(new DecelerateInterpolator()).start();

        updateBookmarkIcon();
    }

    private void hideBars() {
        barsVisible = false;
        if (speechState != 0) showSpeechBar();   // 工具栏收起，朗读条回来
        topBar.animate().translationY(-dp(72)).alpha(0f).setDuration(200)
                .withEndAction(() -> topBar.setVisibility(View.GONE)).start();
        bottomBar.animate().translationY(dp(112)).alpha(0f).setDuration(200)
                .withEndAction(() -> bottomBar.setVisibility(View.GONE)).start();
    }

    private void openDrawer() {
        drawerOpen = true;
        scrim.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(220).start();

        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(-drawer.getWidth() == 0 ? -dp(300) : -drawer.getWidth());
        drawer.animate().translationX(0).setDuration(280)
                .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        setupToc();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawer.animate().translationX(-drawer.getWidth()).setDuration(240)
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
        scrim.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
    }

    private void openSheet() {
        sheetOpen = true;
        scrim.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(220).start();

        sheet.setVisibility(View.VISIBLE);
        sheet.post(() -> {
            sheet.setTranslationY(sheet.getHeight());
            sheet.animate().translationY(0).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        });
    }

    private void closeSheet() {
        if (!sheetOpen) return;
        sheetOpen = false;
        sheet.animate().translationY(sheet.getHeight()).setDuration(240)
                .withEndAction(() -> sheet.setVisibility(View.GONE)).start();
        scrim.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- 朗读 ----------

    private void onSpeakButton() {
        if (speechState == 1) {
            speech.pause();
            speechState = 2;
            updateSpeakUi();
            return;
        }
        if (speechState == 2) {
            // 从当前念到的那句重新起，声音和画面始终对得上
            startSpeakingAt(lastReadStart);
            return;
        }
        startSpeakingAt(-1);
    }

    private void initSpeech() {
        speech = new SpeechReader();
        speech.setRate(prefs.getFloat("tts_rate", 1.0f));
        speech.setPitch(VoiceStyle.byId(prefs.getInt("tts_style", 0)).pitch);
        speech.setVoiceName(prefs.getString("tts_voice", ""));
        speech.setListener(new SpeechReader.Listener() {
            @Override
            public void onReady(boolean ok, String message) {
                runOnUiThread(() -> {
                    if (!ok) {
                        pendingSpeak = false;
                        speechState = 0;
                        updateSpeakUi();
                        Toast.makeText(ReaderActivity.this,
                                message == null ? "朗读不可用" : message, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (pendingSpeak) {
                        pendingSpeak = false;
                        startSpeakingAt(pendingOffset);
                    }
                });
            }

            @Override
            public void onSegment(int index, int total, int charOffset, int charLen) {
                runOnUiThread(() -> {
                    if (reader == null) return;
                    lastReadStart = charOffset;
                    lastReadChapter = reader.getChapterIndex();
                    // 一句话同时决定"翻到哪页"和"亮哪句"，两者绑在一起才不会各走各的
                    reader.followReading(charOffset, charOffset + charLen);
                });
            }

            @Override
            public void onDone() {
                runOnUiThread(() -> {
                    int ch = reader.getChapterIndex();
                    if (ch < book.getChapterCount() - 1) {
                        lastReadStart = -1;
                        lastReadChapter = -1;
                        reader.openChapter(ch + 1, 0);
                        startSpeakingAt(0);
                    } else {
                        speechState = 0;
                        lastReadStart = -1;
                        lastReadChapter = -1;
                        reader.clearReadHighlight();
                        updateSpeakUi();
                        Toast.makeText(ReaderActivity.this, "全书读完了", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    speechState = 0;
                    updateSpeakUi();
                    Toast.makeText(ReaderActivity.this,
                            message == null ? "朗读出错" : message, Toast.LENGTH_SHORT).show();
                });
            }
        });
        speech.init(this);
    }

    /**
     * 从 offset 开始念。
     *
     * @param offset 字符位置；传 -1 表示"从当前页开头念"。
     *               越界（比如刚换过章）也自动退回当前页开头，不会念错位。
     */
    private void startSpeakingAt(int offset) {
        String text = reader.getPlainText();
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, "本章没有可朗读的文字", Toast.LENGTH_SHORT).show();
            speechState = 0;
            updateSpeakUi();
            return;
        }
        // 位置失效（越界，或换了章还拿着旧位置）就退回当前页开头
        if (offset < 0 || offset >= text.length()
                || (lastReadChapter >= 0 && lastReadChapter != reader.getChapterIndex())) {
            offset = reader.offsetForPage(reader.getPageIndex());
        }

        if (speech == null) {          // 引擎还没建起来，先记下起点
            pendingSpeak = true;
            pendingOffset = offset;
            initSpeech();
            Toast.makeText(this, "正在准备朗读…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!speech.isReady()) {
            Toast.makeText(this, "朗读引擎还没准备好", Toast.LENGTH_SHORT).show();
            return;
        }

        // 这里不清高亮：长按点亮的那一段要留着当"起点"反馈，
        // 引擎一起播，onSegment 会立刻把它换成正在念的那一句。
        speech.speak(text, offset);
        if (!speech.isSpeaking()) {          // 切不出可念的句子
            speechState = 0;
            reader.clearReadHighlight();
            updateSpeakUi();
            return;
        }
        lastReadStart = offset;
        lastReadChapter = reader.getChapterIndex();
        speechState = 1;
        updateSpeakUi();
    }

    private void stopSpeaking() {
        if (speech != null) speech.stop();
        if (reader != null) reader.clearReadHighlight();
        speechState = 0;
        lastReadStart = -1;
        lastReadChapter = -1;
        pendingSpeak = false;
        updateSpeakUi();
    }

    private void updateSpeakUi() {
        if (tvSpeak != null) {
            tvSpeak.setText(speechState == 1 ? "暂停" : speechState == 2 ? "继续" : "朗读");
        }
        if (speechBar == null) return;

        if (speechState == 0) {
            hideSpeechBar();
            return;
        }
        tvSpeechState.setText(speechState == 1 ? "朗读中" : "已暂停");
        btnSpeechToggle.setText(speechState == 1 ? "暂停" : "继续");
        showSpeechBar();
    }

    private void showSpeechBar() {
        if (speechBar == null || speechBarShown) return;
        if (barsVisible) return;          // 工具栏占着底部，等它收起再出来
        speechBarShown = true;
        speechBar.setVisibility(View.VISIBLE);
        speechBar.setAlpha(0f);
        speechBar.setTranslationY(dp(16));
        speechBar.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void hideSpeechBar() {
        if (speechBar == null || !speechBarShown) return;
        speechBarShown = false;
        speechBar.animate().alpha(0f).translationY(dp(16)).setDuration(150)
                .withEndAction(() -> {
                    if (!speechBarShown) speechBar.setVisibility(View.GONE);
                }).start();
    }

    private void showSpeechOptions() {
        final int[] style = {prefs.getInt("tts_style", 0)};
        final float[] rate = {prefs.getFloat("tts_rate", 1.0f)};
        final String[] voice = {prefs.getString("tts_voice", "")};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(4), dp(20), dp(2));

        // ---- 音色 ----
        root.addView(optionLabel("音色 · 同一个人换不同音高念"));
        final TextView[] styleChips = new TextView[VoiceStyle.ALL.length];
        LinearLayout styleRow = new LinearLayout(this);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < VoiceStyle.ALL.length; i++) {
            final int idx = i;
            TextView chip = makeChip(VoiceStyle.ALL[i].label);
            applyChipStyle(chip, VoiceStyle.ALL[i].id == style[0]);
            chip.setOnClickListener(v -> {
                style[0] = VoiceStyle.ALL[idx].id;
                prefs.edit().putInt("tts_style", style[0]).apply();
                for (int k = 0; k < styleChips.length; k++) {
                    applyChipStyle(styleChips[k], VoiceStyle.ALL[k].id == style[0]);
                }
                applySpeechTone();
            });
            styleChips[i] = chip;
            styleRow.addView(chip);
        }
        root.addView(styleRow);

        // ---- 语速 ----
        root.addView(optionLabel("语速"));
        final float[] rates = {0.8f, 1.0f, 1.2f, 1.5f, 1.8f};
        final TextView[] rateChips = new TextView[rates.length];
        LinearLayout rateRow = new LinearLayout(this);
        rateRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < rates.length; i++) {
            final int idx = i;
            TextView chip = makeChip(rates[i] + "x");
            applyChipStyle(chip, Math.abs(rates[i] - rate[0]) < 0.01f);
            chip.setOnClickListener(v -> {
                rate[0] = rates[idx];
                prefs.edit().putFloat("tts_rate", rate[0]).apply();
                for (int k = 0; k < rateChips.length; k++) {
                    applyChipStyle(rateChips[k], Math.abs(rates[k] - rate[0]) < 0.01f);
                }
                applySpeechTone();
            });
            rateChips[i] = chip;
            rateRow.addView(chip);
        }
        root.addView(rateRow);

        // ---- 语音包（系统里装了多个中文语音时才出现）----
        List<String> voices = speech != null ? speech.getVoiceNames() : null;
        if (voices != null && voices.size() > 1) {
            root.addView(optionLabel("语音包 · 系统里有 " + voices.size() + " 个中文语音"));
            final TextView[] voiceChips = new TextView[voices.size() + 1];
            LinearLayout voiceRow = new LinearLayout(this);
            voiceRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i <= voices.size(); i++) {
                final int idx = i;
                final String name = i == 0 ? "" : voices.get(i - 1);
                TextView chip = makeChip(i == 0 ? "默认" : "音色" + i);
                applyChipStyle(chip, name.equals(voice[0]));
                chip.setOnClickListener(v -> {
                    voice[0] = name;
                    prefs.edit().putString("tts_voice", voice[0]).apply();
                    for (int k = 0; k < voiceChips.length; k++) {
                        String n = k == 0 ? "" : voices.get(k - 1);
                        applyChipStyle(voiceChips[k], n.equals(voice[0]));
                    }
                    applySpeechTone();
                });
                voiceChips[i] = chip;
                voiceRow.addView(chip);
            }
            root.addView(voiceRow);
        }

        new AlertDialog.Builder(this)
                .setTitle("朗读设置")
                .setView(root)
                .setPositiveButton("完成", null)
                .setNeutralButton("停止朗读", (d, w) -> stopSpeaking())
                .show();
    }

    private TextView optionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(0xFF9C9184);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView makeChip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lp.setMarginEnd(dp(5));
        tv.setLayoutParams(lp);
        return tv;
    }

    private void applyChipStyle(TextView tv, boolean active) {
        if (active) {
            tv.setBackgroundColor(0x1FB87333);
            tv.setTextColor(0xFF96571F);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            tv.setBackgroundColor(0xFFF0EADF);
            tv.setTextColor(0xFF6B6055);
            tv.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    /** 音色/语速/语音包改动后立即生效；正在念的话从这一句重起 */
    private void applySpeechTone() {
        if (speech == null) return;
        speech.setRate(prefs.getFloat("tts_rate", 1.0f));
        speech.setPitch(VoiceStyle.byId(prefs.getInt("tts_style", 0)).pitch);
        speech.setVoiceName(prefs.getString("tts_voice", ""));
        if (speechState == 1) {
            speech.pause();
            speech.resume();
        }
    }

    // ---------- ReaderView 回调 ----------

    @Override
    public void onProgress(int chapterIndex, int pageIndex, int pageCount, float percent) {
        tvPage.setText((pageIndex + 1) + " / " + pageCount);
        tvPercent.setText(Math.round(percent * 100) + "%");
        seek.setProgress(Math.round(percent * 1000));
        topChapter.setText(book.getChapterTitle(chapterIndex));
        updateBookmarkIcon();
    }

    @Override
    public void onCenterTap() {
        toggleBars();
    }

    @Override
    public void onBookmarkRequested() {
        toggleBookmark();
    }

    @Override
    public void onUserTurn() {
        // 朗读时用户自己翻页，说明想自己看：先停下来，免得声音和画面对不上
        if (speechState == 1) {
            speech.pause();
            speechState = 2;
            updateSpeakUi();
            Toast.makeText(this, "已暂停朗读，点「继续」从当前页接着读", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onReadFrom(int offset) {
        // 长按正文：就从按住的地方开始念（按句对齐，不会半句起头）
        // 这个位置是当场从当前章排好的版面上算出来的，所以先把它记成当前章的
        lastReadChapter = reader.getChapterIndex();
        startSpeakingAt(offset);
    }

    // ---------- 生命周期 ----------

    private void persist() {
        if (record == null || reader == null) return;
        db.saveProgress(record.uuid, reader.getChapterIndex(),
                reader.getPageIndex(), reader.globalPercent());
    }

    @Override
    protected void onPause() {
        super.onPause();
        persist();
    }

    @Override
    protected void onDestroy() {
        persist();
        if (speech != null) speech.release();
        if (book != null) book.close();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        if (sheetOpen) {
            closeSheet();
            return;
        }
        if (barsVisible) {
            hideBars();
            return;
        }
        persist();
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            reader.nextPage();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            reader.prevPage();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
