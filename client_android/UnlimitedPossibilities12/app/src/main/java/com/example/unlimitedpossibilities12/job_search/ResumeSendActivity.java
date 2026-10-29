package com.example.unlimitedpossibilities12.job_search;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.restApi.ResumeSendRequest;
import com.google.gson.Gson;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ResumeSendActivity extends AppCompatActivity {
    private TextView messageTextView;
    private ResumeSendRequest resumeSendRequest;
    private String resultMessage;
    private String companyNameVacancy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_resume_send); // убедитесь, что у вас есть соответствующий layout

        messageTextView = findViewById(R.id.messageTextView); // ваш TextView для отображения сообщения

        // Получаем данные из Intent
        String message = getIntent().getStringExtra("message");
        resumeSendRequest = (ResumeSendRequest) getIntent().getSerializableExtra("resumeSendRequest");
        companyNameVacancy = resumeSendRequest.getVacancy().getCompanyName();

        // Создаем сообщение, объединяя все элементы
        String fullMessage = createFullMessage(message);
        messageTextView.setText(fullMessage);

        // Вы можете использовать resumeSendRequest для дальнейших действий
        Button sendResumeButton = findViewById(R.id.sendResumeButton);
        sendResumeButton.setOnClickListener(v -> {
            // Логика отправки резюме
            sendResumeToServer(resumeSendRequest);
        });
    }

    private String createFullMessage(String message) {
        String fileName = resumeSendRequest.getFileName();
        String accessToken = resumeSendRequest.getAccessToken();
        String email = resumeSendRequest.getEmail();
        String firstName = resumeSendRequest.getFirstName();
        String lastName = resumeSendRequest.getLastName();
        Vacancy vacancy = resumeSendRequest.getVacancy();

        return (message != null ? message + "\n" : "") +
                "File Name: " + fileName + "\n" +
                "Access Token: " + accessToken + "\n" +
                "Email: " + email + "\n" +
                "First Name: " + firstName + "\n" +
                "Last Name: " + lastName + "\n" +
                "Vacancy: " + (vacancy != null ? vacancy.toString() : "N/A");
    }

    private void sendResumeToServer(ResumeSendRequest resumeSendRequest) {
        OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();

        Gson gson = new Gson();
        String json = gson.toJson(resumeSendRequest); // Преобразуем объект в JSON

        Request request = new Request.Builder()
                .url("https://unlimitedpossibilities12.org/convenient_job_search/readyResume/api/upload") // Укажите URL вашего контроллера
                .post(RequestBody.create(MediaType.parse("application/json"), json)) // Отправляем JSON-объект
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> showToast("Ошибка при отправке резюме"));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String companyNameVacancy = resumeSendRequest.getVacancy().getCompanyName(); // Получаем название компании
                String statusMessage;

                if (response.isSuccessful()) {
                    resultMessage = response.body().string(); // Сохраняем результат из сервера
                    statusMessage = "Ваше резюме успешно отправлено в компанию " + companyNameVacancy + "! Удачного отклика и немерянного бабла!";
                } else {
                    resultMessage = response.body().string(); // Получаем сообщение об ошибке от сервера
                    statusMessage = resultMessage; // Используем сообщение от сервера
                }

                // Переход на новую активность с передачей сообщения
                Intent intent = new Intent(ResumeSendActivity.this, ResumeStatusSendActivity.class);
                intent.putExtra("statusMessage", statusMessage);
                startActivity(intent);
            }
        });
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
