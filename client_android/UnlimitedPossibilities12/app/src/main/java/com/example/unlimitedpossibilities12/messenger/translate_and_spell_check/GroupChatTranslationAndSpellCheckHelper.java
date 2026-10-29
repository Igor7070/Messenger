package com.example.unlimitedpossibilities12.messenger.translate_and_spell_check;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.MessageRequest;
import com.example.unlimitedpossibilities12.api.TranslationUpdateRequest;
import com.example.unlimitedpossibilities12.messenger.GroupChatsActivity;
import com.example.unlimitedpossibilities12.messenger.GroupMessageAdapter;
import com.example.unlimitedpossibilities12.api.Message;
import com.example.unlimitedpossibilities12.messenger.TranscriptionSettingsFragment;
import com.example.unlimitedpossibilities12.messenger.TranslationSettingsFragment;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class GroupChatTranslationAndSpellCheckHelper {
    private static final String TAG = "GroupChatTranslation";

    public static void updateTranslationIndicator(Context context, TextView translationIndicator, boolean isTranslationEnabled, String translationLanguage) {
        if (translationIndicator == null) {
            Log.w(TAG, "translationIndicator is null, skipping update");
            return;
        }
        if (isTranslationEnabled) {
            String languageCode = translationLanguage.equals("auto") ? "AUTO" :
                    (translationLanguage.length() >= 2 ? translationLanguage.substring(0, 2).toUpperCase() : translationLanguage.toUpperCase());
            translationIndicator.setText(languageCode);
            translationIndicator.setTextColor(ContextCompat.getColor(context, R.color.colorPrimary));
            translationIndicator.setVisibility(View.VISIBLE);
        } else {
            translationIndicator.setVisibility(View.GONE);
        }
    }

    public static void updateTranslationIcon(Context context, ImageView translationIconView, boolean isTranslationEnabled) {
        if (translationIconView == null) {
            Log.w(TAG, "translationIconView is null, skipping update");
            return;
        }
        translationIconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_globe));
        translationIconView.setColorFilter(ContextCompat.getColor(context, isTranslationEnabled ? R.color.colorPrimary : android.R.color.darker_gray));
    }

    public static void translateExistingMessages(Context context, List<Message> messages, boolean isTranslationEnabled,
                                                 String translationLanguage, long chatId,
                                                 List<Message> translationQueue, GroupMessageAdapter messageAdapter,
                                                 Runnable translateNextMessage) {
        if (!isTranslationEnabled) {
            Log.d(TAG, "Translation disabled, skipping translation");
            return;
        }
        // Пропуск перевода, если режим SELECTIVELY
        if (((GroupChatsActivity) context).getTranslationMode() == TranslationSettingsFragment.TranslationMode.SELECTIVELY) {
            Log.d(TAG, "Translation mode is SELECTIVELY, skipping existing messages translation");
            return;
        }
        translationQueue.clear();
        Log.d(TAG, "Cleared translation queue");
        for (Message message : messages) {
            // Проверяем, что сообщение является текстовым, файлом с текстом или аудио с транскрипцией
            boolean isTranslatable = "text".equalsIgnoreCase(message.getMessageType()) ||
                    ("file".equalsIgnoreCase(message.getMessageType()) &&
                            message.getContent() != null && !message.getContent().isEmpty()) ||
                    ("audio".equalsIgnoreCase(message.getMessageType()) &&
                            message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty());
            if (!isTranslatable) {
                Log.d(TAG, "Skipping non-translatable message id=" + message.getId() + " (type=" + message.getMessageType() + ")");
                continue;
            }
            if (!isMessageNew(context, message, chatId)) {
                Log.d(TAG, "Skipping message id=" + message.getId() + " (not new)");
                continue;
            }
            if (message.getTranslatedContent() == null ||
                    !translationLanguage.equals(message.getTranslationLanguage())) {
                // *** ДОБАВЛЕНО: Установка transcribedContent как content для аудиосообщений ***
                if ("audio".equalsIgnoreCase(message.getMessageType())) {
                    message.setContent(message.getTranscribedContent());
                }
                translationQueue.add(message);
                Log.d(TAG, "Added message id=" + message.getId() + " to translation queue");
            } else {
                Log.d(TAG, "Skipping message id=" + message.getId() + " (already translated to " + message.getTranslationLanguage() + ")");
            }
        }
        Log.d(TAG, "Translation queue size: " + translationQueue.size());
        translateNextMessage.run();
    }

    public static void translateNextMessage(List<Message> translationQueue, boolean isTranslating, Runnable translateMessage) {
        if (translationQueue.isEmpty() || isTranslating) {
            return;
        }
        translateMessage.run();
    }

    public static void clearTranslations(Context context, List<Message> messages, long chatId, List<Message> translationQueue, GroupMessageAdapter messageAdapter) {
        translationQueue.clear();
        SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        for (Message message : messages) {
            message.setTranslatedContent(null);
            message.setShowOriginal(true);
            String key = "translation_" + chatId + "_" + message.getId();
            editor.remove(key);
            editor.remove(key + "_language");
            Log.d(TAG, "Removed translation from SharedPreferences for message id=" + message.getId());
        }

        editor.apply();
        messageAdapter.notifyDataSetChanged();
    }

    public static void translateMessage(Context context, Message message, String translationLanguage, long chatId, GroupMessageAdapter messageAdapter, Runnable translateNextMessage) {
        ApiService apiService = ApiClient.getApiService();

        // Выбор текста для перевода и проверка языка
        String textToTranslate;
        if ("audio".equalsIgnoreCase(message.getMessageType())) {
            textToTranslate = message.getTranscribedContent();
            if (textToTranslate == null || textToTranslate.trim().isEmpty()) {
                Log.e(TAG, "No transcribed content available for translation for message id=" + message.getId());
                Toast.makeText(context, "No transcribed content available for translation", Toast.LENGTH_SHORT).show();
                translateNextMessage.run();
                return;
            }
            // Установка content для корректного отображения
            message.setContent(textToTranslate);
        } else {
            textToTranslate = message.getContent();
            if (textToTranslate == null || textToTranslate.trim().isEmpty()) {
                Log.e(TAG, "No content available for translation for message id=" + message.getId());
                Toast.makeText(context, "No content available for translation", Toast.LENGTH_SHORT).show();
                translateNextMessage.run();
                return;
            }
        }

        Log.d(TAG, "Translating message id=" + message.getId() + " to language=" + translationLanguage + ", text=" + textToTranslate);

        Call<String> call = apiService.translateMessage(new MessageRequest(textToTranslate, translationLanguage));
        call.enqueue(new Callback<String>() {
            @Override
            public void onResponse(Call<String> call, Response<String> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String translatedContent = response.body();
                    Log.d(TAG, "Translated content: " + translatedContent);
                    if (translatedContent.startsWith("\"") && translatedContent.endsWith("\"")) {
                        translatedContent = translatedContent.substring(1, translatedContent.length() - 1);
                    }
                    Log.d(TAG, "Parsed translated content: " + translatedContent);
                    message.setTranslatedContent(translatedContent);
                    message.setTranslationLanguage(translationLanguage);
                    // Устанавливаем showOriginal в зависимости от типа сообщения
                    message.setShowOriginal("text".equalsIgnoreCase(message.getMessageType())); // true для текстовых, false для аудио

                    SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = prefs.edit();
                    String key = "translation_" + chatId + "_" + message.getId();
                    editor.putString(key, translatedContent);
                    editor.putString(key + "_language", translationLanguage);
                    editor.apply();
                    Log.d(TAG, "Saved translation locally for message id=" + message.getId() + ", showOriginal=" + message.isShowOriginal());

                    apiService.saveTranslation(message.getId(), new TranslationUpdateRequest(translatedContent, translationLanguage))
                            .enqueue(new Callback<Void>() {
                                @Override
                                public void onResponse(Call<Void> call, Response<Void> response) {
                                    if (response.isSuccessful()) {
                                        Log.d(TAG, "Translation saved on server for message id=" + message.getId());
                                    } else {
                                        Log.e(TAG, "Failed to save translation on server: code=" + response.code());
                                    }
                                }

                                @Override
                                public void onFailure(Call<Void> call, Throwable t) {
                                    Log.e(TAG, "Error saving translation on server: " + t.getMessage());
                                }
                            });
                    ((android.app.Activity) context).runOnUiThread(() -> {
                        messageAdapter.notifyDataSetChanged();
                        Log.d(TAG, "UI updated for message id=" + message.getId() + ", showOriginal=" + message.isShowOriginal());
                        translateNextMessage.run();
                    });
                } else {
                    Log.e(TAG, "Translation failed for message id=" + message.getId() + ": code=" + response.code());
                    translateNextMessage.run();
                }
            }

            @Override
            public void onFailure(Call<String> call, Throwable t) {
                Log.e(TAG, "Translation error for message id=" + message.getId() + ": " + t.getMessage());
                translateNextMessage.run();
            }
        });
    }

    public static boolean isMessageNew(Context context, Message message, long chatId) {
        TranslationSettingsFragment.TranslationMode translationMode = ((GroupChatsActivity) context).getTranslationMode();
        if (translationMode == TranslationSettingsFragment.TranslationMode.ALL) {
            Log.d(TAG, "Translation mode is ALL, message id=" + message.getId() + " considered new");
            return true;
        }
        SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
        String enabledTimestamp = prefs.getString("translation_enabled_timestamp_" + chatId, null);
        if (enabledTimestamp == null) {
            Log.w(TAG, "No translation enabled timestamp for message id=" + message.getId() + ", skipping translation");
            return false;
        }
        String messageTimestamp = message.getTimestamp();
        if (messageTimestamp == null || messageTimestamp.isEmpty()) {
            Log.w(TAG, "Message id=" + message.getId() + " has no timestamp, skipping translation");
            return false;
        }
        try {
            ZonedDateTime messageTime = ZonedDateTime.parse(messageTimestamp, DateTimeFormatter.ISO_ZONED_DATE_TIME);
            ZonedDateTime enabledTime = ZonedDateTime.parse(enabledTimestamp, DateTimeFormatter.ISO_ZONED_DATE_TIME);
            boolean isNew = messageTime.isAfter(enabledTime);
            Log.d(TAG, "Message id=" + message.getId() + ", messageTime=" + messageTimestamp +
                    ", enabledTime=" + enabledTimestamp + ", isNew=" + isNew);
            return isNew;
        } catch (DateTimeParseException e) {
            Log.e(TAG, "Error parsing timestamps for message id=" + message.getId() + ": " + e.getMessage());
            return false;
        }
    }

    public static void updateSpellCheckIndicator(Context context, TextView spellCheckIndicator, boolean isSpellCheckEnabled) {
        if (spellCheckIndicator == null) {
            Log.w(TAG, "spellCheckIndicator is null, skipping update");
            return;
        }
        if (isSpellCheckEnabled) {
            spellCheckIndicator.setText("SC");
            spellCheckIndicator.setTextColor(ContextCompat.getColor(context, R.color.colorPrimary));
            spellCheckIndicator.setVisibility(View.VISIBLE);
        } else {
            spellCheckIndicator.setVisibility(View.GONE);
        }
    }

    public static void updateSpellCheckIcon(Context context, TextView spellCheckIconView, boolean isSpellCheckEnabled) {
        if (spellCheckIconView == null) {
            Log.w(TAG, "spellCheckIconView is null, skipping update");
            return;
        }
        SpannableString spannable = new SpannableString("✍️");
        int color = ContextCompat.getColor(context, isSpellCheckEnabled ? R.color.colorPrimary : android.R.color.darker_gray);
        spannable.setSpan(new ForegroundColorSpan(color), 0, spannable.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        spellCheckIconView.setText(spannable);
    }

    public static void spellCheckMessage(Context context, String content, ApiService apiService, Callback<String> callback) {
        if (content == null || content.trim().isEmpty()) {
            callback.onResponse(null, Response.success(content));
            return;
        }
        MessageRequest request = new MessageRequest(content, "en");
        Call<String> call = apiService.spellCheckMessage(request);
        call.enqueue(new Callback<String>() {
            @Override
            public void onResponse(Call<String> call, Response<String> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String correctedContent = response.body();
                    if (correctedContent.startsWith("\"") && correctedContent.endsWith("\"")) {
                        correctedContent = correctedContent.substring(1, correctedContent.length() - 1);
                    }
                    Log.d(TAG, "Spell checked content: " + correctedContent);
                    callback.onResponse(call, Response.success(correctedContent));
                } else {
                    Log.e(TAG, "Spell check failed: code=" + response.code());
                    ((android.app.Activity) context).runOnUiThread(() -> Toast.makeText(context,
                            "Failed to spell check message. Sending original text.",
                            Toast.LENGTH_SHORT).show());
                    callback.onResponse(call, Response.success(content));
                }
            }

            @Override
            public void onFailure(Call<String> call, Throwable t) {
                Log.e(TAG, "Spell check error: " + t.getMessage());
                ((android.app.Activity) context).runOnUiThread(() -> Toast.makeText(context,
                        "Failed to spell check message. Sending original text.",
                        Toast.LENGTH_SHORT).show());
                callback.onResponse(call, Response.success(content));
            }
        });
    }

    public static void clearTranscriptions(Context context, List<Message> messages, long chatId, GroupMessageAdapter messageAdapter) {
        SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (Message message : messages) {
            if ("audio".equals(message.getMessageType())) {
                message.setTranscribedContent(null);
                editor.remove("transcription_" + chatId + "_" + message.getId());
                Log.d(TAG, "Cleared transcription for message id=" + message.getId());
            }
        }
        editor.apply();
        messageAdapter.notifyDataSetChanged();
    }

    public static void transcribeExistingMessages(Context context, List<Message> messages, boolean isTranscriptionEnabled,
                                                  TranscriptionSettingsFragment.TranscriptionMode transcriptionMode, long chatId, List<Message> transcriptionQueue,
                                                  Runnable transcribeNextMessage) {
        if (!isTranscriptionEnabled || transcriptionMode == TranscriptionSettingsFragment.TranscriptionMode.SELECTIVELY) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
        for (Message message : messages) {
            if ("audio".equals(message.getMessageType()) && message.getTranscribedContent() == null) {
                if (transcriptionMode == TranscriptionSettingsFragment.TranscriptionMode.NEW_ONLY) {
                    String transcriptionTimestamp = prefs.getString("transcription_enabled_timestamp_" + chatId, null);
                    if (transcriptionTimestamp != null && isMessageNewerThanTimestamp(message, transcriptionTimestamp)) {
                        transcriptionQueue.add(message);
                        Log.d(TAG, "Added message id=" + message.getId() + " to transcription queue (NEW_ONLY)");
                    }
                } else {
                    transcriptionQueue.add(message);
                    Log.d(TAG, "Added message id=" + message.getId() + " to transcription queue (ALL)");
                }
            }
        }
        transcribeNextMessage.run();
    }

    public static void transcribeMessage(Context context, Message message, long chatId, ApiService apiService,
                                         GroupMessageAdapter messageAdapter, boolean isTranslationEnabled, String translationLanguage,
                                         TranscriptionSettingsFragment.TranscriptionMode transcriptionMode, Runnable onComplete) {
        if (message.getFileUrl() == null || message.getFileUrl().isEmpty()) {
            Log.e(TAG, "No fileUrl for message id=" + message.getId() + ", fileUrl=" + message.getFileUrl());
            Toast.makeText(context, "No audio file available for transcription", Toast.LENGTH_SHORT).show();
            onComplete.run();
            return;
        }
        Map<String, String> request = new HashMap<>();
        request.put("fileUrl", message.getFileUrl());
        Log.d(TAG, "Sending transcription request for message id=" + message.getId() + ", fileUrl=" + message.getFileUrl());
        apiService.transcribeAudio(message.getId(), request).enqueue(new Callback<String>() {
            @Override
            public void onResponse(Call<String> call, Response<String> response) {
                Log.d(TAG, "Transcription response for message id=" + message.getId() + ": code=" + response.code() + ", body=" + response.body());
                if (response.isSuccessful() && response.body() != null) {
                    String transcribedContent = response.body();
                    if (!transcribedContent.isEmpty()) {
                        boolean wasDelivered = message.isDelivered();
                        boolean wasRead = message.isRead();
                        message.setTranscribedContent(transcribedContent);
                        message.setDelivered(wasDelivered);
                        message.setRead(wasRead);
                        SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putString("transcription_" + chatId + "_" + message.getId(), transcribedContent);
                        editor.putBoolean("delivered_" + chatId + "_" + message.getId(), wasDelivered);
                        editor.apply();
                        Log.d(TAG, "Transcription saved for message id=" + message.getId() + ": " + transcribedContent);
                        // Автоматический перевод транскрипции, если перевод включён
                        if (isTranslationEnabled) {
                            translateMessage(context, message, translationLanguage, chatId, messageAdapter, () -> {
                                Log.d(TAG, "Translation completed for transcribed message id=" + message.getId());
                                message.setShowOriginal(false); // Показываем перевод сразу
                                messageAdapter.notifyDataSetChanged();
                                onComplete.run();
                            });
                        } else {
                            message.setShowOriginal(true); // Показываем оригинал, если перевод выключен
                            messageAdapter.notifyDataSetChanged();
                            onComplete.run();
                        }
                    } else {
                        Log.w(TAG, "Empty transcription received for message id=" + message.getId());
                        Toast.makeText(context, "Transcription is empty", Toast.LENGTH_SHORT).show();
                        onComplete.run();
                    }
                } else {
                    Log.e(TAG, "Failed to transcribe message id=" + message.getId() + ": code=" + response.code());
                    Toast.makeText(context, "Failed to transcribe audio", Toast.LENGTH_SHORT).show();
                    onComplete.run();
                }
            }

            @Override
            public void onFailure(Call<String> call, Throwable t) {
                Log.e(TAG, "Error transcribing message id=" + message.getId() + ": " + t.getMessage());
                Toast.makeText(context, "Error transcribing audio: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                onComplete.run();
            }
        });
    }

    public static void transcribeNextMessage(List<Message> transcriptionQueue, boolean isTranscribing, Runnable transcribeMessage) {
        Log.d(TAG, "transcribeNextMessage called, queue size=" + transcriptionQueue.size() + ", isTranscribing=" + isTranscribing);
        if (!transcriptionQueue.isEmpty() && !isTranscribing && transcribeMessage != null) {
            transcribeMessage.run();
        } else {
            Log.w(TAG, "Skipping transcribeNextMessage: queueEmpty=" + transcriptionQueue.isEmpty() +
                    ", isTranscribing=" + isTranscribing + ", transcribeMessage=" + (transcribeMessage == null ? "null" : "not null"));
        }
    }

    public static boolean isMessageNewerThanTimestamp(Message message, String timestamp) {
        if (timestamp == null || message.getTimestamp() == null) {
            return false;
        }
        try {
            ZonedDateTime messageTime = ZonedDateTime.parse(message.getTimestamp(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
            ZonedDateTime enableTime = ZonedDateTime.parse(timestamp, DateTimeFormatter.ISO_ZONED_DATE_TIME);
            return messageTime.isAfter(enableTime);
        } catch (DateTimeParseException e) {
            Log.e(TAG, "Error parsing timestamp for message id=" + message.getId() + ": " + e.getMessage());
            return false;
        }
    }

    public static void updateTranscriptionIndicator(Context context, TextView transcriptionIndicator, boolean isTranscriptionEnabled) {
        if (transcriptionIndicator == null) {
            Log.w(TAG, "transcriptionIndicator is null, skipping update");
            return;
        }
        transcriptionIndicator.setVisibility(isTranscriptionEnabled ? View.VISIBLE : View.GONE);
        Log.d(TAG, "Transcription indicator updated: visibility=" + (isTranscriptionEnabled ? "VISIBLE" : "GONE"));
    }

    public static void updateTranscriptionIcon(Context context, ImageView transcriptionIconView, boolean isTranscriptionEnabled) {
        if (transcriptionIconView == null) {
            Log.w(TAG, "transcriptionIconView is null, skipping update");
            return;
        }
        transcriptionIconView.setImageResource(R.drawable.ic_transcription);
        transcriptionIconView.setColorFilter(ContextCompat.getColor(context, isTranscriptionEnabled ? R.color.tts_accent : R.color.grey_500));
        Log.d(TAG, "Transcription icon updated: enabled=" + isTranscriptionEnabled);
    }
}

