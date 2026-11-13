package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Звуковая волна голосового сообщения: громкость записи по кусочкам.
 *
 * Считается один раз — запись разбирается в фоне и превращается в сотню
 * чисел от 0 до 100, — а дальше живёт в памяти и в настройках. Иначе
 * волна появлялась бы заново при каждой прокрутке ленты.
 */
public final class Waveforms {

    private static final String TAG = "Waveforms";
    private static final String PREFS = "waveform_cache2";
    /** Столбиков в волне: больше телефон всё равно не покажет. */
    private static final int BARS = 64;
    /** Сколько записей держим в настройках, чтобы файл не рос без конца. */
    private static final int KEEP = 300;

    public interface Listener {
        void onWaveform(byte[] levels);
    }

    private static final Map<String, byte[]> MEMORY = new HashMap<>();
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Waveforms() {
    }

    /** Готовая волна или null, если её ещё не считали. */
    public static byte[] cached(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) return null;
        synchronized (MEMORY) {
            byte[] known = MEMORY.get(url);
            if (known != null) return known;
        }
        String stored = prefs(context).getString(url, null);
        if (stored == null) return null;
        try {
            byte[] levels = Base64.decode(stored, Base64.NO_WRAP);
            synchronized (MEMORY) {
                MEMORY.put(url, levels);
            }
            return levels;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Отдаёт волну: готовую — сразу, иначе считает в фоне и зовёт слушателя
     * в основном потоке. Одну и ту же запись дважды не разбираем.
     */
    public static void load(Context context, String url, Listener listener) {
        if (context == null || url == null || url.isEmpty() || listener == null) return;
        byte[] known = cached(context, url);
        if (known != null) {
            listener.onWaveform(known);
            return;
        }
        final Context app = context.getApplicationContext();
        synchronized (IN_FLIGHT) {
            if (!IN_FLIGHT.add(url)) return; // уже считаем
        }
        POOL.execute(() -> {
            byte[] levels = null;
            try {
                levels = decode(url);
            } catch (Throwable e) {
                Log.w(TAG, "Волну для " + url + " посчитать не вышло: " + e);
            } finally {
                synchronized (IN_FLIGHT) {
                    IN_FLIGHT.remove(url);
                }
            }
            if (levels == null) return;
            remember(app, url, levels);
            final byte[] ready = levels;
            MAIN.post(() -> listener.onWaveform(ready));
        });
    }

    /** Забыть волну: запись пропала или заменилась. */
    public static void forget(Context context, String url) {
        if (context == null || url == null) return;
        synchronized (MEMORY) {
            MEMORY.remove(url);
        }
        prefs(context).edit().remove(url).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void remember(Context context, String url, byte[] levels) {
        synchronized (MEMORY) {
            MEMORY.put(url, levels);
        }
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        if (prefs.getAll().size() >= KEEP) editor.clear();
        editor.putString(url, Base64.encodeToString(levels, Base64.NO_WRAP)).apply();
    }

    /**
     * Разбирает запись и возвращает громкость по кусочкам.
     *
     * Звук раскодируется во временные отрезки примерно по 20 мс, у каждого
     * берётся самый громкий отсчёт, потом всё это сжимается до {@link #BARS}
     * столбиков и приводится к шкале 0..100 — тихая запись тоже должна
     * выглядеть волной, а не ниткой.
     */
    private static byte[] decode(String url) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(url);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (track < 0 || format == null) return null;
            extractor.selectTrack(track);

            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int chunk = Math.max(1, rate * channels / 50); // ~20 мс

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            List<Integer> peaks = new ArrayList<>();
            int inChunk = 0;
            int chunkPeak = 0;
            boolean fedEverything = false;
            long guard = System.currentTimeMillis() + 20000; // дольше возиться не стоит

            while (System.currentTimeMillis() < guard) {
                if (!fedEverything) {
                    int in = codec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            fedEverything = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int out = codec.dequeueOutputBuffer(info, 10000);
                if (out >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(out);
                    if (buffer != null && info.size > 0) {
                        buffer.order(ByteOrder.nativeOrder());
                        ShortBuffer samples = buffer.asShortBuffer();
                        int count = info.size / 2;
                        for (int i = 0; i < count; i++) {
                            int value = Math.abs(samples.get(i));
                            if (value > chunkPeak) chunkPeak = value;
                            if (++inChunk >= chunk) {
                                peaks.add(chunkPeak);
                                chunkPeak = 0;
                                inChunk = 0;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (out == MediaCodec.INFO_TRY_AGAIN_LATER && fedEverything) {
                    // ждём последние куски
                    if (peaks.size() > 0 && info.size == 0) continue;
                }
            }
            if (inChunk > 0) peaks.add(chunkPeak);
            return shrink(peaks);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                codec.release();
            }
            extractor.release();
        }
    }

    /** Сжимает отрезки до нужного числа столбиков и переводит в 0..100. */
    private static byte[] shrink(List<Integer> peaks) {
        if (peaks.isEmpty()) return null;
        byte[] levels = new byte[BARS];
        int loudest = 1;
        int[] raw = new int[BARS];
        for (int i = 0; i < BARS; i++) {
            int from = (int) ((long) i * peaks.size() / BARS);
            int to = (int) ((long) (i + 1) * peaks.size() / BARS);
            if (to <= from) to = Math.min(peaks.size(), from + 1);
            int peak = 0;
            for (int j = from; j < to; j++) peak = Math.max(peak, peaks.get(j));
            raw[i] = peak;
            if (peak > loudest) loudest = peak;
        }
        // Равняемся не на самый громкий всплеск, а на уровень погромче
        // среднего: один щелчок в микрофон иначе прижимал бы всю речь к низу
        int[] sorted = raw.clone();
        java.util.Arrays.sort(sorted);
        int loud = Math.max(1, sorted[(int) (BARS * 0.85)]);
        if (loud * 4 < loudest) loud = loudest / 4; // запись из одних щелчков
        for (int i = 0; i < BARS; i++) {
            double share = Math.pow(Math.min(1.0, raw[i] / (double) loud), 0.8);
            levels[i] = (byte) Math.max(6, Math.min(100, Math.round(share * 100)));
        }
        return levels;
    }
}
