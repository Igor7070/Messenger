package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.example.unlimitedpossibilities12.R;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TranslationSettingsFragment extends DialogFragment {
    public enum TranslationMode {
        ALL, NEW_ONLY, SELECTIVELY
    }
    private OnTranslationSettingsListener listener;

    public interface OnTranslationSettingsListener {
        void onTranslationSettingsChanged(boolean enabled, String language, TranslationMode mode);
    }

    public TranslationSettingsFragment(OnTranslationSettingsListener listener) {
        this.listener = listener;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_translation_settings, container, false);

        view.setAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.dialog_fade_in_translate));

        CheckBox enableTranslation = view.findViewById(R.id.enable_translation1);
        RadioGroup translationModeGroup = view.findViewById(R.id.translation_mode_group);
        RadioButton translateAllRadio = view.findViewById(R.id.translate_all);
        RadioButton translateNewOnlyRadio = view.findViewById(R.id.translate_new_only);
        RadioButton translateSelectivelyRadio = view.findViewById(R.id.translate_selectively);
        Spinner languageSpinner = view.findViewById(R.id.language_spinner1);
        MaterialButton saveButton = view.findViewById(R.id.save_button1);
        MaterialButton cancelButton = view.findViewById(R.id.cancel_button);

        // Список языков с флагами
        List<Language> languages = getLanguageList();
        LanguageAdapter adapter = new LanguageAdapter(requireContext(), R.layout.spinner_item_translate, languages);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_translate);
        languageSpinner.setAdapter(adapter);
        SpinnerStyle.roundDropdown(languageSpinner, 320); // скруглённые углы, список не выше 320dp

        SharedPreferences prefs = requireContext().getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
        long chatId = getArguments() != null ? getArguments().getLong("chatId", -1) : -1;
        boolean isTranslationEnabled = prefs.getBoolean("translation_enabled_" + chatId, false);
        String savedLanguage = prefs.getString("translation_language_" + chatId, "auto");
        TranslationMode translationMode = TranslationMode.valueOf(prefs.getString("translation_mode_" + chatId, TranslationMode.ALL.name()));

        enableTranslation.setChecked(isTranslationEnabled);
        languageSpinner.setSelection(getIndex(languages, savedLanguage));
        if (translationMode == TranslationMode.ALL) {
            translateAllRadio.setChecked(true);
        } else if (translationMode == TranslationMode.NEW_ONLY) {
            translateNewOnlyRadio.setChecked(true);
        } else {
            translateSelectivelyRadio.setChecked(true);
        }

        saveButton.setOnClickListener(v -> {
            boolean enabled = enableTranslation.isChecked();
            String language = languages.get(languageSpinner.getSelectedItemPosition()).code;
            TranslationMode mode = translateAllRadio.isChecked() ? TranslationMode.ALL :
                    translateNewOnlyRadio.isChecked() ? TranslationMode.NEW_ONLY : TranslationMode.SELECTIVELY;
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("translation_enabled_" + chatId, enabled);
            editor.putString("translation_language_" + chatId, language);
            editor.putString("translation_mode_" + chatId, mode.name());
            editor.apply();
            listener.onTranslationSettingsChanged(enabled, language, mode);
            dismiss();
        });

        cancelButton.setOnClickListener(v -> dismiss());

        return view;
    }

    private static class Language {
        String name;
        String code;
        String flag;

        Language(String name, String code, String flag) {
            this.name = name;
            this.code = code;
            this.flag = flag;
        }

        @Override
        public String toString() {
            return flag + " " + name;
        }
    }

    private List<Language> getLanguageList() {
        List<Language> languages = new ArrayList<>();
        // Добавляем "Auto" первым
        languages.add(new Language("Auto", "auto", "🌐"));

        // Список остальных языков
        List<Language> otherLanguages = new ArrayList<>();
        otherLanguages.add(new Language("Afrikaans", "af", "🇿🇦"));
        otherLanguages.add(new Language("Albanian", "sq", "🇦🇱"));
        otherLanguages.add(new Language("Amharic", "am", "🇪🇹"));
        otherLanguages.add(new Language("Arabic", "ar", "🇸🇦"));
        otherLanguages.add(new Language("Armenian", "hy", "🇦🇲"));
        otherLanguages.add(new Language("Azerbaijani", "az", "🇦🇿"));
        otherLanguages.add(new Language("Basque", "eu", "🇪🇸"));
        otherLanguages.add(new Language("Belarusian", "be", "🇧🇾"));
        otherLanguages.add(new Language("Bengali", "bn", "🇧🇩"));
        otherLanguages.add(new Language("Bosnian", "bs", "🇧🇦"));
        otherLanguages.add(new Language("Bulgarian", "bg", "🇧🇬"));
        otherLanguages.add(new Language("Catalan", "ca", "🇪🇸"));
        otherLanguages.add(new Language("Cebuano", "ceb", "🇵🇭"));
        otherLanguages.add(new Language("Chichewa", "ny", "🇲🇼"));
        otherLanguages.add(new Language("Chinese (Simplified)", "zh", "🇨🇳"));
        otherLanguages.add(new Language("Chinese (Traditional)", "zh-TW", "🇹🇼"));
        otherLanguages.add(new Language("Corsican", "co", "🇫🇷"));
        otherLanguages.add(new Language("Croatian", "hr", "🇭🇷"));
        otherLanguages.add(new Language("Czech", "cs", "🇨🇿"));
        otherLanguages.add(new Language("Danish", "da", "🇩🇰"));
        otherLanguages.add(new Language("Dutch", "nl", "🇳🇱"));
        otherLanguages.add(new Language("English", "en", "🇬🇧"));
        otherLanguages.add(new Language("Esperanto", "eo", "🏳️"));
        otherLanguages.add(new Language("Estonian", "et", "🇪🇪"));
        otherLanguages.add(new Language("Filipino", "tl", "🇵🇭"));
        otherLanguages.add(new Language("Finnish", "fi", "🇫🇮"));
        otherLanguages.add(new Language("French", "fr", "🇫🇷"));
        otherLanguages.add(new Language("Frisian", "fy", "🇳🇱"));
        otherLanguages.add(new Language("Galician", "gl", "🇪🇸"));
        otherLanguages.add(new Language("Georgian", "ka", "🇬🇪"));
        otherLanguages.add(new Language("German", "de", "🇩🇪"));
        otherLanguages.add(new Language("Greek", "el", "🇬🇷"));
        otherLanguages.add(new Language("Gujarati", "gu", "🇮🇳"));
        otherLanguages.add(new Language("Haitian Creole", "ht", "🇭🇹"));
        otherLanguages.add(new Language("Hausa", "ha", "🇳🇬"));
        otherLanguages.add(new Language("Hawaiian", "haw", "🇺🇸"));
        otherLanguages.add(new Language("Hebrew", "he", "🇮🇱"));
        otherLanguages.add(new Language("Hindi", "hi", "🇮🇳"));
        otherLanguages.add(new Language("Hmong", "hmn", "🇨🇳"));
        otherLanguages.add(new Language("Hungarian", "hu", "🇭🇺"));
        otherLanguages.add(new Language("Icelandic", "is", "🇮🇸"));
        otherLanguages.add(new Language("Igbo", "ig", "🇳🇬"));
        otherLanguages.add(new Language("Indonesian", "id", "🇮🇩"));
        otherLanguages.add(new Language("Irish", "ga", "🇮🇪"));
        otherLanguages.add(new Language("Italian", "it", "🇮🇹"));
        otherLanguages.add(new Language("Japanese", "ja", "🇯🇵"));
        otherLanguages.add(new Language("Javanese", "jv", "🇮🇩"));
        otherLanguages.add(new Language("Kannada", "kn", "🇮🇳"));
        otherLanguages.add(new Language("Kazakh", "kk", "🇰🇿"));
        otherLanguages.add(new Language("Khmer", "km", "🇰🇭"));
        otherLanguages.add(new Language("Kinyarwanda", "rw", "🇷🇼"));
        otherLanguages.add(new Language("Korean", "ko", "🇰🇷"));
        otherLanguages.add(new Language("Kurdish", "ku", "🇮🇶"));
        otherLanguages.add(new Language("Kyrgyz", "ky", "🇰🇬"));
        otherLanguages.add(new Language("Lao", "lo", "🇱🇦"));
        otherLanguages.add(new Language("Latin", "la", "🏳️"));
        otherLanguages.add(new Language("Latvian", "lv", "🇱🇻"));
        otherLanguages.add(new Language("Lithuanian", "lt", "🇱🇹"));
        otherLanguages.add(new Language("Luxembourgish", "lb", "🇱🇺"));
        otherLanguages.add(new Language("Macedonian", "mk", "🇲🇰"));
        otherLanguages.add(new Language("Malagasy", "mg", "🇲🇬"));
        otherLanguages.add(new Language("Malay", "ms", "🇲🇾"));
        otherLanguages.add(new Language("Malayalam", "ml", "🇮🇳"));
        otherLanguages.add(new Language("Maltese", "mt", "🇲🇹"));
        otherLanguages.add(new Language("Maori", "mi", "🇳🇿"));
        otherLanguages.add(new Language("Marathi", "mr", "🇮🇳"));
        otherLanguages.add(new Language("Mongolian", "mn", "🇲🇳"));
        otherLanguages.add(new Language("Myanmar (Burmese)", "my", "🇲🇲"));
        otherLanguages.add(new Language("Nepali", "ne", "🇳🇵"));
        otherLanguages.add(new Language("Norwegian", "no", "🇳🇴"));
        otherLanguages.add(new Language("Odia", "or", "🇮🇳"));
        otherLanguages.add(new Language("Pashto", "ps", "🇦🇫"));
        otherLanguages.add(new Language("Persian", "fa", "🇮🇷"));
        otherLanguages.add(new Language("Polish", "pl", "🇵🇱"));
        otherLanguages.add(new Language("Portuguese", "pt", "🇵🇹"));
        otherLanguages.add(new Language("Punjabi", "pa", "🇮🇳"));
        otherLanguages.add(new Language("Romanian", "ro", "🇷🇴"));
        otherLanguages.add(new Language("Russian", "ru", "🇷🇺"));
        otherLanguages.add(new Language("Samoan", "sm", "🇼🇸"));
        otherLanguages.add(new Language("Scots Gaelic", "gd", "🏴󠁧󠁢󠁳󠁣󠁴󠁿"));
        otherLanguages.add(new Language("Serbian", "sr", "🇷🇸"));
        otherLanguages.add(new Language("Sesotho", "st", "🇱🇸"));
        otherLanguages.add(new Language("Shona", "sn", "🇿🇼"));
        otherLanguages.add(new Language("Sindhi", "sd", "🇵🇰"));
        otherLanguages.add(new Language("Sinhala", "si", "🇱🇰"));
        otherLanguages.add(new Language("Slovak", "sk", "🇸🇰"));
        otherLanguages.add(new Language("Slovenian", "sl", "🇸🇮"));
        otherLanguages.add(new Language("Somali", "so", "🇸🇴"));
        otherLanguages.add(new Language("Spanish", "es", "🇪🇸"));
        otherLanguages.add(new Language("Sundanese", "su", "🇮🇩"));
        otherLanguages.add(new Language("Swahili", "sw", "🇹🇿"));
        otherLanguages.add(new Language("Swedish", "sv", "🇸🇪"));
        otherLanguages.add(new Language("Tajik", "tg", "🇹🇯"));
        otherLanguages.add(new Language("Tamil", "ta", "🇮🇳"));
        otherLanguages.add(new Language("Tatar", "tt", "🇷🇺"));
        otherLanguages.add(new Language("Telugu", "te", "🇮🇳"));
        otherLanguages.add(new Language("Thai", "th", "🇹🇭"));
        otherLanguages.add(new Language("Turkish", "tr", "🇹🇷"));
        otherLanguages.add(new Language("Turkmen", "tk", "🇹🇲"));
        otherLanguages.add(new Language("Ukrainian", "uk", "🇺🇦"));
        otherLanguages.add(new Language("Urdu", "ur", "🇵🇰"));
        otherLanguages.add(new Language("Uyghur", "ug", "🇨🇳"));
        otherLanguages.add(new Language("Uzbek", "uz", "🇺🇿"));
        otherLanguages.add(new Language("Vietnamese", "vi", "🇻🇳"));
        otherLanguages.add(new Language("Welsh", "cy", "🏴󠁧󠁢󠁷󠁬󠁳󠁿"));
        otherLanguages.add(new Language("Xhosa", "xh", "🇿🇦"));
        otherLanguages.add(new Language("Yiddish", "yi", "🇮🇱"));
        otherLanguages.add(new Language("Yoruba", "yo", "🇳🇬"));
        otherLanguages.add(new Language("Zulu", "zu", "🇿🇦"));

        // Сортировка остальных языков по алфавиту
        Collections.sort(otherLanguages, new Comparator<Language>() {
            @Override
            public int compare(Language l1, Language l2) {
                return l1.name.compareTo(l2.name);
            }
        });

        // Добавляем отсортированные языки после "Auto"
        languages.addAll(otherLanguages);

        return languages;
    }

    private int getIndex(List<Language> languages, String code) {
        for (int i = 0; i < languages.size(); i++) {
            if (languages.get(i).code.equals(code)) return i;
        }
        return 0;
    }

    private class LanguageAdapter extends ArrayAdapter<Language> {

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
            textView.setTextColor(getResources().getColor(R.color.translate_text_primary));
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
            textView.setTextColor(getResources().getColor(R.color.translate_text_primary));
            textView.setTextSize(18);
            textView.setPadding(48, 24, 48, 24);

            return convertView;
        }
    }
}


