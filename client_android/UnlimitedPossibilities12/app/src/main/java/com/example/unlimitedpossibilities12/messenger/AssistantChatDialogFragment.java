package com.example.unlimitedpossibilities12.messenger;

import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.AuthToken;
import com.example.unlimitedpossibilities12.messenger.language.LanguageAdapter;
import com.example.unlimitedpossibilities12.messenger.language.Language;
import com.example.unlimitedpossibilities12.R;

import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AssistantChatDialogFragment extends DialogFragment {

    private boolean isFullScreen = false;
    private boolean isTranslatorMode = false;

    // Старые поля
    private LinearLayout rootLayout;
    private LinearLayout messagesContainer;
    private EditText inputEditText;
    private Button expandButton;
    private Button sendButton;
    private View typingIndicator = null;
    private ObjectAnimator dot1Anim, dot2Anim, dot3Anim;

    // Новые поля для переводчика
    private ImageButton btnMoreOptions;
    private ScrollView chatContainer;
    private LinearLayout translatorContainer;
    private LinearLayout inputContainer; // чтобы скрывать поле ввода в режиме переводчика
    private Spinner languageSpinner;
    private ImageButton btnRecord;
    private TextView tvOriginalText;
    private TextView tvTranslatedText;
    private TextView tvRecordingTimer; // для будущего таймера записи
    private LanguageAdapter languageAdapter;
    private String selectedLanguageCode = "auto"; // по умолчанию
    private boolean isRecording = false;
    private MediaRecorder mediaRecorder;
    private File audioFile;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private int recordingSeconds = 0;
    private MediaPlayer mediaPlayer;
    private androidx.cardview.widget.CardView btnRecordContainer;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_assistant_chat, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Старые view
        rootLayout = view.findViewById(R.id.assistant_root_layout);
        messagesContainer = view.findViewById(R.id.assistant_messages);
        inputEditText = view.findViewById(R.id.assistant_input);
        sendButton = view.findViewById(R.id.assistant_send);
        expandButton = view.findViewById(R.id.assistant_expand);

        // Новые view
        btnMoreOptions = view.findViewById(R.id.btn_more_options);
        chatContainer = view.findViewById(R.id.chat_container);
        translatorContainer = view.findViewById(R.id.translator_container);
        inputContainer = view.findViewById(R.id.input_container);
        languageSpinner = view.findViewById(R.id.language_spinner);
        btnRecord = view.findViewById(R.id.btn_record);
        tvOriginalText = view.findViewById(R.id.tv_original_text);
        tvTranslatedText = view.findViewById(R.id.tv_translated_text);
        tvRecordingTimer = view.findViewById(R.id.tv_recording_timer);

        // Меню три точки
        btnMoreOptions.setOnClickListener(v -> showOptionsMenu());
        ImageButton btnPlayOriginal = view.findViewById(R.id.btn_play_original);
        ImageButton btnPlayTranslated = view.findViewById(R.id.btn_play_translated);
        btnRecordContainer = view.findViewById(R.id.record_button_container);

        // Анимация нажатия:
        btnRecord.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
            return false;
        });

        // Инициализация Spinner (заглушка, потом подключишь свой LanguageAdapter)
        setupLanguageSpinner();

        // Начальное приветствие (только в режиме чата)
        addMessage("Hi! I'm your smart assistant.\nAsk anything ⚡", false);

        // Кнопка отправки (остаётся только для чата)
        sendButton.setOnClickListener(v -> {
            String text = inputEditText.getText().toString().trim();
            if (text.isEmpty()) return;

            addMessage(text, true);
            inputEditText.setText("");

            showTypingIndicator();

            new Thread(() -> {
                final String response = sendToGPT(text);
                requireActivity().runOnUiThread(() -> {
                    hideTypingIndicator();
                    addMessage(response, false);
                });
            }).start();
        });

        // Повтор оригинала
        btnPlayOriginal.setOnClickListener(v -> {
            String original = tvOriginalText.getText().toString().trim();
            if (!original.isEmpty()) {
                speakText(original, "auto"); // или "ru" / другой язык, если знаешь
            } else {
                Toast.makeText(requireContext(), "There is no text to listen to", Toast.LENGTH_SHORT).show();
            }
        });

        // Повтор перевода
        btnPlayTranslated.setOnClickListener(v -> {
            String translated = tvTranslatedText.getText().toString().trim();
            if (!translated.isEmpty()) {
                speakText(translated, selectedLanguageCode);
            } else {
                Toast.makeText(requireContext(), "There is no translation available for listening", Toast.LENGTH_SHORT).show();
            }
        });

        // Кнопка Развернуть / Свернуть
        expandButton.setOnClickListener(v -> toggleFullScreen());
    }

    private void showOptionsMenu() {
        PopupMenu popup = new PopupMenu(requireContext(), btnMoreOptions);
        MenuInflater inflater = popup.getMenuInflater();
        inflater.inflate(R.menu.assistant_menu, popup.getMenu());

        // Находим пункт меню по ID
        MenuItem item = popup.getMenu().findItem(R.id.action_voice_translator);

        if (isTranslatorMode) {
            // В режиме переводчика меняем текст и иконку (если хочешь другую иконку)
            item.setTitle("Return to chat");
            // item.setIcon(R.drawable.ic_arrow_back); // ← если хочешь иконку "назад", раскомментируй
        } else {
            // В режиме чата — оставляем как есть
            item.setTitle("Voice translator");
            item.setIcon(R.drawable.ic_mic_white);
        }

        popup.setOnMenuItemClickListener(menuItem -> {
            if (menuItem.getItemId() == R.id.action_voice_translator) {
                if (isTranslatorMode) {
                    switchBackToChat();
                } else {
                    switchToTranslatorMode();
                }
                return true;
            }
            return false;
        });

        popup.show();
    }

    private void switchToTranslatorMode() {
        isTranslatorMode = true;

        // Скрываем чат и поле ввода
        chatContainer.setVisibility(View.GONE);
        inputContainer.setVisibility(View.GONE);
        // Показываем переводчик
        translatorContainer.setVisibility(View.VISIBLE);

        // Меняем заголовок
        TextView title = getView().findViewById(R.id.assistant_title);
        if (title != null) {
            title.setText("Voice translator");
        }

        // Кнопка записи (пока заглушка)
        btnRecord.setOnClickListener(v -> toggleRecording());
    }

    private void switchBackToChat() {
        isTranslatorMode = false;

        // Показываем чат и поле ввода
        chatContainer.setVisibility(View.VISIBLE);
        inputContainer.setVisibility(View.VISIBLE);
        // Скрываем переводчик
        translatorContainer.setVisibility(View.GONE);

        // Возвращаем заголовок
        TextView title = getView().findViewById(R.id.assistant_title);
        if (title != null) {
            title.setText("AI Assistant");
        }
    }

    private void setupLanguageSpinner() {
        // Создаём список языков (можно вынести в отдельный метод или статический список)
        List<Language> languages = getLanguageList();

        languageAdapter = new LanguageAdapter(requireContext(), R.layout.spinner_item_translate, languages);
        languageAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_translate);

        languageSpinner.setAdapter(languageAdapter);
        SpinnerStyle.roundDropdown(languageSpinner);

        // Выбираем по умолчанию "Auto"
        languageSpinner.setSelection(0);

        // Слушатель выбора языка
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedLanguageCode = languageAdapter.getLanguageCode(position);
                Toast.makeText(requireContext(), "Language selected: " + selectedLanguageCode, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedLanguageCode = "auto";
            }
        });
    }

    private void toggleRecording() {
        if (isRecording) {
            stopRecordingAndSend();
        } else {
            startRecording();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Старые методы без изменений
    // ──────────────────────────────────────────────────────────────────────────────

    private void toggleFullScreen() {
        isFullScreen = !isFullScreen;

        Dialog dialog = getDialog();
        if (dialog != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                WindowManager.LayoutParams params = window.getAttributes();
                if (isFullScreen) {
                    params.width = WindowManager.LayoutParams.MATCH_PARENT;
                    params.height = WindowManager.LayoutParams.MATCH_PARENT;
                    params.gravity = Gravity.BOTTOM;
                    expandButton.setText("Collapse ↓");
                } else {
                    params.width = WindowManager.LayoutParams.MATCH_PARENT;
                    params.height = getResources().getDisplayMetrics().heightPixels / 2;
                    params.gravity = Gravity.BOTTOM;
                    expandButton.setText("Expand ↑");
                }
                window.setAttributes(params);
            }
        }
    }

    private void addMessage(String text, boolean isUser) {
        TextView messageView = new TextView(requireContext());
        messageView.setText(text);
        messageView.setTextColor(getResources().getColor(android.R.color.white));
        messageView.setTextSize(16);
        messageView.setPadding(32, 24, 32, 24);
        messageView.setBackgroundResource(isUser
                ? R.drawable.assistant_message_user
                : R.drawable.assistant_message_bubble);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 8, 0, 8);
        if (isUser) {
            params.gravity = Gravity.END;
        } else {
            params.gravity = Gravity.START;
        }
        messageView.setLayoutParams(params);
        messagesContainer.addView(messageView);

        ScrollView scrollView = (ScrollView) messagesContainer.getParent();
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private String sendToGPT(String prompt) {
        try {
            URL url = new URL("https://unlimitedpossibilities12.org/api/communicating_with_a_advanced_ai_model");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            // Запрос написан вручную и обходит ApiClient с его перехватчиком,
            // поэтому токен нужно подставить здесь — иначе сервер отвечает 401
            String bearer = AuthToken.bearer();
            if (bearer != null) conn.setRequestProperty("Authorization", bearer);
            conn.setDoOutput(true);

            // Склейка строками ломалась на переносах и кавычках внутри вопроса
            String jsonInput = new JSONObject().put("prompt", prompt).toString();
            OutputStream os = conn.getOutputStream();
            os.write(jsonInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String inputLine;
                StringBuilder response = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                return response.toString();
            } else {
                // Тело ошибки полезнее номера: по нему видно, истёк токен или
                // отказал сам OpenAI
                String body = "";
                try (BufferedReader err = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = err.readLine()) != null) sb.append(line);
                    body = sb.toString();
                } catch (Exception ignored) {
                }
                Log.e("AssistantChat", "GPT request failed: " + responseCode + " " + body);
                if (responseCode == 401) {
                    return "Session expired — sign in again";
                }
                return "Server error: " + responseCode;
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
                window.setGravity(Gravity.BOTTOM);
                window.setBackgroundDrawableResource(android.R.color.transparent);
            }
        }
    }

    private void showTypingIndicator() {
        if (typingIndicator != null) return;

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        typingIndicator = inflater.inflate(R.layout.item_typing_indicator, messagesContainer, false);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.START;
        params.setMargins(0, 8, 0, 8);

        messagesContainer.addView(typingIndicator, params);

        startDotsAnimation();

        ScrollView scrollView = (ScrollView) messagesContainer.getParent();
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void hideTypingIndicator() {
        if (typingIndicator != null) {
            stopDotsAnimation();
            messagesContainer.removeView(typingIndicator);
            typingIndicator = null;
        }
    }

    private void startDotsAnimation() {
        if (typingIndicator == null) return;

        View dot1 = typingIndicator.findViewById(R.id.dot1);
        View dot2 = typingIndicator.findViewById(R.id.dot2);
        View dot3 = typingIndicator.findViewById(R.id.dot3);

        dot1Anim = ObjectAnimator.ofFloat(dot1, "translationY", 0f, -10f, 0f);
        dot1Anim.setDuration(600);
        dot1Anim.setRepeatCount(ObjectAnimator.INFINITE);
        dot1Anim.setRepeatMode(ObjectAnimator.RESTART);

        dot2Anim = ObjectAnimator.ofFloat(dot2, "translationY", 0f, -10f, 0f);
        dot2Anim.setDuration(600);
        dot2Anim.setStartDelay(150);
        dot2Anim.setRepeatCount(ObjectAnimator.INFINITE);
        dot2Anim.setRepeatMode(ObjectAnimator.RESTART);

        dot3Anim = ObjectAnimator.ofFloat(dot3, "translationY", 0f, -10f, 0f);
        dot3Anim.setDuration(600);
        dot3Anim.setStartDelay(300);
        dot3Anim.setRepeatCount(ObjectAnimator.INFINITE);
        dot3Anim.setRepeatMode(ObjectAnimator.RESTART);

        dot1Anim.start();
        dot2Anim.start();
        dot3Anim.start();
    }

    private void stopDotsAnimation() {
        if (dot1Anim != null) dot1Anim.cancel();
        if (dot2Anim != null) dot2Anim.cancel();
        if (dot3Anim != null) dot3Anim.cancel();
    }

    // NEW: Получение списка языков
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

    private void startRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), "No microphone permission", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            audioFile = File.createTempFile("voice_translate", ".mp3", requireContext().getCacheDir());

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            btnRecordContainer.setCardBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark));
            btnRecord.setImageResource(android.R.drawable.ic_media_pause); // или другой иконки паузы

            // Таймер
            recordingSeconds = 0;
            tvRecordingTimer.setVisibility(View.VISIBLE);
            timerRunnable = () -> {
                recordingSeconds++;
                tvRecordingTimer.setText(String.format("%02d:%02d", recordingSeconds / 60, recordingSeconds % 60));
                handler.postDelayed(timerRunnable, 1000);
            };
            handler.postDelayed(timerRunnable, 1000);

            Toast.makeText(requireContext(), "Recording has started...", Toast.LENGTH_SHORT).show();

        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(requireContext(), "Recording error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopRecordingAndSend() {
        if (!isRecording) return;

        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
        } catch (Exception e) {
            e.printStackTrace();
        }

        isRecording = false;
        btnRecordContainer.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.green_500)); // или #22C55E
        btnRecord.setImageResource(R.drawable.ic_mic_white); // возвращаем микрофон
        tvRecordingTimer.setVisibility(View.GONE);
        handler.removeCallbacks(timerRunnable);

        if (audioFile == null || !audioFile.exists() || audioFile.length() < 1000) {
            Toast.makeText(requireContext(), "The recording is too short", Toast.LENGTH_SHORT).show();
            return;
        }

        // Отправляем на сервер
        sendVoiceToTranslator(audioFile);
    }

    private void sendVoiceToTranslator(File audioFile) {
        // Показываем прогресс
        Toast.makeText(requireContext(), "Processing...", Toast.LENGTH_SHORT).show();

        RequestBody requestFile = RequestBody.create(MediaType.parse("audio/mp3"), audioFile);
        MultipartBody.Part body = MultipartBody.Part.createFormData("audio", audioFile.getName(), requestFile);

        Call<Map<String, Object>> call = ApiClient.getApiService().translateVoice(
                body,
                selectedLanguageCode,
                "auto",
                true // withTts
        );

        call.enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    Map<String, Object> result = response.body();

                    String original = (String) result.get("original");
                    String translated = (String) result.get("translated");
                    String audioBase64 = (String) result.get("audioBase64");

                    tvOriginalText.setText(original != null ? original : "Not recognized");
                    tvTranslatedText.setText(translated != null ? translated : "Translation not completed");

                    // Воспроизведение TTS, если есть
                    if (audioBase64 != null && !audioBase64.isEmpty()) {
                        playBase64Audio(audioBase64);
                    }
                } else {
                    Toast.makeText(requireContext(), "Server error: " + response.code(), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                Toast.makeText(requireContext(), "Network error: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void playBase64Audio(String base64) {
        Log.d("TTS_DEBUG", "playBase64Audio: base64 length = " + (base64 != null ? base64.length() : "null"));

        if (base64 == null || base64.trim().isEmpty()) {
            Toast.makeText(requireContext(), "No audio from the server", Toast.LENGTH_LONG).show();
            return;
        }

        // 1. Обрезаем префикс data:audio/...base64,
        if (base64.startsWith("data:")) {
            base64 = base64.split(",")[1];
            Log.d("TTS_DEBUG", "Обрезан data: префикс");
        }

        try {
            byte[] decodedBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            Log.d("TTS_DEBUG", "Декодировано байт: " + decodedBytes.length);

            if (decodedBytes.length == 0) {
                Toast.makeText(requireContext(), "Audio is blank after decoding", Toast.LENGTH_LONG).show();
                return;
            }

            // 2. Создаём временный файл с расширением .m4a (самое надёжное для OpenAI TTS)
            File temp = File.createTempFile("tts_play_", ".m4a", requireContext().getCacheDir());
            FileOutputStream fos = new FileOutputStream(temp);
            fos.write(decodedBytes);
            fos.close();

            Log.d("TTS_DEBUG", "Файл создан: размер = " + temp.length() + " байт, путь = " + temp.getAbsolutePath());

            if (temp.length() < 100) {
                Toast.makeText(requireContext(), "The file is too small (" + temp.length() + " байт)", Toast.LENGTH_LONG).show();
                temp.delete();
                return;
            }

            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }

            // 3. Самый надёжный способ — MediaPlayer.create() (как часто делают в чате)
            mediaPlayer = MediaPlayer.create(requireContext(), Uri.fromFile(temp));

            if (mediaPlayer == null) {
                Toast.makeText(requireContext(), "Failed to create player", Toast.LENGTH_LONG).show();
                temp.delete();
                return;
            }

            mediaPlayer.start();

            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d("TTS_DEBUG", "Воспроизведение завершено");
                temp.delete();
                mp.release();
                mediaPlayer = null;
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e("TTS_DEBUG", "Ошибка плеера: what=" + what + ", extra=" + extra);
                Toast.makeText(requireContext(), "Player error: " + what + "/" + extra, Toast.LENGTH_LONG).show();
                temp.delete();
                mp.release();
                mediaPlayer = null;
                return true;
            });

        } catch (Exception e) {
            Log.e("TTS_DEBUG", "Ошибка воспроизведения", e);
            Toast.makeText(requireContext(), "Failed to play: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void speakText(String text, String lang) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        Toast.makeText(requireContext(), "Generating a voice...", Toast.LENGTH_SHORT).show();

        ApiService apiService = ApiClient.getApiService();

        try {
            JSONObject json = new JSONObject();
            json.put("text", text);
            json.put("language", lang);

            RequestBody body = RequestBody.create(MediaType.parse("application/json"), json.toString());

            apiService.generateTTS(body).enqueue(new Callback<ResponseBody>() {
                @Override
                public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            String jsonResponse = response.body().string();
                            Log.d("TTS_DEBUG", "Ответ сервера TTS (/tts): " + jsonResponse.substring(0, Math.min(200, jsonResponse.length())));

                            JSONObject obj = new JSONObject(jsonResponse);
                            String base64 = obj.optString("audio", "");

                            if (!base64.isEmpty()) {
                                // Обрезаем префикс, если он есть (на всякий случай)
                                if (base64.startsWith("data:audio")) {
                                    base64 = base64.split(",")[1];
                                    Log.d("TTS_DEBUG", "Обрезан data: префикс, новая длина base64: " + base64.length());
                                }

                                final String base64ToPlay = base64;
                                requireActivity().runOnUiThread(() -> {
                                    playBase64Audio(base64ToPlay);
                                });
                            } else {
                                requireActivity().runOnUiThread(() ->
                                        Toast.makeText(requireContext(), "No audio in response from /tts", Toast.LENGTH_SHORT).show());
                            }
                        } catch (Exception e) {
                            Log.e("TTS_DEBUG", "Ошибка обработки ответа /tts", e);
                            requireActivity().runOnUiThread(() ->
                                    Toast.makeText(requireContext(), "Processing error TTS: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    } else {
                        Log.e("TTS_DEBUG", "Ошибка ответа /tts: code = " + response.code());
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), "TTS error: the server responded " + response.code(), Toast.LENGTH_SHORT).show());
                    }
                }

                @Override
                public void onFailure(Call<ResponseBody> call, Throwable t) {
                    Log.e("TTS_DEBUG", "Ошибка сети при запросе /tts", t);
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "TTS network error: " + t.getMessage(), Toast.LENGTH_SHORT).show());
                }
            });

        } catch (Exception e) {
            Log.e("TTS_DEBUG", "Ошибка подготовки запроса TTS", e);
            Toast.makeText(requireContext(), "TTS preparation error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}