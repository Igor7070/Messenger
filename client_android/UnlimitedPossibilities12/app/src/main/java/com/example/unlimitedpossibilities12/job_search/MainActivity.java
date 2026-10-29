package com.example.unlimitedpossibilities12.job_search;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.utils.SHA1Util;

import com.example.unlimitedpossibilities12.api.AuthToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private CheckBox checkWorkUa, checkRabotaUa;
    private EditText maxVacanciesWork, maxVacanciesRabota, inputPosition;
    private Spinner citySpinner, languageSpinner, timeframeSpinner;
    private Button searchButton;
    private WebView webView;
    private ExecutorService executorService;
    private ProgressBar progressBar;
    private TextView searchingText;

    // Объявляем TextView
    private TextView textSearchTitle, textSelectSite, textMaxVacanciesLabel,
            textPositionLabel, textCityLabel, textLanguageLabel, textTimeframeLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String sha1 = SHA1Util.getSHA1(this);
        System.out.println("SHA-1: " + sha1); // Вывод SHA-1 в лог

        // Инициализация TextView
        textSearchTitle = findViewById(R.id.textSearchTitle);
        textSelectSite = findViewById(R.id.textSelectSite);
        textMaxVacanciesLabel = findViewById(R.id.textMaxVacanciesLabel);
        textPositionLabel = findViewById(R.id.textPositionLabel);
        textCityLabel = findViewById(R.id.textCityLabel);
        textLanguageLabel = findViewById(R.id.textLanguageLabel);
        textTimeframeLabel = findViewById(R.id.textTimeframeLabel);

        checkWorkUa = findViewById(R.id.checkWorkUa);
        checkRabotaUa = findViewById(R.id.checkRabotaUa);
        maxVacanciesWork = findViewById(R.id.maxVacanciesWork);
        maxVacanciesRabota = findViewById(R.id.maxVacanciesRabota);
        inputPosition = findViewById(R.id.inputPosition);
        citySpinner = findViewById(R.id.citySpinner);
        languageSpinner = findViewById(R.id.languageSpinner);
        timeframeSpinner = findViewById(R.id.timeframeSpinner);
        searchButton = findViewById(R.id.searchButton);
        webView = findViewById(R.id.webView);

        // Настройка адаптеров для спиннеров
        ArrayAdapter<CharSequence> cityAdapter = ArrayAdapter.createFromResource(this,
                R.array.cities_array, android.R.layout.simple_spinner_item);
        cityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        citySpinner.setAdapter(cityAdapter);

        ArrayAdapter<CharSequence> languageAdapter = ArrayAdapter.createFromResource(this,
                R.array.languages_array, android.R.layout.simple_spinner_item);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter);

        ArrayAdapter<CharSequence> timeframeAdapter = ArrayAdapter.createFromResource(this,
                R.array.timeframes_array, android.R.layout.simple_spinner_item);
        timeframeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        timeframeSpinner.setAdapter(timeframeAdapter);

        //WebView
        webView.setWebViewClient(new WebViewClient());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true); // Включить локальное хранилище
        // Настройки WebView
        webView = findViewById(R.id.webView);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);

        // Инициализация ExecutorService
        executorService = Executors.newSingleThreadExecutor();

        progressBar = findViewById(R.id.progressBar);
        searchingText = findViewById(R.id.searchingText);
        searchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performSearch();
            }
        });
    }

    private void performSearch() {
        // Проверка, выбраны ли галочки
        if (!checkWorkUa.isChecked() && !checkRabotaUa.isChecked()) {
            Toast.makeText(this, "Вы не выбрали ни одного сайта для поиска.", Toast.LENGTH_SHORT).show();
            return; // Прекращаем выполнение метода, если ни одна галочка не выбрана
        }

        // Проверка, введена ли должность
        String positionText = inputPosition.getText().toString().trim();
        if (positionText.isEmpty()) {
            Toast.makeText(this, "Вы не указали должность для поиска.", Toast.LENGTH_SHORT).show();
            return; // Прекращаем выполнение метода, если должность не указана
        }

        progressBar.setVisibility(View.VISIBLE);
        searchingText.setVisibility(View.VISIBLE);
        hideInputViews(); // Метод для скрытия элементов ввода

        String urlString = "https://unlimitedpossibilities12.org/api/convenient_job_search/search_result";

        executorService.execute(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                // Тот же случай, что в ассистенте: запрос в обход ApiClient,
                // токен приходится подставлять руками
                String bearer = AuthToken.bearer();
                if (bearer != null) connection.setRequestProperty("Authorization", bearer);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                // Создание данных для запроса
                StringBuilder postData = new StringBuilder();
                postData.append("work-ua=").append(checkWorkUa.isChecked() ? "true" : "false").append("&");
                postData.append("rabota-ua=").append(checkRabotaUa.isChecked() ? "true" : "false").append("&");
                postData.append("max-vacancies-work=").append(maxVacanciesWork.getText().toString()).append("&");
                postData.append("max-vacancies-rabota=").append(maxVacanciesRabota.getText().toString()).append("&");
                postData.append("inputPosition=").append(URLEncoder.encode(inputPosition.getText().toString(), "UTF-8")).append("&");

                // Получаем значения из спиннеров
                String selectedLanguage = getSelectedLanguage();
                postData.append("language=").append(URLEncoder.encode(selectedLanguage, "UTF-8")).append("&");

                String selectedCity = getSelectedCity();
                postData.append("city=").append(URLEncoder.encode(selectedCity, "UTF-8")).append("&");

                String selectedTimeframe = getSelectedTimeframe();
                postData.append("timeframe=").append(URLEncoder.encode(selectedTimeframe, "UTF-8"));

                // Отправка данных на сервер
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(postData.toString().getBytes());
                    os.flush();
                }

                // Проверка кода ответа
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String inputLine;

                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();

                    // Обработка JSON-ответа
                    List<Vacancy> vacancies = parseVacancies(response.toString());

                    // Обновление UI должно выполняться в основном потоке
                    runOnUiThread(() -> {
                        // Передача списка вакансий в новый Activity
                        Intent intent = new Intent(MainActivity.this, VacancyListActivity.class);
                        intent.putExtra("vacancyList", (Serializable) vacancies); // Передача списка вакансий
                        startActivity(intent); // Переход на экран с вакансиями

                        // Скрытие прогресса после завершения
                        progressBar.setVisibility(View.GONE);
                        searchingText.setVisibility(View.GONE);
                        //hideInputViews(); // Метод для скрытия элементов ввода
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Ошибка: " + responseCode, Toast.LENGTH_SHORT).show());
                    progressBar.setVisibility(View.GONE);
                    searchingText.setVisibility(View.GONE);
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                progressBar.setVisibility(View.GONE);
                searchingText.setVisibility(View.GONE);
            }
        });
    }

    // Метод для получения выбранного языка
    private String getSelectedLanguage() {
        String selectedLanguage = "";
        if (languageSpinner.getSelectedItemPosition() > 0) {
            switch (languageSpinner.getSelectedItem().toString()) {
                case "Русский":
                    selectedLanguage = "ru";
                    break;
                case "Украинский":
                    selectedLanguage = "uk";
                    break;
                case "Английский":
                    selectedLanguage = "en";
                    break;
            }
        }
        return selectedLanguage;
    }

    // Метод для получения выбранного города
    private String getSelectedCity() {
        String selectedCity = "";
        if (citySpinner.getSelectedItemPosition() > 0) {
            switch (citySpinner.getSelectedItem().toString()) {
                case "Киев":
                    selectedCity = "kiev";
                    break;
                case "Харьков":
                    selectedCity = "kharkiv";
                    break;
                case "Одесса":
                    selectedCity = "odesa";
                    break;
                case "Днепропетровск":
                    selectedCity = "dnipro";
                    break;
            }
        }
        return selectedCity;
    }

    // Метод для получения выбранного временного промежутка
    private String getSelectedTimeframe() {
        String selectedTimeframe = "";
        if (timeframeSpinner.getSelectedItemPosition() > 0) {
            switch (timeframeSpinner.getSelectedItem().toString()) {
                case "1 день":
                    selectedTimeframe = "1";
                    break;
                case "3 дня":
                    selectedTimeframe = "3";
                    break;
                case "7 дней":
                    selectedTimeframe = "7";
                    break;
                case "14 дней":
                    selectedTimeframe = "14";
                    break;
                case "30 дней":
                    selectedTimeframe = "30";
                    break;
            }
        }
        return selectedTimeframe;
    }

    // Метод для парсинга вакансий из JSON
    private List<Vacancy> parseVacancies(String jsonResponse) {
        List<Vacancy> vacancies = new ArrayList<>();
        try {
            JSONArray jsonArray = new JSONArray(jsonResponse);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                Vacancy vacancy = new Vacancy();
                vacancy.setId(jsonObject.getInt("id"));
                vacancy.setTitle(jsonObject.getString("title"));
                vacancy.setCity(jsonObject.getString("city"));
                vacancy.setCompanyName(jsonObject.getString("companyName"));
                vacancy.setSalary(jsonObject.getString("salary"));
                vacancy.setSiteName(jsonObject.getString("siteName"));
                vacancy.setUrl(jsonObject.getString("url"));
                vacancies.add(vacancy);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return vacancies;
    }

    // Метод для скрытия элементов ввода
    private void hideInputViews() {
        checkWorkUa.setVisibility(View.GONE);
        checkRabotaUa.setVisibility(View.GONE);
        maxVacanciesWork.setVisibility(View.GONE);
        maxVacanciesRabota.setVisibility(View.GONE);
        inputPosition.setVisibility(View.GONE);
        citySpinner.setVisibility(View.GONE);
        languageSpinner.setVisibility(View.GONE);
        timeframeSpinner.setVisibility(View.GONE);
        searchButton.setVisibility(View.GONE);

        checkWorkUa.setEnabled(false);
        checkRabotaUa.setEnabled(false);
        maxVacanciesWork.setEnabled(false);
        maxVacanciesRabota.setEnabled(false);
        inputPosition.setEnabled(false);
        citySpinner.setEnabled(false);
        languageSpinner.setEnabled(false);
        timeframeSpinner.setEnabled(false);
        searchButton.setEnabled(false);

        textSearchTitle.setVisibility(View.GONE);
        textSelectSite.setVisibility(View.GONE);
        textMaxVacanciesLabel.setVisibility(View.GONE);
        textPositionLabel.setVisibility(View.GONE);
        textCityLabel.setVisibility(View.GONE);
        textLanguageLabel.setVisibility(View.GONE);
        textTimeframeLabel.setVisibility(View.GONE);

        textSearchTitle.setEnabled(false);
        textSelectSite.setEnabled(false);
        textMaxVacanciesLabel.setEnabled(false);
        textPositionLabel.setEnabled(false);
        textCityLabel.setEnabled(false);
        textLanguageLabel.setEnabled(false);
        textTimeframeLabel.setEnabled(false);
    }

    private void showInputViews() {
        checkWorkUa.setVisibility(View.VISIBLE);
        checkRabotaUa.setVisibility(View.VISIBLE);
        maxVacanciesWork.setVisibility(View.VISIBLE);
        maxVacanciesRabota.setVisibility(View.VISIBLE);
        inputPosition.setVisibility(View.VISIBLE);
        citySpinner.setVisibility(View.VISIBLE);
        languageSpinner.setVisibility(View.VISIBLE);
        timeframeSpinner.setVisibility(View.VISIBLE);
        searchButton.setVisibility(View.VISIBLE);

        checkWorkUa.setEnabled(true);
        checkRabotaUa.setEnabled(true);
        maxVacanciesWork.setEnabled(true);
        maxVacanciesRabota.setEnabled(true);
        inputPosition.setEnabled(true);
        citySpinner.setEnabled(true);
        languageSpinner.setEnabled(true);
        timeframeSpinner.setEnabled(true);
        searchButton.setEnabled(true);

        // Восстановление видимости TextView
        textSearchTitle.setVisibility(View.VISIBLE);
        textSelectSite.setVisibility(View.VISIBLE);
        textMaxVacanciesLabel.setVisibility(View.VISIBLE);
        textPositionLabel.setVisibility(View.VISIBLE);
        textCityLabel.setVisibility(View.VISIBLE);
        textLanguageLabel.setVisibility(View.VISIBLE);
        textTimeframeLabel.setVisibility(View.VISIBLE);

        textSearchTitle.setEnabled(true);
        textSelectSite.setEnabled(true);
        textMaxVacanciesLabel.setEnabled(true);
        textPositionLabel.setEnabled(true);
        textCityLabel.setEnabled(true);
        textLanguageLabel.setEnabled(true);
        textTimeframeLabel.setEnabled(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showInputViews(); // Восстанавливаем элементы интерфейса
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Завершение ExecutorService при уничтожении активности
        executorService.shutdown();
    }

    @Override
    public void onBackPressed() {
        // Выход из приложения при нажатии кнопки "Назад"
        super.onBackPressed();
        //finishAffinity(); // Завершает все активности и закрывает приложение
    }
}