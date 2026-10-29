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
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.Message;
import com.example.unlimitedpossibilities12.api.MessageRequest;
import com.example.unlimitedpossibilities12.api.TranslationUpdateRequest;
import com.example.unlimitedpossibilities12.messenger.MessageAdapter;
import com.example.unlimitedpossibilities12.messenger.TranscriptionSettingsFragment;
import com.example.unlimitedpossibilities12.messenger.TranslationSettingsFragment;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class PrivateChatTranslationAndSpellCheckHelper {
    private static final String TAG = "PrivateChatTranslation";

    public static void updateTranslationIndicator(Context context, TextView translationTextView, boolean isTranslationEnabled, String translationLanguage) {
        if (translationTextView == null) {
            Log.w(TAG, "translationTextView is null, skipping update");
            return;
        }
        if (isTranslationEnabled) {
            String languageCode = translationLanguage.equals("auto") ? "AUTO" :
                    (translationLanguage.length() >= 2 ? translationLanguage.substring(0, 2).toUpperCase() : translationLanguage.toUpperCase());
            translationTextView.setText(languageCode);
            translationTextView.setTextColor(ContextCompat.getColor(context, R.color.colorPrimary));
            translationTextView.setVisibility(View.VISIBLE);
        } else {
            translationTextView.setVisibility(View.GONE);
        }
    }

    public static void updateTranslationIcon(Context context, ImageView translationIconView, boolean isTranslationEnabled) {
        if (translationIconView != null) {
            translationIconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_globe));
            translationIconView.setColorFilter(ContextCompat.getColor(context, isTranslationEnabled ? R.color.colorPrimary : android.R.color.darker_gray));
        }
    }

    public static void translateExistingMessages(Context context, List<Message> messages, boolean isTranslationEnabled, String translationLanguage, boolean translateNewOnly, long chatId, List<Message> translationQueue, MessageAdapter messageAdapter, Runnable translateNextMessage) {
        if (!isTranslationEnabled) {
            Log.d(TAG, "Translation disabled, clearing existing translations");
            clearTranslations(context, messages, chatId, translationQueue, messageAdapter);
            return;
        }
        translationQueue.clear();
        Log.d(TAG, "Cleared translation queue");
        // Добавлено: Инициализация списка переведённых сообщений
        List<Message> translatedMessages = new ArrayList<>();
        for (Message message : messages) {
            if (!isMessageNew(context, message, translateNewOnly, chatId)) {
                Log.d(TAG, "Skipping message id=" + message.getId() + " (not new)");
                continue;
            }
            if (message.getTranslatedContent() == null || !translationLanguage.equals(message.getTranslationLanguage())) {
                translationQueue.add(message);
                Log.d(TAG, "Added message id=" + message.getId() + " to translation queue");
            } else {
                Log.d(TAG, "Skipping message id=" + message.getId() + " (already translated to " + message.getTranslationLanguage() + ")");
            }
        }
        Log.d(TAG, "Translation queue size: " + translationQueue.size());
        if (!translationQueue.isEmpty()) {
            // Изменено: Передаём translatedMessages в translateNextMessage
            translateNextMessage.run();
        } else {
            Log.d(TAG, "Translation queue is empty, no translations needed");
        }
    }

    public static void translateNextMessage(List<Message> translationQueue, boolean isTranslating, Runnable translateMessage, MessageAdapter messageAdapter, List<Message> messages, Context context, List<Message> translatedMessages) {
        if (translationQueue.isEmpty() || isTranslating) {
            if (translationQueue.isEmpty()) {
                Log.d(TAG, "Translation queue is empty, no further translations needed");
                if (!translatedMessages.isEmpty()) {
                    // Добавлено: Обновляем адаптер через DiffUtil
                    ((android.app.Activity) context).runOnUiThread(() -> {
                        messageAdapter.updateMessages(new ArrayList<>(messages));
                        Log.d(TAG, "Adapter updated with DiffUtil after translating " + translatedMessages.size() + " messages");
                        translatedMessages.clear();
                    });
                }
            } else {
                Log.d(TAG, "Translation in progress, waiting for completion");
            }
            return;
        }
        translateMessage.run();
    }

    public static void translateMessage(Context context, Message message, String translationLanguage, long chatId, MessageAdapter messageAdapter, List<Message> messages, List<Message> translatedMessages, Runnable onComplete) {
        ApiService apiService = com.example.unlimitedpossibilities12.api.ApiClient.getApiService();
        String textToTranslate = "audio".equalsIgnoreCase(message.getMessageType()) ?
                message.getTranscribedContent() : message.getContent();
        if (textToTranslate == null || textToTranslate.isEmpty()) {
            Log.w(TAG, "No content to translate for message id=" + message.getId() + ", messageType=" + message.getMessageType());
            onComplete.run();
            return;
        }
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

                    SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = prefs.edit();
                    String key = "translation_" + chatId + "_" + message.getId();
                    editor.putString(key, translatedContent);
                    editor.putString(key + "_language", translationLanguage);
                    editor.apply();
                    Log.d(TAG, "Saved translation locally for message id=" + message.getId());

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

                    final String finalTranslatedContent = translatedContent;

                    ((android.app.Activity) context).runOnUiThread(() -> {
                        int position = messages.indexOf(message);
                        if (position != -1) {
                            RecyclerView recyclerView = ((android.app.Activity) context).findViewById(R.id.message_list);
                            if (recyclerView == null) {
                                Log.e(TAG, "RecyclerView is null for message id=" + message.getId());
                                synchronized (translatedMessages) {
                                    translatedMessages.add(message);
                                }
                                onComplete.run();
                                return;
                            }
                            MessageAdapter.MessageViewHolder holder = (MessageAdapter.MessageViewHolder) recyclerView.findViewHolderForAdapterPosition(position);
                            if (holder != null) {
                                if ("audio".equalsIgnoreCase(message.getMessageType())) {
                                    // Для аудиосообщений: устанавливаем перевод в contentTextView, скрываем translatedContentTextView
                                    message.setShowOriginal(false);
                                    holder.setContentText(finalTranslatedContent);
                                    holder.hideTranslatedContentTextView();
                                    Log.d(TAG, "Audio message id=" + message.getId() + ": set translated content to contentTextView, translatedContentTextView hidden");
                                } else {
                                    // Для текстовых сообщений: устанавливаем перевод в translatedContentTextView
                                    holder.setTranslatedContentText(finalTranslatedContent);
                                    holder.setContentText(message.isShowOriginal() ? message.getContent() : finalTranslatedContent);
                                    Log.d(TAG, "Text/file message id=" + message.getId() + ": set translated content to translatedContentTextView, showOriginal=" + message.isShowOriginal());
                                }
                                // Устанавливаем видимость translateIcon только для текстовых/файловых сообщений
                                holder.setTranslateIconVisibility("text".equalsIgnoreCase(message.getMessageType()) ||
                                        ("file".equalsIgnoreCase(message.getMessageType()) &&
                                                message.getContent() != null && !message.getContent().isEmpty()) ?
                                        View.VISIBLE : View.GONE);
                                Log.d(TAG, "Updated translated content for message id=" + message.getId() + " at position=" + position +
                                        ", messageType=" + message.getMessageType());
                            } else {
                                Log.w(TAG, "ViewHolder not found for message id=" + message.getId() + " at position=" + position +
                                        ", recyclerView child count=" + recyclerView.getChildCount() +
                                        ", isComputingLayout=" + recyclerView.isComputingLayout());
                            }
                            synchronized (translatedMessages) {
                                translatedMessages.add(message);
                            }
                        } else {
                            Log.w(TAG, "Message id=" + message.getId() + " not found in messages list");
                        }
                        onComplete.run();
                    });
                } else {
                    Log.e(TAG, "Translation failed for message id=" + message.getId() + ": code=" + response.code());
                    onComplete.run();
                }
            }

            @Override
            public void onFailure(Call<String> call, Throwable t) {
                Log.e(TAG, "Translation error for message id=" + message.getId() + ": " + t.getMessage());
                onComplete.run();
            }
        });
    }

    public static void clearTranslations(Context context, List<Message> messages, long chatId, List<Message> translationQueue, MessageAdapter messageAdapter) {
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

    public static boolean isMessageNew(Context context, Message message, boolean translateNewOnly, long chatId) {
        if (!translateNewOnly) {
            Log.d(TAG, "translateNewOnly is false, message id=" + message.getId() + " considered new");
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
                    ((android.app.Activity) context).runOnUiThread(() -> android.widget.Toast.makeText(context,
                            "Failed to spell check message. Sending original text.",
                            android.widget.Toast.LENGTH_SHORT).show());
                    callback.onResponse(call, Response.success(content));
                }
            }

            @Override
            public void onFailure(Call<String> call, Throwable t) {
                Log.e(TAG, "Spell check error: " + t.getMessage());
                ((android.app.Activity) context).runOnUiThread(() -> android.widget.Toast.makeText(context,
                        "Failed to spell check message. Sending original text.",
                        android.widget.Toast.LENGTH_SHORT).show());
                callback.onResponse(call, Response.success(content));
            }
        });
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
        transcriptionIconView.setImageResource(isTranscriptionEnabled ? R.drawable.ic_transcription : R.drawable.ic_transcription);
        transcriptionIconView.setColorFilter(ContextCompat.getColor(context, isTranscriptionEnabled ? R.color.tts_accent : R.color.grey_500));
        Log.d(TAG, "Transcription icon updated: enabled=" + isTranscriptionEnabled);
    }

    public static void clearTranscriptions(Context context, List<Message> messages, long chatId, MessageAdapter messageAdapter) {
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

    public static void transcribeExistingMessages(Context context, List<Message> messages, boolean isTranscriptionEnabled, TranscriptionSettingsFragment.TranscriptionMode transcriptionMode, long chatId, List<Message> transcriptionQueue, MessageAdapter messageAdapter, Runnable transcribeNextMessage) {
        if (!isTranscriptionEnabled || transcriptionMode == TranscriptionSettingsFragment.TranscriptionMode.SELECTIVELY) {
            Log.d(TAG, "Transcription disabled or in SELECTIVE mode, skipping transcription");
            return;
        }
        transcriptionQueue.clear();
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
        Log.d(TAG, "Transcription queue size: " + transcriptionQueue.size());
        if (!transcriptionQueue.isEmpty()) {
            transcribeNextMessage.run();
        }
    }

    public static boolean isMessageNewerThanTimestamp(Message message, String timestamp) {
        if (timestamp == null || message.getTimestamp() == null) {
            Log.w(TAG, "Timestamp is null for message id=" + message.getId() + " or transcription enabled timestamp is null");
            return false;
        }
        try {
            ZonedDateTime messageTime = ZonedDateTime.parse(message.getTimestamp(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
            ZonedDateTime enableTime = ZonedDateTime.parse(timestamp, DateTimeFormatter.ISO_ZONED_DATE_TIME);
            boolean isNewer = messageTime.isAfter(enableTime);
            Log.d(TAG, "Message id=" + message.getId() + " isNewer=" + isNewer);
            return isNewer;
        } catch (DateTimeParseException e) {
            Log.e(TAG, "Error parsing timestamp for message id=" + message.getId() + ": " + e.getMessage());
            return false;
        }
    }

    public static void transcribeNextMessage(List<Message> transcriptionQueue, boolean isTranscribing, Runnable transcribeMessage, MessageAdapter messageAdapter) {
        Log.d(TAG, "transcribeNextMessage called, queue size=" + transcriptionQueue.size() + ", isTranscribing=" + isTranscribing);
        if (transcriptionQueue.isEmpty() || isTranscribing || transcribeMessage == null) {
            if (transcriptionQueue.isEmpty()) {
                Log.d(TAG, "Transcription queue is empty, no further transcriptions needed");
            } else if (isTranscribing) {
                Log.d(TAG, "Transcription in progress, waiting for completion");
            } else {
                Log.w(TAG, "transcribeMessage is null, skipping transcription");
            }
            return;
        }
        transcribeMessage.run();
    }

    public static void transcribeMessage(Context context, Message message, long chatId, MessageAdapter messageAdapter, List<Message> messages, ApiService apiService, Runnable onComplete) {
        if (message.getFileUrl() == null || message.getFileUrl().isEmpty()) {
            Log.e(TAG, "No fileUrl for message id=" + message.getId() + ", fileUrl=" + message.getFileUrl());
            ((android.app.Activity) context).runOnUiThread(() -> android.widget.Toast.makeText(context, "No audio file available for transcription", android.widget.Toast.LENGTH_SHORT).show());
            onComplete.run();
            return;
        }
        Map<String, String> request = new HashMap<>();
        request.put("fileUrl", message.getFileUrl());
        Log.d(TAG, "Sending transcription request for message id=" + message.getId() + ", fileUrl=" + message.getFileUrl() + ", delivered=" + message.isDelivered() + ", read=" + message.isRead());
        apiService.transcribeAudio(message.getId(), request).enqueue(new Callback<String>() {
            @Override
            public void onResponse(Call<String> call, Response<String> response) {
                Log.d(TAG, "Transcription response for message id=" + message.getId() + ": code=" + response.code() + ", body=" + response.body());
                if (response.isSuccessful() && response.body() != null) {
                    String transcribedContent = response.body();
                    if (!transcribedContent.isEmpty()) {
                        // Сохранение статусов доставки и чтения
                        boolean wasDelivered = message.isDelivered();
                        boolean wasRead = message.isRead();
                        message.setTranscribedContent(transcribedContent);
                        // Восстановление статусов доставки и чтения
                        message.setDelivered(wasDelivered);
                        message.setRead(wasRead);
                        SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putString("transcription_" + chatId + "_" + message.getId(), transcribedContent);
                        editor.putBoolean("delivered_" + chatId + "_" + message.getId(), wasDelivered);
                        editor.apply();
                        Log.d(TAG, "Transcription saved for message id=" + message.getId() + ": " + transcribedContent + ", delivered=" + message.isDelivered() + ", read=" + message.isRead());

                        // Обновление сообщения в списке
                        int index = messages.indexOf(message);
                        if (index != -1) {
                            messages.set(index, message);
                            ((android.app.Activity) context).runOnUiThread(() -> {
                                messageAdapter.notifyItemChanged(index);
                                Log.d(TAG, "Notified adapter for message id=" + message.getId());
                            });
                        }

                        // Автоматический перевод транскрипции, если включён перевод
                        SharedPreferences prefsTranslate = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
                        boolean isTranslationEnabled = prefsTranslate.getBoolean("translation_enabled_" + chatId, false);
                        String translationModeStr = prefsTranslate.getString("translation_mode_" + chatId, "ALL");
                        String translationLanguage = prefsTranslate.getString("translation_language_" + chatId, "auto");
                        TranslationSettingsFragment.TranslationMode translationMode;
                        try {
                            translationMode = TranslationSettingsFragment.TranslationMode.valueOf(translationModeStr);
                        } catch (IllegalArgumentException e) {
                            Log.w(TAG, "Invalid translation mode for message id=" + message.getId() + ": " + translationModeStr + ", defaulting to ALL");
                            translationMode = TranslationSettingsFragment.TranslationMode.ALL;
                        }
                        if (isTranslationEnabled && translationMode != TranslationSettingsFragment.TranslationMode.SELECTIVELY) {
                            Log.d(TAG, "Initiating translation for transcribed message id=" + message.getId() + ", language=" + translationLanguage);
                            translateMessage(context, message, translationLanguage, chatId, messageAdapter, messages, new ArrayList<>(), () -> Log.d(TAG, "Translation completed for transcribed message id=" + message.getId()));
                        }
                    } else {
                        Log.w(TAG, "Empty transcription received for message id=" + message.getId());
                        ((android.app.Activity) context).runOnUiThread(() -> android.widget.Toast.makeText(context, "Transcription is empty", android.widget.Toast.LENGTH_SHORT).show());
                    }
                } else {
                    Log.e(TAG, "Failed to transcribe message id=" + message.getId() + ": code=" + response.code() + ", message=" + response.message());
                    ((android.app.Activity) context).runOnUiThread(() -> android.widget.Toast.makeText(context, "Failed to transcribe audio: " + response.message(), android.widget.Toast.LENGTH_SHORT).show());
                }
                onComplete.run();
            }

            @Override
            public void onFailure(Call<String> call, Throwable t) {
                Log.e(TAG, "Error transcribing message id=" + message.getId() + ": " + t.getMessage());
                ((android.app.Activity) context).runOnUiThread(() -> android.widget.Toast.makeText(context, "Error transcribing audio: " + t.getMessage(), android.widget.Toast.LENGTH_SHORT).show());
                onComplete.run();
            }
        });
    }
}
