package com.example.unl_pos12.service;

import java.io.ByteArrayOutputStream;

/**
 * Нарезка потока речи на фразы по паузам.
 *
 * Раньше звук отправлялся на распознавание каждые пять секунд по таймеру.
 * Фраза почти никогда не укладывается ровно в окно, поэтому её резало
 * посередине: распознавался обрывок, переводился обрывок, озвучивался
 * обрывок. Вдобавок на распознавание уходила и тишина — в разговоре люди
 * говорят по очереди, и половину времени микрофон пишет паузу, за которую
 * тоже приходится платить.
 *
 * Здесь кусок уходит на распознавание, когда человек замолчал: фраза
 * получается целой, задержка — «пауза плюс обработка», а тишина не
 * оплачивается вовсе.
 *
 * Формат звука — PCM 16 бит, моно, 16 кГц (так шлют оба клиента).
 */
public class VoiceSegmenter {

    private static final int SAMPLE_RATE = 16000;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int BYTES_PER_MS = SAMPLE_RATE * BYTES_PER_SAMPLE / 1000; // 32

    /** Пауза, после которой считаем фразу законченной. */
    private static final long SILENCE_FLUSH_MS = 700;
    /** Короче этого — не фраза, а кашель или щелчок: не отправляем. */
    private static final long MIN_SPEECH_MS = 350;
    /** Предел на случай монолога без пауз — иначе задержка росла бы бесконечно. */
    private static final long MAX_SEGMENT_MS = 12000;
    /** Немного тишины перед речью, чтобы не срезать первый слог. */
    private static final long PREROLL_MS = 300;
    /** Абсолютный минимум громкости: ниже — заведомо тишина. */
    private static final double MIN_SPEECH_RMS = 300;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private long speechMs;          // сколько в буфере речи
    private long trailingSilenceMs; // сколько тишины подряд в конце
    private long bufferedMs;        // длина буфера
    private double noiseFloor = 150; // оценка уровня шума, подстраивается
    private long lastActivityAt = System.currentTimeMillis();

    /**
     * Добавить очередной кусок звука.
     *
     * @return готовая фраза (PCM) или null, если ещё говорят
     */
    public byte[] accept(byte[] chunk) {
        if (chunk == null || chunk.length == 0) return null;
        lastActivityAt = System.currentTimeMillis();

        double rms = rms(chunk);
        long chunkMs = chunk.length / BYTES_PER_MS;
        boolean isSpeech = rms > Math.max(MIN_SPEECH_RMS, noiseFloor * 2.5);

        if (!isSpeech) {
            // Шум подстраиваем только по тишине, иначе громкий голос поднял бы порог
            noiseFloor = noiseFloor * 0.95 + rms * 0.05;
        }

        buffer.write(chunk, 0, chunk.length);
        bufferedMs += chunkMs;

        if (isSpeech) {
            speechMs += chunkMs;
            trailingSilenceMs = 0;
        } else {
            trailingSilenceMs += chunkMs;
        }

        // Речи ещё не было — держим только небольшой хвост тишины перед ней
        if (speechMs == 0 && bufferedMs > PREROLL_MS * 2) {
            trimToTail(PREROLL_MS);
            return null;
        }

        boolean phraseEnded = speechMs > 0 && trailingSilenceMs >= SILENCE_FLUSH_MS;
        boolean tooLong = bufferedMs >= MAX_SEGMENT_MS;
        if (!phraseEnded && !tooLong) return null;

        byte[] segment = buffer.toByteArray();
        boolean worthSending = speechMs >= MIN_SPEECH_MS;
        reset();
        return worthSending ? segment : null;
    }

    /** Остаток в конце разговора — чтобы последняя фраза не потерялась. */
    public byte[] flush() {
        if (speechMs < MIN_SPEECH_MS) {
            reset();
            return null;
        }
        byte[] segment = buffer.toByteArray();
        reset();
        return segment;
    }

    public boolean isIdle(long olderThanMs) {
        return System.currentTimeMillis() - lastActivityAt > olderThanMs;
    }

    private void reset() {
        buffer.reset();
        speechMs = 0;
        trailingSilenceMs = 0;
        bufferedMs = 0;
    }

    /** Оставить в буфере только последние msToKeep миллисекунд. */
    private void trimToTail(long msToKeep) {
        byte[] all = buffer.toByteArray();
        int keep = (int) Math.min(all.length, msToKeep * BYTES_PER_MS);
        buffer.reset();
        buffer.write(all, all.length - keep, keep);
        bufferedMs = keep / BYTES_PER_MS;
        trailingSilenceMs = bufferedMs;
    }

    /** Громкость куска: корень из среднего квадрата отсчётов. */
    private static double rms(byte[] pcm) {
        long sum = 0;
        int samples = pcm.length / BYTES_PER_SAMPLE;
        if (samples == 0) return 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF)); // little-endian
            sum += (long) sample * sample;
        }
        return Math.sqrt((double) sum / samples);
    }
}
