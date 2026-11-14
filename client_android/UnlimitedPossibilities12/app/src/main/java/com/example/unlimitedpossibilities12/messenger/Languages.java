package com.example.unlimitedpossibilities12.messenger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Языки перевода одним списком.
 *
 * Тот же набор, что предлагается в переписке; вынесен отдельно, чтобы язык
 * по умолчанию на экране настроек выбирался из того же списка, а не из
 * похожего, но своего.
 */
public final class Languages {

    /** Язык, который означает «как получится» — пусть решает сервер. */
    public static final String AUTO = "auto";

    public static final class Language {
        public final String name;
        public final String code;
        public final String flag;

        Language(String name, String code, String flag) {
            this.name = name;
            this.code = code;
            this.flag = flag;
        }

        public String label() {
            return flag + " " + name;
        }
    }

    private static List<Language> cached;
    private static List<Language> target;

    private Languages() {
    }

    /** «Auto» первым, остальные по алфавиту — как в окне перевода. */
    public static synchronized List<Language> all() {
        if (cached != null) return cached;

        List<Language> rest = new ArrayList<>();
        target = rest;
        fill();
        target = null;
        Collections.sort(rest, (left, right) -> left.name.compareTo(right.name));

        List<Language> list = new ArrayList<>();
        list.add(new Language("Auto", AUTO, "\ud83c\udf10"));
        list.addAll(rest);
        cached = Collections.unmodifiableList(list);
        return cached;
    }

