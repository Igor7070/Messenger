package com.example.unlimitedpossibilities12.messenger.language;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.unlimitedpossibilities12.R;

import java.util.List;

public class LanguageAdapter extends ArrayAdapter<Language> {

    public LanguageAdapter(Context context, int resource, List<Language> objects) {
        super(context, resource, objects);
    }

    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.spinner_item_translate, parent, false);
        }
        TextView textView = convertView.findViewById(android.R.id.text1);
        Language language = getItem(position);

        textView.setText(language.toString());
        textView.setTextColor(getContext().getResources().getColor(R.color.translate_text_primary));
        textView.setTextSize(18);

        return convertView;
    }

    @Override
    public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.spinner_dropdown_item_translate, parent, false);
        }
        TextView textView = convertView.findViewById(android.R.id.text1);
        Language language = getItem(position);

        textView.setText(language.toString());
        textView.setTextColor(getContext().getResources().getColor(R.color.translate_text_primary));
        textView.setTextSize(18);
        textView.setPadding(48, 24, 48, 24);

        // Фон строки прозрачный: непрозрачный цвет перекрывал скруглённые углы
        // подложки списка (popupBackground). Тёмный фон даёт сама подложка.
        android.util.TypedValue tv = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        convertView.setBackgroundResource(tv.resourceId);

        return convertView;
    }

    public String getLanguageCode(int position) {
        if (position >= 0 && position < getCount()) {
            return getItem(position).code;
        }
        return "auto";
    }
}