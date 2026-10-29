package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Распознавание и синтез речи средствами самого телефона — бесплатный режим
 * перевода звонков.
 *
 * Звук никуда не уходит: телефон распознаёт собственный микрофон, по сети
 * передаётся только текст, а переводит его получатель у себя через ML Kit.
 * Денег это не стоит и работает без интернета, но качество ниже облачного —
 * поэтому режим выбирается пользователем, а не включается молча.
 *
 * Распознавание принудительно офлайновое: без этого Android отправляет звук
 * на серверы Google, и вся выгода по приватности пропадает.
 */
public class OnDeviceSpeech {
    private static final String TAG = "OnDeviceSpeech";

    public interface Listener {
        /** Распознана законченная фраза. */
        void onPhrase(String text);
        /** Распознавание невозможно (нет пакета, нет движка) — причина для пользователя. */
        void onUnavailable(String reason);
    }

    /** Сообщает, произносит ли телефон перевод прямо сейчас. */
    public interface SpeakingListener {
        void onSpeakingChanged(boolean speaking);
    }

    private final Context context;
    private final Listener listener;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private String languageTag = "ru-RU";
    private boolean listening;
    private boolean stopped;
    private SpeakingListener speakingListener;
    /** Фраз в очереди синтеза: «замолчал» — только когда кончилась последняя. */
    private final java.util.concurrent.atomic.AtomicInteger speaking =
            new java.util.concurrent.atomic.AtomicInteger();

    public OnDeviceSpeech(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public static boolean isRecognitionAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    /** @param languageCode код вида "ru", "en" — язык, на котором говорит пользователь */
    public void setLanguage(String languageCode) {
        if (languageCode == null || languageCode.isEmpty() || "auto".equalsIgnoreCase(languageCode)) {
            languageTag = Locale.getDefault().toLanguageTag();
        } else {
            languageTag = new Locale(languageCode).toLanguageTag();
        }
        Log.d(TAG, "Language set to " + languageTag);
    }

    /** Начать слушать микрофон. Распознаватель сам останавливается после фразы — перезапускаем. */
    public void start() {
        if (!isRecognitionAvailable(context)) {
            listener.onUnavailable("На этом телефоне нет распознавания речи");
            return;
        }
        stopped = false;
        ensureTts();
        startListening();
    }

    public void stop() {
        stopped = true;
        listening = false;
        if (recognizer != null) {
            try {
                recognizer.stopListening();
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
        if (tts != null) {
            try {
                if (speaking.getAndSet(0) > 0) notifySpeaking(false);
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
    }

    public void setSpeakingListener(SpeakingListener listener) {
        this.speakingListener = listener;
    }

    /** Произнести переведённый текст на языке слушателя. */
    public void speak(String text, String languageCode) {
        if (text == null || text.isEmpty()) return;
        if (tts == null) {
            // Молча не исчезаем: иначе отсутствие голоса не отличить от
            // выключенного переключателя
            Log.w(TAG, "Нечем произнести перевод: синтез речи не готов");
            return;
        }
        try {
            tts.setLanguage(new Locale(languageCode == null ? "en" : languageCode));
            if (speaking.getAndIncrement() == 0) notifySpeaking(true);
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "translation");
        } catch (Exception e) {
            Log.w(TAG, "TTS error: " + e.getMessage());
            finishedOne();
        }
    }

    /** Замолчать немедленно: озвучку выключили посреди фразы. */
    public void silence() {
        if (tts == null) return;
        try {
            tts.stop();
        } catch (Exception ignored) {
        }
        if (speaking.getAndSet(0) > 0) notifySpeaking(false);
    }

    private void notifySpeaking(boolean value) {
        SpeakingListener l = speakingListener;
        if (l != null) l.onSpeakingChanged(value);
    }

    /** Одна фраза договорена: громкость возвращаем, когда очередь опустела. */
    private void finishedOne() {
        if (speaking.decrementAndGet() <= 0) {
            speaking.set(0);
            notifySpeaking(false);
        }
    }

    /**
     * Подготовить только синтез речи, без микрофона: в звонке распознаёт Vosk,
     * а произносить перевод всё равно нужно голосом телефона.
     */
    public void prepareTts() {
        stopped = false;
        ensureTts();
    }

    private void ensureTts() {
        if (tts != null) return;
        tts = new TextToSpeech(context, status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS недоступен");
                tts = null;
                return;
            }
            // Разговор идёт по голосовому каналу, а синтез по умолчанию играет
            // как музыка: громкость у них разная, и перевод то тонет, то кричит
            try {
                tts.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            } catch (Exception e) {
                Log.w(TAG, "Не удалось направить синтез в канал звонка: " + e.getMessage());
            }
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) { finishedOne(); }
                @Override public void onError(String utteranceId) { finishedOne(); }
            });
        });
    }

    private void startListening() {
        if (stopped || listening) return;
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
                recognizer.setRecognitionListener(recognitionListener);
            }
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            // Главное: не отправлять звук на серверы Google
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            }
            recognizer.startListening(intent);
            listening = true;
        } catch (Exception e) {
            Log.e(TAG, "Не удалось начать распознавание: " + e.getMessage());
            listener.onUnavailable("Не удалось начать распознавание: " + e.getMessage());
        }
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}

        @Override
        public void onError(int error) {
            listening = false;
            // Нет пакета языка — предупреждаем один раз, остальные ошибки штатны
            if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                    || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                listener.onUnavailable("Нет офлайн-пакета для выбранного языка — скачайте его в настройках телефона");
                return;
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                listener.onUnavailable("Нет разрешения на микрофон");
                return;
            }
            // Тишина, таймаут, занятый распознаватель — просто слушаем дальше
            restartSoon();
        }

        @Override
        public void onResults(Bundle results) {
            listening = false;
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                String text = matches.get(0);
                if (text != null && !text.trim().isEmpty()) {
                    Log.d(TAG, "Распознано: " + text);
                    listener.onPhrase(text.trim());
                }
            }
            restartSoon();
        }

        @Override public void onPartialResults(Bundle partialResults) {}
        @Override public void onEvent(int eventType, Bundle params) {}
    };

    /** Распознаватель отдаёт по одной фразе — для непрерывного разговора запускаем снова. */
    private void restartSoon() {
        if (stopped) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::startListening, 200);
    }
}