    public static int indexOf(String code) {
        List<Language> list = all();
        if (code != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).code.equalsIgnoreCase(code)) return i;
            }
        }
        return 0;
    }

    public static String nameOf(String code) {
        return all().get(indexOf(code)).name;
    }

    public static String labelOf(String code) {
        return all().get(indexOf(code)).label();
    }

    private static void fill() {
        add("Afrikaans", "af", "\ud83c\uddff\ud83c\udde6");
        add("Albanian", "sq", "\ud83c\udde6\ud83c\uddf1");
        add("Amharic", "am", "\ud83c\uddea\ud83c\uddf9");
        add("Arabic", "ar", "\ud83c\uddf8\ud83c\udde6");
        add("Armenian", "hy", "\ud83c\udde6\ud83c\uddf2");
        add("Azerbaijani", "az", "\ud83c\udde6\ud83c\uddff");
        add("Basque", "eu", "\ud83c\uddea\ud83c\uddf8");
        add("Belarusian", "be", "\ud83c\udde7\ud83c\uddfe");
        add("Bengali", "bn", "\ud83c\udde7\ud83c\udde9");
        add("Bosnian", "bs", "\ud83c\udde7\ud83c\udde6");
        add("Bulgarian", "bg", "\ud83c\udde7\ud83c\uddec");
        add("Catalan", "ca", "\ud83c\uddea\ud83c\uddf8");
        add("Cebuano", "ceb", "\ud83c\uddf5\ud83c\udded");
        add("Chichewa", "ny", "\ud83c\uddf2\ud83c\uddfc");
        add("Chinese (Simplified)", "zh", "\ud83c\udde8\ud83c\uddf3");
        add("Chinese (Traditional)", "zh-TW", "\ud83c\uddf9\ud83c\uddfc");
        add("Corsican", "co", "\ud83c\uddeb\ud83c\uddf7");
        add("Croatian", "hr", "\ud83c\udded\ud83c\uddf7");
        add("Czech", "cs", "\ud83c\udde8\ud83c\uddff");
        add("Danish", "da", "\ud83c\udde9\ud83c\uddf0");
        add("Dutch", "nl", "\ud83c\uddf3\ud83c\uddf1");
        add("English", "en", "\ud83c\uddec\ud83c\udde7");
        add("Esperanto", "eo", "\ud83c\udff3\ufe0f");
        add("Estonian", "et", "\ud83c\uddea\ud83c\uddea");
        add("Filipino", "tl", "\ud83c\uddf5\ud83c\udded");
        add("Finnish", "fi", "\ud83c\uddeb\ud83c\uddee");
        add("French", "fr", "\ud83c\uddeb\ud83c\uddf7");
        add("Frisian", "fy", "\ud83c\uddf3\ud83c\uddf1");
        add("Galician", "gl", "\ud83c\uddea\ud83c\uddf8");
        add("Georgian", "ka", "\ud83c\uddec\ud83c\uddea");
        add("German", "de", "\ud83c\udde9\ud83c\uddea");
        add("Greek", "el", "\ud83c\uddec\ud83c\uddf7");
        add("Gujarati", "gu", "\ud83c\uddee\ud83c\uddf3");
        add("Haitian Creole", "ht", "\ud83c\udded\ud83c\uddf9");
        add("Hausa", "ha", "\ud83c\uddf3\ud83c\uddec");
        add("Hawaiian", "haw", "\ud83c\uddfa\ud83c\uddf8");
        add("Hebrew", "he", "\ud83c\uddee\ud83c\uddf1");
        add("Hindi", "hi", "\ud83c\uddee\ud83c\uddf3");
        add("Hmong", "hmn", "\ud83c\udde8\ud83c\uddf3");
        add("Hungarian", "hu", "\ud83c\udded\ud83c\uddfa");
        add("Icelandic", "is", "\ud83c\uddee\ud83c\uddf8");
        add("Igbo", "ig", "\ud83c\uddf3\ud83c\uddec");
        add("Indonesian", "id", "\ud83c\uddee\ud83c\udde9");
        add("Irish", "ga", "\ud83c\uddee\ud83c\uddea");
        add("Italian", "it", "\ud83c\uddee\ud83c\uddf9");
        add("Japanese", "ja", "\ud83c\uddef\ud83c\uddf5");
        add("Javanese", "jv", "\ud83c\uddee\ud83c\udde9");
        add("Kannada", "kn", "\ud83c\uddee\ud83c\uddf3");
        add("Kazakh", "kk", "\ud83c\uddf0\ud83c\uddff");
        add("Khmer", "km", "\ud83c\uddf0\ud83c\udded");
        add("Kinyarwanda", "rw", "\ud83c\uddf7\ud83c\uddfc");
        add("Korean", "ko", "\ud83c\uddf0\ud83c\uddf7");
        add("Kurdish", "ku", "\ud83c\uddee\ud83c\uddf6");
        add("Kyrgyz", "ky", "\ud83c\uddf0\ud83c\uddec");
        add("Lao", "lo", "\ud83c\uddf1\ud83c\udde6");
        add("Latin", "la", "\ud83c\udff3\ufe0f");
        add("Latvian", "lv", "\ud83c\uddf1\ud83c\uddfb");
        add("Lithuanian", "lt", "\ud83c\uddf1\ud83c\uddf9");
        add("Luxembourgish", "lb", "\ud83c\uddf1\ud83c\uddfa");
        add("Macedonian", "mk", "\ud83c\uddf2\ud83c\uddf0");
        add("Malagasy", "mg", "\ud83c\uddf2\ud83c\uddec");
        add("Malay", "ms", "\ud83c\uddf2\ud83c\uddfe");
        add("Malayalam", "ml", "\ud83c\uddee\ud83c\uddf3");
        add("Maltese", "mt", "\ud83c\uddf2\ud83c\uddf9");
        add("Maori", "mi", "\ud83c\uddf3\ud83c\uddff");
        add("Marathi", "mr", "\ud83c\uddee\ud83c\uddf3");
        add("Mongolian", "mn", "\ud83c\uddf2\ud83c\uddf3");
        add("Myanmar (Burmese)", "my", "\ud83c\uddf2\ud83c\uddf2");
        add("Nepali", "ne", "\ud83c\uddf3\ud83c\uddf5");
        add("Norwegian", "no", "\ud83c\uddf3\ud83c\uddf4");
        add("Odia", "or", "\ud83c\uddee\ud83c\uddf3");
        add("Pashto", "ps", "\ud83c\udde6\ud83c\uddeb");
        add("Persian", "fa", "\ud83c\uddee\ud83c\uddf7");
        add("Polish", "pl", "\ud83c\uddf5\ud83c\uddf1");
        add("Portuguese", "pt", "\ud83c\uddf5\ud83c\uddf9");
        add("Punjabi", "pa", "\ud83c\uddee\ud83c\uddf3");
        add("Romanian", "ro", "\ud83c\uddf7\ud83c\uddf4");
        add("Russian", "ru", "\ud83c\uddf7\ud83c\uddfa");
        add("Samoan", "sm", "\ud83c\uddfc\ud83c\uddf8");
        add("Scots Gaelic", "gd", "\ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc73\udb40\udc63\udb40\udc74\udb40\udc7f");
        add("Serbian", "sr", "\ud83c\uddf7\ud83c\uddf8");
        add("Sesotho", "st", "\ud83c\uddf1\ud83c\uddf8");
        add("Shona", "sn", "\ud83c\uddff\ud83c\uddfc");
        add("Sindhi", "sd", "\ud83c\uddf5\ud83c\uddf0");
        add("Sinhala", "si", "\ud83c\uddf1\ud83c\uddf0");
        add("Slovak", "sk", "\ud83c\uddf8\ud83c\uddf0");
        add("Slovenian", "sl", "\ud83c\uddf8\ud83c\uddee");
        add("Somali", "so", "\ud83c\uddf8\ud83c\uddf4");
        add("Spanish", "es", "\ud83c\uddea\ud83c\uddf8");
        add("Sundanese", "su", "\ud83c\uddee\ud83c\udde9");
        add("Swahili", "sw", "\ud83c\uddf9\ud83c\uddff");
        add("Swedish", "sv", "\ud83c\uddf8\ud83c\uddea");
        add("Tajik", "tg", "\ud83c\uddf9\ud83c\uddef");
        add("Tamil", "ta", "\ud83c\uddee\ud83c\uddf3");
        add("Tatar", "tt", "\ud83c\uddf7\ud83c\uddfa");
        add("Telugu", "te", "\ud83c\uddee\ud83c\uddf3");
        add("Thai", "th", "\ud83c\uddf9\ud83c\udded");
        add("Turkish", "tr", "\ud83c\uddf9\ud83c\uddf7");
        add("Turkmen", "tk", "\ud83c\uddf9\ud83c\uddf2");
        add("Ukrainian", "uk", "\ud83c\uddfa\ud83c\udde6");
        add("Urdu", "ur", "\ud83c\uddf5\ud83c\uddf0");
        add("Uyghur", "ug", "\ud83c\udde8\ud83c\uddf3");
        add("Uzbek", "uz", "\ud83c\uddfa\ud83c\uddff");
        add("Vietnamese", "vi", "\ud83c\uddfb\ud83c\uddf3");
        add("Welsh", "cy", "\ud83c\udff4\udb40\udc67\udb40\udc62\udb40\udc77\udb40\udc6c\udb40\udc73\udb40\udc7f");
        add("Xhosa", "xh", "\ud83c\uddff\ud83c\udde6");
        add("Yiddish", "yi", "\ud83c\uddee\ud83c\uddf1");
        add("Yoruba", "yo", "\ud83c\uddf3\ud83c\uddec");
        add("Zulu", "zu", "\ud83c\uddff\ud83c\udde6");
    }

    private static void add(String name, String code, String flag) {
        target.add(new Language(name, code, flag));
    }
}
