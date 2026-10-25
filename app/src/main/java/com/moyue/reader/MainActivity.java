package com.moyue.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.moyue.reader.db.LibraryDb;
import com.moyue.reader.book.BookSource;
import com.moyue.reader.model.BookRecord;
import com.moyue.reader.ui.BookAdapter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;

    private GridView grid;
    private BookAdapter adapter;
    private LibraryDb db;
    private View emptyState;
    private TextView libSub;
    private AlertDialog progressDlg;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int sortMode = 0; // 0 最近阅读 1 添加时间 2 书名

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new LibraryDb(this);
        grid = findViewById(R.id.grid);
        emptyState = findViewById(R.id.empty_state);
        libSub = findViewById(R.id.lib_sub);

        adapter = new BookAdapter(this);
        grid.setAdapter(adapter);

        grid.setOnItemClickListener((parent, view, position, id) -> openBook(adapter.getItem(position)));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            showBookMenu(adapter.getItem(position));
            return true;
        });

        View.OnClickListener importClick = v -> pickFiles();
        findViewById(R.id.btn_import).setOnClickListener(importClick);
        findViewById(R.id.fab).setOnClickListener(importClick);
        findViewById(R.id.btn_import_empty).setOnClickListener(importClick);
        findViewById(R.id.btn_sort).setOnClickListener(v -> showSortMenu());

        handleViewIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleViewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadBooks();
    }

    private void handleViewIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            List<Uri> one = new ArrayList<>();
            one.add(intent.getData());
            importUris(one);
        }
    }

    // ---------- 书库 ----------

    private void loadBooks() {
        List<BookRecord> list;
        switch (sortMode) {
            case 1:
                list = db.query("added_at DESC");
                break;
            case 2:
                list = db.query("title COLLATE NOCASE ASC");
                break;
            default:
                list = db.query("last_read_at DESC, added_at DESC");
                break;
        }
        adapter.setData(list);
        emptyState.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        libSub.setText(list.isEmpty() ? "书架空空如也" : (list.size() + " 本书 · 本地存储"));
    }

    private void showSortMenu() {
        String[] items = {"最近阅读", "导入时间", "书名"};
        new AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(items, sortMode, (d, which) -> {
                    sortMode = which;
                    loadBooks();
                    d.dismiss();
                })
                .show();
    }

    private void showBookMenu(BookRecord b) {
        String[] items = {"开始阅读", "修改书名", "书籍信息", "从书架移除"};
        new AlertDialog.Builder(this)
                .setTitle(b.title)
                .setItems(items, (d, which) -> {
                    if (which == 0) openBook(b);
                    else if (which == 1) renameBook(b);
                    else if (which == 2) showInfo(b);
                    else confirmDelete(b);
                })
                .show();
    }

    /** 网上下载的书文件名经常很乱，让用户自己起个名字 */
    private void renameBook(BookRecord b) {
        final EditText et = new EditText(this);
        et.setText(b.title);
        et.setSingleLine(true);
        et.setSelection(et.getText().length());
        et.setHint("书名");
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);

        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * d);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, Math.round(8 * d), pad, 0);
        wrap.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("修改书名")
                .setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dlg.show();

        // 自己接管点击，书名不合法时不关闭对话框
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = et.getText().toString().trim();
            if (t.isEmpty()) {
                toast("书名不能为空");
                return;
            }
            if (t.length() > 60) t = t.substring(0, 60);
            b.title = t;
            db.updateTitle(b.uuid, t);
            adapter.clearCache();
            loadBooks();
            dlg.dismiss();
            toast("已改名");
        });
    }

    private void showInfo(BookRecord b) {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：").append(b.title).append('\n');
        sb.append("作者：").append(b.author == null || b.author.isEmpty() ? "未知" : b.author).append('\n');
        sb.append("章节：").append(b.chapterCount).append(" 章\n");
        sb.append("大小：").append(String.format("%.1f MB", b.fileSize / 1024.0 / 1024.0)).append('\n');
        sb.append("进度：").append(Math.round(b.progress * 100)).append("%\n");
        sb.append("导入：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date(b.addedAt)));
        new AlertDialog.Builder(this)
                .setTitle("书籍信息")
                .setMessage(sb.toString())
                .setPositiveButton("好", null)
                .show();
    }

    private void confirmDelete(BookRecord b) {
        new AlertDialog.Builder(this)
                .setTitle("移除这本书？")
                .setMessage("《" + b.title + "》将从书架删除，本地文件也会一并清除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (d, w) -> {
                    db.delete(b.uuid);
                    deleteQuietly(new File(new File(getFilesDir(), "books"), b.fileName));
                    if (b.coverFile != null && !b.coverFile.isEmpty()) {
                        deleteQuietly(new File(new File(getFilesDir(), "covers"), b.coverFile));
                    }
                    adapter.clearCache();
                    loadBooks();
                    toast("已移除");
                })
                .show();
    }

    private void openBook(BookRecord b) {
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra(ReaderActivity.EXTRA_UUID, b.uuid);
        startActivity(i);
    }

    // ---------- 导入 ----------

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/epub+zip",
                "text/plain",
                "application/octet-stream",
                "application/zip",
                "application/x-zip-compressed"
        });
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            toast("没有找到文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) importUris(uris);
    }

    private void importUris(List<Uri> uris) {
        showBusy("正在导入…");
        new Thread(() -> {
            int ok = 0, dup = 0, fail = 0;
            for (Uri uri : uris) {
                try {
                    int r = importOne(uri);
                    if (r == 1) ok++;
                    else if (r == 2) dup++;
                    else fail++;
                } catch (Throwable t) {
                    fail++;
                }
            }
            final int fok = ok, fdup = dup, ffail = fail;
            ui.post(() -> {
                hideBusy();
                loadBooks();
                StringBuilder msg = new StringBuilder();
                if (fok > 0) msg.append("导入 ").append(fok).append(" 本");
                if (fdup > 0) msg.append(msg.length() > 0 ? "，" : "").append(fdup).append(" 本已存在");
                if (ffail > 0) msg.append(msg.length() > 0 ? "，" : "").append(ffail).append(" 本失败");
                toast(msg.length() == 0 ? "没有导入任何文件" : msg.toString());
            });
        }).start();
    }

    /** @return 1 成功 2 重复 0 失败 */
    private int importOne(Uri uri) throws Exception {
        File booksDir = new File(getFilesDir(), "books");
        if (!booksDir.exists()) booksDir.mkdirs();

        String uuid = UUID.randomUUID().toString();
        File tmp = new File(booksDir, uuid + ".tmp");

        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new Exception("无法读取文件");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }

        BookSource book;
        boolean isZip;
        try {
            isZip = BookSource.isZip(tmp);
            book = BookSource.open(tmp);
        } catch (Throwable t) {
            deleteQuietly(tmp);
            throw t;
        }

        // 按实际格式决定落盘扩展名
        String ext = isZip ? ".epub" : ".txt";
        File dest = new File(booksDir, uuid + ext);
        if (!tmp.renameTo(dest)) {
            try (InputStream in = new FileInputStream(tmp);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            deleteQuietly(tmp);
        }

        String title = book.getTitle();
        String author = book.getAuthor();
        if (title == null || title.trim().isEmpty() || "未命名".equals(title)) {
            String name = queryDisplayName(uri);
            if (name != null) title = name.replaceAll("(?i)\\.(epub|txt|text)$", "");
        }

        // 去重：同名同大小
        List<BookRecord> existing = db.all();
        for (BookRecord e : existing) {
            if (title != null && title.equals(e.title) && Math.abs(e.fileSize - dest.length()) < 4096) {
                book.close();
                deleteQuietly(dest);
                return 2;
            }
        }

        BookRecord r = new BookRecord();
        r.uuid = uuid;
        r.title = title;
        r.author = author;
        r.fileName = uuid + ext;
        r.fileSize = dest.length();
        r.addedAt = System.currentTimeMillis();
        r.lastReadAt = 0;
        r.progress = 0;
        r.chapterIndex = 0;
        r.pageIndex = 0;
        r.chapterCount = book.getChapterCount();

        try {
            Bitmap cover = book.getCoverBitmap(560);
            if (cover != null) {
                File coversDir = new File(getFilesDir(), "covers");
                if (!coversDir.exists()) coversDir.mkdirs();
                String cf = uuid + ".jpg";
                File cfFile = new File(coversDir, cf);
                try (FileOutputStream fos = new FileOutputStream(cfFile)) {
                    cover.compress(Bitmap.CompressFormat.JPEG, 88, fos);
                }
                r.coverFile = cf;
                cover.recycle();
            }
        } catch (Throwable ignored) {
        }

        book.close();
        db.insert(r);
        return 1;
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    // ---------- UI 小工具 ----------

    private void toast(String msg) {
        TextView tv = findViewById(R.id.toast);
        tv.setText(msg);
        tv.setVisibility(View.VISIBLE);
        tv.animate().cancel();
        tv.setAlpha(0f);
        tv.animate().alpha(1f).setDuration(160).start();
        ui.postDelayed(() -> tv.animate().alpha(0f).setDuration(260)
                .withEndAction(() -> tv.setVisibility(View.GONE)).start(), 2200);
    }

    private void showBusy(String msg) {
        if (progressDlg != null) return;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        int p = (int) (28 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p, p, p);

        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        box.addView(pb);

        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextSize(13f);
        tv.setPadding(0, (int) (14 * getResources().getDisplayMetrics().density), 0, 0);
        tv.setGravity(android.view.Gravity.CENTER);
        box.addView(tv);

        AlertDialog d = new AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(false)
                .create();
        d.show();
        progressDlg = d;
    }

    private void hideBusy() {
        if (progressDlg != null) {
            try {
                progressDlg.dismiss();
            } catch (Throwable ignored) {
            }
            progressDlg = null;
        }
    }
}
