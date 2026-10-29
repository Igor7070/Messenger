package com.example.unlimitedpossibilities12.messenger.popupMenu;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

public class PopupMenuAdapter extends ArrayAdapter<PopupMenuItem> {

    private final Context context;

    public PopupMenuAdapter(Context context, List<PopupMenuItem> items) {
        super(context, 0, items);
        this.context = context;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false);
        }

        PopupMenuItem item = getItem(position);
        if (item == null) return convertView;

        TextView textView = (TextView) convertView;

        textView.setText(item.title);
        textView.setTextColor(item.textColor);
        textView.setTextSize(16f);
        textView.setPadding(24, 16, 24, 16);

        if (item.icon != null) {
            textView.setCompoundDrawablesWithIntrinsicBounds(item.icon, null, null, null);
            textView.setCompoundDrawablePadding(16);
        } else {
            textView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        }

        return convertView;
    }
}
