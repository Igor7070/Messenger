package com.example.unlimitedpossibilities12.job_search;

import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import android.Manifest;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.restApi.ResumeSendRequest;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class DownloadResumeActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_WRITE_EXTERNAL_STORAGE = 1;
    private byte[] documentBytes;
    private String fileName;
    private static final int RC_SIGN_IN = 9001;
    private GoogleSignInClient googleSignInClient;
    private final String CLIENT_ID_ANDROID = "700417838593-rf6g0btmcmjnk07rteqg1ovr8cfsugbo.apps.googleusercontent.com";
    private final String CLIENT_ID_WEB = "700417838593-m7o2cpuriob059nre37ckgb2u8d8rtld.apps.googleusercontent.com";
    protected String accessTokenResult;
    protected String emailResult;
    protected String firstNameResult;
    protected String lastNameResult;
    private Vacancy vacancy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_resume);

        // Получаем данные из Intent
        Intent intent = getIntent();
        fileName = intent.getStringExtra("fileName");
        documentBytes = intent.getByteArrayExtra("documentBytes");

        // Получение объекта вакансии
        if (getIntent().hasExtra("vacancy")) {
            vacancy = (Vacancy) getIntent().getSerializableExtra("vacancy");
        }

        Button downloadButton = findViewById(R.id.downloadButton);
        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ContextCompat.checkSelfPermission(DownloadResumeActivity.this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(DownloadResumeActivity.this,
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQUEST_CODE_WRITE_EXTERNAL_STORAGE);
                } else {
                    downloadFile();
                }
            }
        });

        // Настройка Google Sign-In
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(CLIENT_ID_WEB) // Ваш CLIENT_ID для Android
                .requestServerAuthCode(CLIENT_ID_WEB) // Запрос на получение authorization code
                .requestEmail()
                .build();

        // Настроим текст для кнопки авторизации в Google
        Button signInButton = findViewById(R.id.google_sign_in_button);
        String googleText = "Авторизация в Google";
        SpannableString spannableString = new SpannableString(googleText);

        // Цвета логотипа Google: Синий, Красный, Желтый, Синий, Зеленый, Красный
        int[] colors = {
                Color.parseColor("#4285F4"), // G
                Color.parseColor("#DB4437"), // o
                Color.parseColor("#F4B400"), // o
                Color.parseColor("#4285F4"), // g
                Color.parseColor("#0F9D58"), // l
                Color.parseColor("#DB4437")  // e
        };

        // Применяем цвета к буквам "Google"
        int start = googleText.indexOf("G");
        int end = googleText.indexOf("e", start) + 1;
        if (start != -1 && end != -1) {
            for (int i = 0; i < "Google".length(); i++) {
                spannableString.setSpan(new ForegroundColorSpan(colors[i]), start + i, start + i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        signInButton.setText(spannableString);

        googleSignInClient = GoogleSignIn.getClient(this, gso);

        //Button signInButton = findViewById(R.id.google_sign_in_button);
        signInButton.setOnClickListener(view -> signIn());
    }

    private void downloadFile() {
        if (documentBytes != null) {
            // Создание файла в стандартной папке загрузок
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS); // Указываем путь

            Uri uri = null;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Для Android 10 и выше
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            } else {
                // Для Android 9 и ниже
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File file = new File(downloadsDir, fileName);
                try {
                    if (!downloadsDir.exists()) {
                        downloadsDir.mkdirs(); // Создаем папку, если она не существует
                    }
                    FileOutputStream outputStream = new FileOutputStream(file);
                    outputStream.write(documentBytes);
                    outputStream.close();
                    Toast.makeText(this, "Файл сохранен: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    return; // Завершаем метод, чтобы не выполнять следующий блок
                } catch (IOException e) {
                    e.printStackTrace();
                    Toast.makeText(this, "Ошибка при сохранении файла", Toast.LENGTH_SHORT).show();
                    return; // Выходим из метода
                }
            }

            // Запись для Android 10 и выше
            if (uri != null) {
                try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                    outputStream.write(documentBytes);
                    Toast.makeText(this, "Файл сохранен: " + uri.toString(), Toast.LENGTH_LONG).show();
                } catch (IOException e) {
                    e.printStackTrace();
                    Toast.makeText(this, "Ошибка при сохранении файла", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Не удалось создать файл", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Данные файла отсутствуют", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_WRITE_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                downloadFile();
            } else {
                Toast.makeText(this, "Разрешение на запись в хранилище отклонено", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void signIn() {
        Intent signInIntent = googleSignInClient.getSignInIntent();
        startActivityForResult(signInIntent, RC_SIGN_IN);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            handleSignInResult(task);
        }
    }

    private void handleSignInResult(Task<GoogleSignInAccount> completedTask) {
        try {
            GoogleSignInAccount account = completedTask.getResult(ApiException.class);
            String idToken = account.getIdToken();
            String authCode = account.getServerAuthCode(); // Получение authorization code

            System.out.println("authCode: " + authCode);

            authenticateWithServer(idToken, authCode); // Передаем оба токена
        } catch (ApiException e) {
            Log.w("DownloadResumeActivity", "signInResult:failed code=" + e.getStatusCode());
        }
    }

    private void authenticateWithServer(String idToken, String authCode) {
        OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();

        // Создаем JSON-объект с idToken и authCode
        JSONObject json = new JSONObject();
        try {
            json.put("idToken", idToken);
            json.put("authCode", authCode); // Добавляем authCode
        } catch (JSONException e) {
            e.printStackTrace();
        }

        Request request = new Request.Builder()
                .url("https://unlimitedpossibilities12.org/api/login") // Укажите URL вашего контроллера
                .post(RequestBody.create(MediaType.parse("application/json"), json.toString())) // Отправляем JSON-объект
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    // Преобразуем ответ в JSON
                    try {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        String message = jsonResponse.getString("message");
                        String accessToken = jsonResponse.getString("accessToken");
                        String email = jsonResponse.getString("email");
                        String firstName = jsonResponse.getString("firstName");
                        String lastName = jsonResponse.getString("lastName");

                        accessTokenResult = accessToken;
                        emailResult = email;
                        firstNameResult = firstName;
                        lastNameResult = lastName;

                        System.out.println("accessToken: " + accessToken);

                        ResumeSendRequest resumeSendRequest = new ResumeSendRequest(
                                fileName, accessToken, email,
                                firstName, lastName, vacancy);

                        // Отображение временного окна с сообщением, access token и другими данными
                        /*runOnUiThread(() -> showToast(message + "\nAccess Token: " + accessTokenResult +
                                "\nEmail: " + emailResult +
                                "\nFirst Name: " + firstNameResult +
                                "\nLast Name: " + lastNameResult +
                                "\nFile Name: " + fileName +
                                "\nVacancy: " + vacancy));*/

                        // Переход на новую активность с передачей данных
                        Intent intent = new Intent(DownloadResumeActivity.this, ResumeSendActivity.class);
                        intent.putExtra("message", message);
                        intent.putExtra("resumeSendRequest", resumeSendRequest); // Обязательно реализуйте Serializable в ResumeSendRequest
                        startActivity(intent);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else {
                    Log.e("DownloadResumeActivity", "Authentication failed: " + response.message());
                }
            }
        });
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
