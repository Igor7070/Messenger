package com.example.unlimitedpossibilities12.job_search;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.unlimitedpossibilities12.communication_with_ai.ChatWithAIActivity;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.mainPage.WebViewMainPageActivity;

public class ResumeStatusSendActivity extends AppCompatActivity {
    private TextView statusMessageTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_resume_send_status); // Убедитесь, что у вас есть соответствующий layout

        statusMessageTextView = findViewById(R.id.statusMessageTextView); // Ваш TextView для отображения сообщения

        // Получаем данные из Intent
        String message = getIntent().getStringExtra("statusMessage");

        // Устанавливаем текст сообщения
        statusMessageTextView.setText(message);

        Button toMainPageActivityButton = findViewById(R.id.toMainPageActivity);
        toMainPageActivityButton.setOnClickListener(v -> {
            // Логика для общения с ИИ
            Intent intent = new Intent(ResumeStatusSendActivity.this, WebViewMainPageActivity.class);
            startActivity(intent);
        });

        // Обработчик для кнопки "Вернуться к поиску вакансий"
        Button returnToJobSearchButton = findViewById(R.id.returnToJobSearch);
        returnToJobSearchButton.setOnClickListener(v -> {
            Intent intent = new Intent(ResumeStatusSendActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK); // Добавляем флаги
            startActivity(intent);
            //finish(); // Завершаем текущую активность, чтобы не возвращаться к ней при нажатии кнопки "Назад"
        });
    }
}
