package com.moyue.reader.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.moyue.reader.R;
import com.moyue.reader.model.BookRecord;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BookAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<BookRecord> data = new ArrayList<>();
    private final android.util.LruCache<String, Bitmap> cache =
            new android.util.LruCache<String, Bitmap>(24) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return Math.max(1, value.getByteCount() / 1024);
                }
            };

    public BookAdapter(Context c) {
        this.ctx = c;
    }

    public void setData(List<BookRecord> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public BookRecord getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return data.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.item_book, parent, false);
            final CoverFrame cf = v.findViewById(R.id.cover_frame);
            cf.setClipToOutline(true);
            cf.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    float r = 7f * view.getResources().getDisplayMetrics().density;
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
        }

        BookRecord b = data.get(position);
        ImageView img = v.findViewById(R.id.cover_img);
        LinearLayout fallback = v.findViewById(R.id.cover_fallback);
        TextView cfTitle = v.findViewById(R.id.cf_title);
        TextView cfAuthor = v.findViewById(R.id.cf_author);
        TextView title = v.findViewById(R.id.book_title);
        TextView author = v.findViewById(R.id.book_author);
        ProgressBar progress = v.findViewById(R.id.book_progress);
        TextView badge = v.findViewById(R.id.cover_badge);

        title.setText(b.title == null || b.title.isEmpty() ? "未命名" : b.title);
        author.setText(b.author == null || b.author.isEmpty() ? "未知作者" : b.author);

        Bitmap bmp = loadCover(b);
        if (bmp != null) {
            img.setImageBitmap(bmp);
            img.setVisibility(View.VISIBLE);
            fallback.setVisibility(View.GONE);
        } else {
            img.setImageDrawable(null);
            img.setVisibility(View.GONE);
            fallback.setVisibility(View.VISIBLE);
            cfTitle.setText(b.title == null || b.title.isEmpty() ? "未命名" : b.title);
            cfAuthor.setText(b.author == null || b.author.isEmpty() ? "未知作者" : b.author);
        }

        progress.setProgress(Math.round(b.progress * 1000));

        if (b.isFinished()) {
            badge.setVisibility(View.VISIBLE);
            badge.setText("已读完");
        } else if (b.progress > 0.005f) {
            badge.setVisibility(View.VISIBLE);
            badge.setText(Math.round(b.progress * 100) + "%");
        } else {
            badge.setVisibility(View.GONE);
        }

        return v;
    }

    private Bitmap loadCover(BookRecord b) {
        if (b.coverFile == null || b.coverFile.isEmpty()) return null;
        Bitmap cached = cache.get(b.coverFile);
        if (cached != null) return cached;

        File f = new File(new File(ctx.getFilesDir(), "covers"), b.coverFile);
        if (!f.exists()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (bmp != null) cache.put(b.coverFile, bmp);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    public void clearCache() {
        cache.evictAll();
    }
}
