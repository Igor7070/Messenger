package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Распознавание речи на устройстве (Vosk) для бесплатного режима перевода.
 *
 * Почему не системный распознаватель Android: его языковые пакеты принадлежат
 * приложению Google, и запустить их загрузку из нашего кода нельзя —
 * пользователю пришлось бы искать их в системных настройках. Кроме того, он
 * требует микрофон себе, а во время звонка микрофон уже занят разговором.
 *
 * Vosk лишён обоих недостатков: модель — обычный файл, который скачивает само
 * приложение, а на вход он принимает готовый поток звука, тот же, что мы и так
 * захватываем во время звонка. Качество ниже облачного, зато бесплатно и ничего
 * не покидает телефон.
 *
 * Звук: PCM 16 бит, моно, 16 кГц.
 */
public class VoskRecognizer {
    private static final String TAG = "VoskRecognizer";
    /** Модели лежат рядом с моделями перевода для веба — раздаём со своего сайта. */
    private static final String MODELS_BASE = "https://igor7070.github.io/Messenger/models/vosk/";

    public interface Listener {
        /** Распознана законченная фраза. */
        void onPhrase(String text);
        /** Модель отсутствует — нужно согласие на загрузку. */
        void onModelMissing(String languageCode, int sizeMb);
        /** Ход загрузки, 0–100. */
        void onDownloadProgress(int percent);
        void onReady();
        void onError(String message);
    }

    /** Какие модели у нас есть и сколько весят (для честного предупреждения). */
    private static String modelName(String languageCode) {
        switch (languageCode == null ? "" : languageCode) {
            case "ru": return "vosk-model-small-ru-0.22";
            case "en": return "vosk-model-small-en-us-0.15";
            case "uk": return "vosk-model-small-uk-v3-small";
            default: return null;
        }
    }

    /** Размеры заметно разные: украинская модель у Vosk крупнее остальных втрое. */
    /**
     * На сколько файлов разрезана модель при раздаче. GitHub Pages не отдаёт
     * файлы больше 100 МБ, поэтому крупные модели лежат частями .part-aa,
     * .part-ab и склеиваются на телефоне.
     */
    private static int modelParts(String languageCode) {
        return "uk".equals(languageCode) ? 2 : 1;
    }

    public static int modelSizeMb(String languageCode) {
        if ("ru".equals(languageCode)) return 46;
        if ("uk".equals(languageCode)) return 140;
        return 40;
    }

    public static boolean isLanguageSupported(String languageCode) {
        return modelName(languageCode) != null;
    }

    private final Context context;
    private final Listener listener;
    private Model model;
    private Recognizer recognizer;
    /**
     * Очередь кусков звука. Распознавание нельзя делать в потоке, который читает
     * микрофон: пока Vosk считает очередной кусок, запись не ждёт, и несчитанное
     * затирается — речь доходила обрывками по одному-два слова. Поэтому поток
     * чтения складывает копию куска сюда и сразу возвращается к микрофону.
     */
    private final java.util.concurrent.BlockingQueue<byte[]> queue =
            new java.util.concurrent.ArrayBlockingQueue<>(128);
    private Thread worker;
    private volatile boolean running;

    public VoskRecognizer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public static boolean isModelDownloaded(Context context, String languageCode) {
        String name = modelName(languageCode);
        return name != null && new File(modelDir(context, name), "README").exists();
    }

    private static File modelDir(Context context, String name) {
        return new File(context.getFilesDir(), "vosk/" + name);
    }

    /**
     * Подготовить распознавание. Если модели нет, сообщает об этом слушателю —
     * загрузка начинается только по явному согласию пользователя.
     */
    public void prepare(String languageCode) {
        String name = modelName(languageCode);
        if (name == null) {
            listener.onError("There is no speech model for this language yet");
            return;
        }
        if (!isModelDownloaded(context, languageCode)) {
            listener.onModelMissing(languageCode, modelSizeMb(languageCode));
            return;
        }
        new Thread(() -> openModel(name)).start();
    }

    /** Скачать модель и открыть её. Вызывать после согласия пользователя. */
    public void downloadAndPrepare(String languageCode) {
        String name = modelName(languageCode);
        if (name == null) return;
        new Thread(() -> {
            try {
                download(name, languageCode);
                openModel(name);
            } catch (Exception e) {
                Log.e(TAG, "Ошибка загрузки модели: " + e.getMessage());
                listener.onError("Could not download the model: " + e.getMessage());
            }
        }).start();
    }

