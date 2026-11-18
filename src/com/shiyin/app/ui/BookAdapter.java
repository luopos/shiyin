package com.shiyin.app.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import com.shiyin.app.model.Book;

import java.util.ArrayList;
import java.util.List;

/** 书列表适配器。列表都不长（一页 20 本），不做回收也不卡。 */
public class BookAdapter extends BaseAdapter {

    public interface OnPick {
        void onPick(Book book, int position);
    }

    private final Context ctx;
    private final List<Book> data = new ArrayList<>();
    private final OnPick onPick;

    public BookAdapter(Context ctx, OnPick onPick) {
        this.ctx = ctx;
        this.onPick = onPick;
    }

    public void set(List<Book> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public void addAll(List<Book> list) {
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public List<Book> data() {
        return data;
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Book getItem(int i) {
        return data.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    @Override
    public View getView(final int i, View convertView, ViewGroup parent) {
        final Book b = data.get(i);
        return Widgets.bookRow(ctx, b, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onPick != null) onPick.onPick(b, i);
            }
        });
    }
}
