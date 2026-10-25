package com.moyue.reader.book;

/**
 * 目录项，EPUB 与 TXT 共用
 */
public class TocEntry {
    public String label = "";
    public String href = "";
    public int depth = 0;
    public int spineIndex = -1;

    public TocEntry() {
    }

    public TocEntry(String label, int chapterIndex) {
        this.label = label;
        this.spineIndex = chapterIndex;
    }
}