    private void download(String name, String languageCode) throws IOException {
        File target = modelDir(context, name);
        File tmpZip = new File(context.getCacheDir(), name + ".zip");
        int parts = modelParts(languageCode);
        long expected = (long) modelSizeMb(languageCode) * 1024 * 1024;
        Log.d(TAG, "Качаем модель " + name + (parts > 1 ? " (" + parts + " части)" : ""));

        long done = 0;
        int lastPercent = -1;
        try (FileOutputStream out = new FileOutputStream(tmpZip)) {
            for (int part = 0; part < parts; part++) {
                String url = parts == 1
                        ? MODELS_BASE + name + ".zip"
                        : MODELS_BASE + name + ".zip.part-a" + (char) ('a' + part);
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                try (InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[65536];
                    int read;
                    while ((read = in.read(buf)) > 0) {
                        out.write(buf, 0, read);
                        done += read;
                        int percent = (int) Math.min(99, 100L * done / Math.max(expected, 1));
                        if (percent != lastPercent && percent % 5 == 0) {
                            lastPercent = percent;
                            listener.onDownloadProgress(percent);
                        }
                    }
                } finally {
                    conn.disconnect();
                }
            }
        }
        listener.onDownloadProgress(100);

        // Распаковываем: внутри архива папка с именем модели
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        try (ZipInputStream zip = new ZipInputStream(new java.io.FileInputStream(tmpZip))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(parent, entry.getName());
                if (!out.getCanonicalPath().startsWith(parent.getCanonicalPath())) continue; // защита от выхода из папки
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File dir = out.getParentFile();
                    if (dir != null) dir.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int read;
                        while ((read = zip.read(buf)) > 0) fos.write(buf, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
        tmpZip.delete();
        Log.d(TAG, "Модель распакована: " + target.getAbsolutePath());
    }

    private void openModel(String name) {
        try {
            close();
            model = new Model(modelDir(context, name).getAbsolutePath());
            recognizer = new Recognizer(model, 16000.0f);
            startWorker();
            Log.d(TAG, "Модель открыта: " + name);
            listener.onReady();
        } catch (Exception e) {
            Log.e(TAG, "Не удалось открыть модель: " + e.getMessage());
            listener.onError("Could not open the speech model");
        }
    }

    /**
     * Передать очередной кусок звука. Законченные фразы отдаются слушателю —
     * Vosk сам определяет конец фразы по паузе.
     *
     * Копируем кусок: буфер у вызывающего один и тут же переиспользуется под
     * следующую порцию с микрофона.
     */
    public void accept(byte[] pcm, int length) {
        if (!running || length <= 0) return;
        byte[] chunk = new byte[length];
        System.arraycopy(pcm, 0, chunk, 0, length);
        if (!queue.offer(chunk)) {
            // Очередь на 128 кусков — это около шести секунд. Если переполнилась,
            // телефон не успевает вообще, и копить дальше бессмысленно
            Log.w(TAG, "Распознавание не успевает, кусок пропущен");
        }
    }

    private void startWorker() {
        running = true;
        queue.clear();
        worker = new Thread(() -> {
            while (running) {
                byte[] chunk;
                try {
                    chunk = queue.take();
                } catch (InterruptedException e) {
                    return;
                }
                Recognizer r = recognizer;
                if (r == null) continue;
                try {
                    if (r.acceptWaveForm(chunk, chunk.length)) {
                        String text = extractText(r.getResult());
                        if (text != null && !text.isEmpty()) {
                            Log.d(TAG, "Распознано: " + text);
                            listener.onPhrase(text);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Ошибка распознавания: " + e.getMessage());
                }
            }
        }, "vosk-worker");
        worker.start();
    }

    /** Остаток в конце разговора, чтобы последняя фраза не потерялась. */
    public void flush() {
        Recognizer r = recognizer;
        if (r == null) return;
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(500);
            } catch (InterruptedException ignored) {
            }
            worker = null;
        }
        try {
            // Остаток очереди — это ещё не распознанный хвост последней фразы
            byte[] chunk;
            while ((chunk = queue.poll()) != null) {
                r.acceptWaveForm(chunk, chunk.length);
            }
            String text = extractText(r.getFinalResult());
            if (text != null && !text.isEmpty()) listener.onPhrase(text);
        } catch (Exception ignored) {
        }
    }

    public boolean isReady() {
        return recognizer != null;
    }

    public void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        queue.clear();
        try {
            if (recognizer != null) recognizer.close();
            if (model != null) model.close();
        } catch (Exception ignored) {
        }
        recognizer = null;
        model = null;
    }

    /** Vosk отдаёт JSON вида {"text":"..."}. */
    private static String extractText(String json) {
        try {
            return new JSONObject(json).optString("text", "").trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** Название языка по-английски: интерфейс мессенджера английский. */
    public static String label(String languageCode) {
        return new Locale(languageCode == null ? "en" : languageCode).getDisplayLanguage(Locale.ENGLISH);
    }
}
