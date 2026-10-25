package com.moyue.reader.model;

/**
 * 书库中的一本书
 */
public class BookRecord {
    public long id;
    public String uuid;
    public String title;
    public String author;
    public String fileName;      // 内部存储中的 epub 文件名
    public String coverFile;     // 封面图片文件名，可为空
    public long fileSize;
    public long addedAt;
    public long lastReadAt;
    public float progress;       // 0..1
    public int chapterIndex;
    public int pageIndex;
    public int chapterCount;

    public boolean isFinished() {
        return progress >= 0.995f;
    }

    public boolean isStarted() {
        return progress > 0.001f;
    }
}
