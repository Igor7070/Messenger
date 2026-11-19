package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

/**
 * Накопленное в переписке: снимки и видео — плитками, файлы, ссылки и
 * голосовые — строками. Виды собираются кодом: ради двух разметок заводить
 * отдельные файлы не стоит, а правила здесь простые.
 */
public class SharedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final String HOST = "https://unlimitedpossibilities12.org";

    private final Context context;
    private final List<SharedWith.Item> items = new ArrayList<>();
    private String kind = SharedWith.PHOTOS;

    public SharedAdapter(Context context) {
        this.context = context;
    }

    public void show(String kind, List<SharedWith.Item> found) {
        this.kind = kind;
        items.clear();
        if (found != null) items.addAll(found);
        notifyDataSetChanged();
    }

    public boolean isGrid() {
        return SharedWith.PHOTOS.equals(kind) || SharedWith.VIDEOS.equals(kind);
    }

    @Override
    public int getItemViewType(int position) {
        return isGrid() ? 0 : 1;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        float density = context.getResources().getDisplayMetrics().density;
        if (viewType == 0) {
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0xFF16213A);
            return new TileHolder(image);
        }

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (12 * density);
        row.setPadding(pad, pad, pad, pad);
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(context);
        name.setTextColor(0xFFE2E8F0);
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(name, nameParams);

        TextView when = new TextView(context);
        when.setTextColor(0xFF64748B);
        when.setTextSize(13);
        row.addView(when);

        return new RowHolder(row, name, when);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        SharedWith.Item item = items.get(position);
        String address = item.url == null ? "" : item.url;
        boolean link = SharedWith.LINKS.equals(kind);
        String full = link || address.startsWith("http") ? address : HOST + address;

        if (holder instanceof TileHolder) {
            ImageView image = (ImageView) holder.itemView;
            int side = holder.itemView.getResources().getDisplayMetrics().widthPixels / 3
                    - (int) (8 * holder.itemView.getResources().getDisplayMetrics().density);
            image.setLayoutParams(new RecyclerView.LayoutParams(side, side));
            Glide.with(context).load(full).centerCrop().into(image);
            image.setOnClickListener(v -> {
                Intent intent = new Intent(context, FullScreenImageActivity.class);
                intent.putExtra("imageUrl", full);
                context.startActivity(intent);
            });
            return;
        }

        RowHolder row = (RowHolder) holder;
        row.name.setText(item.name);
        row.name.setTextColor(link ? 0xFFA5B4FC : 0xFFE2E8F0);
        row.when.setText(dayLabel(item.timestamp));
        row.itemView.setOnClickListener(v -> {
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(full)));
            } catch (Exception ignored) {
                // открыть нечем — молчим, падать из-за этого не стоит
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** «13 November» — та же подпись, что в веб-клиенте */
    private String dayLabel(String timestamp) {
        if (timestamp == null || timestamp.length() < 10) return "";
        try {
            java.time.LocalDate date = java.time.LocalDate.parse(timestamp.substring(0, 10));
            return date.getDayOfMonth() + " " + date.getMonth()
                    .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
        } catch (Exception ignored) {
            return "";
        }
    }

    static class TileHolder extends RecyclerView.ViewHolder {
        TileHolder(View view) {
            super(view);
        }
    }

    static class RowHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView when;

        RowHolder(View view, TextView name, TextView when) {
            super(view);
            this.name = name;
            this.when = when;
        }
    }
}
