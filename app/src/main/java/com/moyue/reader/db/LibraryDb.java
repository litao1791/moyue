package com.moyue.reader.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.moyue.reader.model.BookRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 书库数据库
 */
public class LibraryDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "moyue.db";
    private static final int DB_VER = 1;
    private static final String T = "books";

    public LibraryDb(Context ctx) {
        super(ctx, DB_NAME, null, DB_VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "uuid TEXT UNIQUE,"
                + "title TEXT,"
                + "author TEXT,"
                + "file_name TEXT,"
                + "cover_file TEXT,"
                + "file_size INTEGER DEFAULT 0,"
                + "added_at INTEGER DEFAULT 0,"
                + "last_read_at INTEGER DEFAULT 0,"
                + "progress REAL DEFAULT 0,"
                + "chapter_index INTEGER DEFAULT 0,"
                + "page_index INTEGER DEFAULT 0,"
                + "chapter_count INTEGER DEFAULT 0"
                + ")");
        db.execSQL("CREATE INDEX idx_last_read ON " + T + "(last_read_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 后续版本迁移
    }

    public long insert(BookRecord r) {
        ContentValues v = toValues(r);
        long id = getWritableDatabase().insert(T, null, v);
        r.id = id;
        return id;
    }

    public void update(BookRecord r) {
        getWritableDatabase().update(T, toValues(r), "uuid=?", new String[]{r.uuid});
    }

    public void delete(String uuid) {
        getWritableDatabase().delete(T, "uuid=?", new String[]{uuid});
    }

    public BookRecord findByUuid(String uuid) {
        Cursor c = getReadableDatabase().query(T, null, "uuid=?", new String[]{uuid}, null, null, null);
        try {
            return c.moveToFirst() ? fromCursor(c) : null;
        } finally {
            c.close();
        }
    }

    public List<BookRecord> all() {
        return query("last_read_at DESC, added_at DESC");
    }

    public List<BookRecord> query(String orderBy) {
        List<BookRecord> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T, null, null, null, null, null, orderBy);
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** 只改书名，不碰进度等其它字段 */
    public void updateTitle(String uuid, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        getWritableDatabase().update(T, v, "uuid=?", new String[]{uuid});
    }

    public void saveProgress(String uuid, int chapterIndex, int pageIndex, float progress) {        ContentValues v = new ContentValues();
        v.put("chapter_index", chapterIndex);
        v.put("page_index", pageIndex);
        v.put("progress", progress);
        v.put("last_read_at", System.currentTimeMillis());
        getWritableDatabase().update(T, v, "uuid=?", new String[]{uuid});
    }

    private ContentValues toValues(BookRecord r) {
        ContentValues v = new ContentValues();
        v.put("uuid", r.uuid);
        v.put("title", r.title);
        v.put("author", r.author);
        v.put("file_name", r.fileName);
        v.put("cover_file", r.coverFile);
        v.put("file_size", r.fileSize);
        v.put("added_at", r.addedAt);
        v.put("last_read_at", r.lastReadAt);
        v.put("progress", r.progress);
        v.put("chapter_index", r.chapterIndex);
        v.put("page_index", r.pageIndex);
        v.put("chapter_count", r.chapterCount);
        return v;
    }

    private BookRecord fromCursor(Cursor c) {
        BookRecord r = new BookRecord();
        r.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        r.uuid = c.getString(c.getColumnIndexOrThrow("uuid"));
        r.title = c.getString(c.getColumnIndexOrThrow("title"));
        r.author = c.getString(c.getColumnIndexOrThrow("author"));
        r.fileName = c.getString(c.getColumnIndexOrThrow("file_name"));
        r.coverFile = c.getString(c.getColumnIndexOrThrow("cover_file"));
        r.fileSize = c.getLong(c.getColumnIndexOrThrow("file_size"));
        r.addedAt = c.getLong(c.getColumnIndexOrThrow("added_at"));
        r.lastReadAt = c.getLong(c.getColumnIndexOrThrow("last_read_at"));
        r.progress = c.getFloat(c.getColumnIndexOrThrow("progress"));
        r.chapterIndex = c.getInt(c.getColumnIndexOrThrow("chapter_index"));
        r.pageIndex = c.getInt(c.getColumnIndexOrThrow("page_index"));
        r.chapterCount = c.getInt(c.getColumnIndexOrThrow("chapter_count"));
        return r;
    }
}
