package com.example.unlimitedpossibilities12.communication_with_ai;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.unlimitedpossibilities12.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.MediaType;

public class ChatWithAIActivity extends AppCompatActivity {
    private LinearLayout messageContainer;
    private EditText messageInput;
    private Button sendMessageButton;
    private ScrollView chatScrollView;
    private long lastMessageTime;
    private int messageCount;
    private static final String PREFS_NAME = "chat_prefs";
    private static final String KEY_LAST_MESSAGE_TIME = "last_message_time";
    private static final String KEY_MESSAGE_COUNT = "message_count";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_with_ai); // Укажите правильный путь к вашему XML

        messageContainer = findViewById(R.id.messageContainer);
        messageInput = findViewById(R.id.messageInput);
        sendMessageButton = findViewById(R.id.sendMessageButton);
        chatScrollView = findViewById(R.id.chatScrollView);

        // Инициализация SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        lastMessageTime = prefs.getLong(KEY_LAST_MESSAGE_TIME, System.currentTimeMillis());
        messageCount = prefs.getInt(KEY_MESSAGE_COUNT, 0);

        sendMessageButton.setOnClickListener(v -> sendMessage());
    }

    private void sendMessage() {
        String messageText = messageInput.getText().toString().trim();
        long currentTime = System.currentTimeMillis();

        // Проверяем, прошел ли час с последнего сообщения
        if (currentTime - lastMessageTime > 3600000) {
            // Сброс счетчика, если прошел час
            messageCount = 0;
            lastMessageTime = currentTime;
        }

        if (messageCount < 5) {
            if (!messageText.isEmpty()) {
                addMessageToChat("Вы: " + messageText); // Отображаем сообщение от пользователя
                messageInput.setText(""); // Очищаем поле ввода
                sendMessageToServer(messageText); // Отправляем сообщение на сервер
                messageCount++; // Увеличиваем счетчик отправленных сообщений

                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                editor.putLong(KEY_LAST_MESSAGE_TIME, lastMessageTime);
                editor.putInt(KEY_MESSAGE_COUNT, messageCount);
                editor.apply(); // Применяем изменения
            }
        } else {
            Toast.makeText(this, "Вы достигли лимита в 5 сообщений в час", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendMessageToServer(String messageText) {
        OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();

        // Создаем JSON-объект с сообщением
        JSONObject json = new JSONObject();
        try {
            json.put("prompt", messageText); // Используем ключ "prompt"
        } catch (JSONException e) {
            e.printStackTrace();
        }

        Request request = new Request.Builder()
                .url("https://unlimitedpossibilities12.org/api/communicating_with_a_advanced_ai_model") // Укажите URL вашего API
                .post(RequestBody.create(MediaType.parse("application/json"), json.toString()))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                // Обработка ошибки, например, показать уведомление пользователю
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String aiResponse = response.body().string(); // Получаем ответ от ИИ
                    runOnUiThread(() -> {
                        addMessageToChat("ИИ: " + aiResponse); // Добавляем ответ в чат
                        chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN)); // Прокручиваем вниз
                    });
                } else {
                    // Обработка ошибки сервера
                    runOnUiThread(() -> {
                        addMessageToChat("Ошибка: " + response.message());
                        chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN)); // Прокручиваем вниз
                    });
                }
            }
        });
    }

    private void addMessageToChat(String message) {
        TextView messageView = new TextView(this);
        messageView.setText(message);
        messageView.setTextSize(16);
        messageView.setPadding(16, 16, 16, 16); // Добавляем отступы внутри сообщения
        messageView.setBackgroundResource(R.drawable.message_background); // Устанавливаем фон с обводкой
        messageView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        messageContainer.addView(messageView);
    }

    private String getAIResponse(String userMessage) {
        // Логика для получения ответа от ИИ
        // В этом примере просто возвращаем статический ответ
        return "Это ответ на ваше сообщение: " + userMessage;
    }
}
