package com.moyue.reader.book;

import android.graphics.Bitmap;

import com.moyue.reader.epub.EpubBook;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * 统一的"书源"抽象。
 * ReaderView / ReaderActivity 只认这个接口，不关心底下是 EPUB 还是 TXT。
 */
public interface BookSource {

    String getTitle();

    String getAuthor();

    /** 章节数 */
    int getChapterCount();

    /** 章节标题（用于页眉、目录） */
    String getChapterTitle(int index);

    /** 章节正文，返回适合 Html.fromHtml 的片段 */
    String getChapterHtml(int index);

    /** 章节内第 index 张图片（占位符 moyue-img:N 对应） */
    Bitmap getChapterImage(int index, int targetWidth);

    /** 封面，没有则返回 null */
    Bitmap getCoverBitmap(int maxWidth);

    /** 目录；空列表表示无目录 */
    List<TocEntry> getToc();

    /** 章节体量权重（用于全书进度百分比），字节或字符数均可 */
    long getChapterWeight(int index);

    void close();

    /** 根据文件内容判断类型并打开 */
    static BookSource open(File f) throws Exception {
        if (isZip(f)) return EpubBook.open(f);
        return TxtBook.open(f);
    }

    static boolean isZip(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] head = new byte[4];
            int n = in.read(head);
            return n >= 4 && (head[0] & 0xFF) == 0x50 && (head[1] & 0xFF) == 0x4B
                    && (head[2] & 0xFF) == 0x03 && (head[3] & 0xFF) == 0x04;
        } catch (Exception e) {
            return false;
        }
    }
}
