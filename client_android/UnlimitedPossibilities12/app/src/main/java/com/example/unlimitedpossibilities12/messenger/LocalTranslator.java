package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Перевод на устройстве для секретных чатов (ML Kit Translation).
 *
 * Текст не покидает телефон: языковые пакеты скачиваются один раз (с серверов
 * Google, по явному согласию пользователя), после чего перевод работает
 * офлайн. Именно поэтому в секретных чатах можно переводить, не ломая E2EE:
 * перевод делается ПОСЛЕ расшифровки (для входящих) и ДО шифрования (для
 * исходящих), а серверный переводчик (OpenAI) сюда не подключается.
 *
 * ML Kit переводит через английский, поэтому для пары ru↔uk нужны пакеты
 * ru, uk и en. {@link #packsNeeded} это учитывает.
 */
public final class LocalTranslator {
    private static final String TAG = "LocalTranslator";
    private static final String PREFS = "local_translation";

    /** Поддерживаемые языки на старте. Ключ — код ML Kit (BCP-47), значение — подпись. */
    public static final Map<String, String> SUPPORTED = new java.util.LinkedHashMap<>();
    static {
        SUPPORTED.put(TranslateLanguage.ENGLISH, "English");
        SUPPORTED.put(TranslateLanguage.RUSSIAN, "Русский");
        SUPPORTED.put(TranslateLanguage.UKRAINIAN, "Українська");
    }

    /** Примерный размер одного языкового пакета — для диалога с пользователем. */
    public static final int PACK_SIZE_MB = 30;

    private static final Map<String, Translator> CLIENTS = new HashMap<>();

    private LocalTranslator() {}

    // ---------- настройки чата ----------

    public static boolean isEnabled(Context ctx, long chatId) {
        return prefs(ctx).getBoolean("enabled_" + chatId, false);
    }

    /** Язык, на который переводить (код ML Kit); null = не задан. */
    public static String targetLanguage(Context ctx, long chatId) {
        return prefs(ctx).getString("target_" + chatId, null);
    }

    /** Язык, с которого переводить исходящие (обычно язык пользователя). */
    public static String sourceLanguage(Context ctx, long chatId) {
        return prefs(ctx).getString("source_" + chatId, TranslateLanguage.RUSSIAN);
    }

    public static void save(Context ctx, long chatId, boolean enabled, String source, String target) {
        prefs(ctx).edit()
                .putBoolean("enabled_" + chatId, enabled)
                .putString("source_" + chatId, source)
                .putString("target_" + chatId, target)
                .apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- языковые пакеты ----------

    /** Какие пакеты нужны для пары языков (с учётом английского как промежуточного). */
    public static Set<String> packsNeeded(String source, String target) {
        Set<String> set = new java.util.LinkedHashSet<>();
        set.add(source);
        set.add(target);
        if (!TranslateLanguage.ENGLISH.equals(source) && !TranslateLanguage.ENGLISH.equals(target)) {
            set.add(TranslateLanguage.ENGLISH);
        }
        return set;
    }

    /** Скачанные пакеты (коды языков). */
    public static Task<Set<TranslateRemoteModel>> downloadedPacks() {
        return RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class);
    }

    /** Есть ли на устройстве всё нужное для пары. */
    public static Task<Boolean> isPairReady(String source, String target) {
        return downloadedPacks().continueWith(t -> {
            if (!t.isSuccessful() || t.getResult() == null) return false;
            Set<String> have = new java.util.HashSet<>();
            for (TranslateRemoteModel m : t.getResult()) have.add(m.getLanguage());
            return have.containsAll(packsNeeded(source, target));
        });
    }

    /**
     * Скачивает недостающие пакеты. Загрузка идёт с серверов Google — это
     * единственный сетевой обмен во всём механизме, и в нём нет ни одного
     * слова из чата.
     */
    public static Task<Void> downloadPair(String source, String target, boolean wifiOnly) {
        DownloadConditions.Builder cond = new DownloadConditions.Builder();
        if (wifiOnly) cond.requireWifi();
        DownloadConditions conditions = cond.build();
        RemoteModelManager manager = RemoteModelManager.getInstance();
        java.util.List<Task<Void>> tasks = new java.util.ArrayList<>();
        for (String lang : packsNeeded(source, target)) {
            TranslateRemoteModel model = new TranslateRemoteModel.Builder(lang).build();
            tasks.add(manager.download(model, conditions));
            Log.d(TAG, "Downloading language pack: " + lang);
        }
        return Tasks.whenAll(tasks);
    }

    /** Скачивает перечисленные пакеты (недостающие ML Kit скачает, имеющиеся пропустит). */
    public static Task<Void> downloadPacks(Set<String> langs, boolean wifiOnly) {
        DownloadConditions.Builder cond = new DownloadConditions.Builder();
        if (wifiOnly) cond.requireWifi();
        DownloadConditions conditions = cond.build();
        RemoteModelManager manager = RemoteModelManager.getInstance();
        java.util.List<Task<Void>> tasks = new java.util.ArrayList<>();
        for (String lang : langs) {
            tasks.add(manager.download(new TranslateRemoteModel.Builder(lang).build(), conditions));
            Log.d(TAG, "Downloading language pack: " + lang);
        }
        return Tasks.whenAll(tasks);
    }

    public static Task<Void> deletePack(String lang) {
        synchronized (CLIENTS) {
            for (Map.Entry<String, Translator> e : new java.util.ArrayList<>(CLIENTS.entrySet())) {
                if (e.getKey().startsWith(lang + ">") || e.getKey().endsWith(">" + lang)) {
                    e.getValue().close();
                    CLIENTS.remove(e.getKey());
                }
            }
        }
        return RemoteModelManager.getInstance().deleteDownloadedModel(new TranslateRemoteModel.Builder(lang).build());
    }

    // ---------- перевод ----------

    private static Translator client(String source, String target) {
        String key = source + ">" + target;
        synchronized (CLIENTS) {
            Translator t = CLIENTS.get(key);
            if (t == null) {
                t = Translation.getClient(new TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build());
                CLIENTS.put(key, t);
            }
            return t;
        }
    }

    /**
     * Перевод текста на устройстве. Пакеты должны быть уже скачаны — иначе
     * ML Kit сам начнёт загрузку без условий (мобильный трафик), поэтому
     * вызывать только после {@link #isPairReady}.
     */
    public static Task<String> translate(String text, String source, String target) {
        if (text == null || text.trim().isEmpty() || source.equals(target)) {
            return Tasks.forResult(text);
        }
        return client(source, target).translate(text);
    }

    /**
     * Определяет язык текста на устройстве. Возвращает код ML Kit или null,
     * если уверенности нет (короткие фразы, смесь языков).
     */
    public static Task<String> detectLanguage(String text) {
        return com.google.mlkit.nl.languageid.LanguageIdentification.getClient()
                .identifyLanguage(text)
                .continueWith(t -> {
                    String code = t.isSuccessful() ? t.getResult() : null;
                    return (code == null || "und".equals(code)) ? null : code;
                });
    }

    /**
     * «Перевести на target, если текст не на target». Исходный язык определяется
     * автоматически; если определить не удалось — берём другой из поддерживаемых
     * (для пары ru/en это однозначно). Возвращает null, если переводить не нужно.
     */
    public static Task<String> translateToIfNeeded(String text, String target) {
        if (text == null || text.trim().isEmpty()) return Tasks.forResult(null);
        return detectLanguage(text).continueWithTask(d -> {
            String source = d.getResult();
            if (source == null || !SUPPORTED.containsKey(source)) {
                // ML Kit либо не уверен (короткая фраза вроде «Нет»), либо назвал язык вне
                // нашего набора (одиночные кириллические слова он часто относит к болгарскому,
                // македонскому, сербскому). В обоих случаях определяем по алфавиту —
                // для ru/uk/en этого достаточно.
                String byAlphabet = detectByAlphabet(text);
                Log.d(TAG, "Language id: mlkit=" + source + ", alphabet=" + byAlphabet + " for \"" + text + "\"");
                source = byAlphabet;
            }
            if (source == null) return Tasks.forResult(null); // цифры, смайлы — переводить нечего
            if (source.equals(target)) return Tasks.forResult(null);
            final String src = source;
            return isPairReady(src, target).continueWithTask(r ->
                    Boolean.TRUE.equals(r.getResult()) ? translate(text, src, target) : Tasks.forResult(null));
        });
    }

    /**
     * Чего не хватает, чтобы перевести этот текст на target: набор кодов языков
     * (может быть пустым). Нужен, чтобы предложить скачать пакет, а не молча
     * показывать собеседника в оригинале.
     */
    public static Task<Set<String>> missingPacksFor(String text, String target) {
        if (text == null || text.isBlank() || target == null) return Tasks.forResult(java.util.Collections.emptySet());
        return detectLanguage(text).continueWithTask(d -> {
            String source = d.getResult();
            if (source == null || !SUPPORTED.containsKey(source)) source = detectByAlphabet(text);
            if (source == null || source.equals(target)) return Tasks.forResult(java.util.Collections.<String>emptySet());
            final Set<String> needed = packsNeeded(source, target);
            return downloadedPacks().continueWith(t -> {
                Set<String> have = new java.util.HashSet<>();
                if (t.isSuccessful() && t.getResult() != null) {
                    for (TranslateRemoteModel m : t.getResult()) have.add(m.getLanguage());
                }
                Set<String> missing = new java.util.LinkedHashSet<>(needed);
                missing.removeAll(have);
                return missing;
            });
        });
    }

    /** Определение языка по алфавиту: латиница -> en; кириллица с і/ї/є/ґ -> uk; иначе ru. */
    static String detectByAlphabet(String text) {
        if (text == null) return null;
        int cyr = 0, lat = 0;
        boolean ukLetters = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0400 && c <= 0x04FF) {
                cyr++;
                if ("іїєґІЇЄҐ".indexOf(c) >= 0) ukLetters = true;
            } else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                lat++;
            }
        }
        if (cyr == 0 && lat == 0) return null;
        if (lat > cyr) return TranslateLanguage.ENGLISH;
        return ukLetters ? TranslateLanguage.UKRAINIAN : TranslateLanguage.RUSSIAN;
    }

    /** Для списка выбора: язык подписан на самом себе — «Русский», «Українська». */
    public static String label(String lang) {
        String l = SUPPORTED.get(lang);
        return l != null ? l : lang;
    }

    /** Для сообщений: интерфейс мессенджера английский, значит и название языка. */
    public static String englishLabel(String lang) {
        return new java.util.Locale(lang == null ? "en" : lang)
                .getDisplayLanguage(java.util.Locale.ENGLISH);
    }
}
