package com.shiyin.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shiyin.app.model.Chapter;
import com.shiyin.app.util.Ui;

import java.util.ArrayList;
import java.util.List;

/** 章节列表适配器。章节可能上千条，必须回收。 */
public class ChapterAdapter extends BaseAdapter {

    public interface OnPick {
        void onPick(Chapter chapter, int position);
    }

    private final Context ctx;
    private final List<Chapter> data = new ArrayList<>();
    private final OnPick onPick;
    private int playingIndex = -1;
    private boolean pickingEnabled = true;

    public ChapterAdapter(Context ctx, OnPick onPick) {
        this.ctx = ctx;
        this.onPick = onPick;
    }

    public void set(List<Chapter> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public List<Chapter> data() {
        return data;
    }

    public void setPlayingIndex(int i) {
        if (playingIndex == i) return;
        playingIndex = i;
        notifyDataSetChanged();
    }

    public void setPickingEnabled(boolean enabled) {
        if (pickingEnabled == enabled) return;
        pickingEnabled = enabled;
        notifyDataSetChanged();
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        return pickingEnabled;
    }

    public int playingIndex() {
        return playingIndex;
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Chapter getItem(int i) {
        return data.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    private static final class Holder {
        TextView no;
        TextView title;
        LinearLayout root;
    }

    @Override
    public View getView(final int i, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padH = Ui.dp(ctx, 16);
            int padV = Ui.dp(ctx, 13);
            row.setPadding(padH, padV, padH, padV);

            TextView no = Ui.text(ctx, "", 11.5f, Ui.DIM2);
            no.setGravity(Gravity.CENTER);
            no.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(ctx, 34),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(no);

            TextView title = Ui.text(ctx, "", 14f, Ui.TEXT);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = Ui.dp(ctx, 8);
            title.setLayoutParams(tlp);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(title);

            h = new Holder();
            h.no = no;
            h.title = title;
            h.root = row;
            row.setTag(h);
            convertView = row;
        } else {
            h = (Holder) convertView.getTag();
        }

        final Chapter c = data.get(i);
        h.no.setText(String.valueOf((long) c.index + 1));
        h.title.setText(c.title);
        h.root.setEnabled(pickingEnabled);

        boolean playing = c.index == playingIndex;
        h.title.setTextColor(playing ? Ui.AMBER : Ui.TEXT);
        h.title.setTypeface(playing ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        h.no.setTextColor(playing ? Ui.AMBER : Ui.DIM2);
        h.root.setBackground(Widgets.ripple(playing ? 0xFF1A1408 : Ui.BG));
        h.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pickingEnabled && onPick != null && i < data.size() && data.get(i) == c) {
                    onPick.onPick(c, i);
                }
            }
        });
        return convertView;
    }
}
