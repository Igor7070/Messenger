package com.example.unlimitedpossibilities12.job_search;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.restApi.ApiClient;
import com.example.unlimitedpossibilities12.restApi.ResumeApi;
import com.example.unlimitedpossibilities12.restApi.ResumeRequest;
import com.example.unlimitedpossibilities12.job_search.resume.Education;
import com.example.unlimitedpossibilities12.job_search.resume.Resume;
import com.example.unlimitedpossibilities12.job_search.resume.WorkExperience;

import java.io.IOException;
import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ResumeActivity extends AppCompatActivity {
    CheckBox enableAICheckbox;
    private EditText fullName, email, phone, city, objective, educationCount, workCount,
            languages, skills, achievements;
    private Button createResumeButton;
    private TextView textDataCreateResume, textContactData, textObjective, textEducation,
            textWorkExpeience, textSkillsAndExperience;
    private ProgressBar progressBar;
    private TextView waitingCreateResumeText;
    private LinearLayout educationFields, workFields;
    private Vacancy vacancy;
    private Resume resume;
    private boolean enableAI;
    private byte[] documentBytes; // Переменная для хранения байтового массива документа

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_resume);

        // Получение объекта вакансии
        if (getIntent().hasExtra("vacancy")) {
            vacancy = (Vacancy) getIntent().getSerializableExtra("vacancy");
        }

        // Инициализация полей
        enableAICheckbox = findViewById(R.id.enableAI);
        enableAICheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            enableAI = isChecked; // Сохранение состояния чекбокса
        });

        fullName = findViewById(R.id.fullName);
        email = findViewById(R.id.email);
        phone = findViewById(R.id.phone);
        city = findViewById(R.id.city);
        objective = findViewById(R.id.objective);
        educationCount = findViewById(R.id.educationCount);
        workCount = findViewById(R.id.workCount);
        educationFields = findViewById(R.id.educationFields);
        workFields = findViewById(R.id.workFields);
        languages = findViewById(R.id.languages);
        skills = findViewById(R.id.skills);
        achievements = findViewById(R.id.achievements);
        createResumeButton = findViewById(R.id.createResumeButton);

        textDataCreateResume = findViewById(R.id.textDataCreateResume);
        textContactData = findViewById(R.id.textContactData);
        textObjective = findViewById(R.id.textObjective);
        textEducation = findViewById(R.id.textEducation);
        textWorkExpeience = findViewById(R.id.textWorkExpeience);
        textSkillsAndExperience = findViewById(R.id.textSkillsAndExperience);

        progressBar = findViewById(R.id.progressBarCreateResume);
        waitingCreateResumeText = findViewById(R.id.waitingCreateResumeText);

        // Добавление TextWatcher для educationCount
        educationCount.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (isNumeric(s.toString())) {
                    generateEducationFields(null);
                } else {
                    educationCount.setError("Введите число"); // Уведомление о неверном вводе
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Добавление TextWatcher для workCount
        workCount.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (isNumeric(s.toString())) {
                    generateWorkFields(null);
                } else {
                    workCount.setError("Введите число"); // Уведомление о неверном вводе
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        createResumeButton.setOnClickListener(v -> createResume());
    }

    private void createResume() {
        hideInputViews();

        progressBar.setVisibility(View.VISIBLE);
        waitingCreateResumeText.setVisibility(View.VISIBLE);

        try {
            Resume resume = new Resume();
            resume.setFullName(fullName.getText().toString());
            resume.setEmail(email.getText().toString());
            resume.setPhone(phone.getText().toString());
            resume.setCity(city.getText().toString());
            resume.setObjective(objective.getText().toString());
            resume.setLanguages(languages.getText().toString());
            resume.setSkills(skills.getText().toString());
            resume.setAchievements(achievements.getText().toString());

            // Обработка образования
            String eduCountText = educationCount.getText().toString();
            int eduCount = !eduCountText.isEmpty() ? Integer.parseInt(eduCountText) : 0;

            for (int i = 0; i < eduCount; i++) {
                LinearLayout educationLayout = (LinearLayout) educationFields.getChildAt(i);
                EditText institution = (EditText) educationLayout.findViewById(R.id.institutionEditText);
                EditText specialization = (EditText) educationLayout.findViewById(R.id.specializationEditText);
                EditText years = (EditText) educationLayout.findViewById(R.id.yearsEditText);

                Education education = new Education();
                education.setInstitutionName(institution.getText().toString());
                education.setSpecialization(specialization.getText().toString());
                education.setYears(years.getText().toString());
                resume.getEducationList().add(education);
            }

            // Обработка опыта работы
            String workCountText = workCount.getText().toString();
            int workCountValue = !workCountText.isEmpty() ? Integer.parseInt(workCountText) : 0;

            for (int i = 0; i < workCountValue; i++) {
                LinearLayout workLayout = (LinearLayout) workFields.getChildAt(i);
                EditText companyName = (EditText) workLayout.findViewById(R.id.companyNameEditText);
                EditText position = (EditText) workLayout.findViewById(R.id.positionEditText);
                EditText period = (EditText) workLayout.findViewById(R.id.periodEditText);

                WorkExperience workExperience = new WorkExperience();
                workExperience.setCompanyName(companyName.getText().toString());
                workExperience.setPosition(position.getText().toString());
                workExperience.setPeriod(period.getText().toString());
                resume.getWorkExperienceList().add(workExperience);
            }

            // Отображение данных для проверки
            this.resume = resume;
            //displayResumeData(resume);

            // Создание запроса
            ResumeRequest resumeRequest = new ResumeRequest(resume, vacancy, enableAI);

            // Выполнение сетевого запроса
            ResumeApi api = ApiClient.getClient().create(ResumeApi.class);
            Call<ResponseBody> call = api.createResume(resumeRequest);
            call.enqueue(new Callback<ResponseBody>() {
                @Override
                public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        // Получение байтового массива
                        try {
                            documentBytes = response.body().bytes(); // Сохраняем байтовый массив
                            // Получаем имя файла из заголовка
                            String fileName = getFileNameFromResponse(response);

                            progressBar.setVisibility(View.GONE);
                            waitingCreateResumeText.setVisibility(View.GONE);

                            // Переход к следующей активности
                            goToDownloadActivity(fileName);
                        } catch (IOException e) {
                            e.printStackTrace();
                            Toast.makeText(ResumeActivity.this, "Error reading file", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(ResumeActivity.this, "Failed to create resume", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onFailure(Call<ResponseBody> call, Throwable t) {
                    progressBar.setVisibility(View.GONE);
                    waitingCreateResumeText.setVisibility(View.GONE);
                    Toast.makeText(ResumeActivity.this, "Network error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            progressBar.setVisibility(View.GONE);
            waitingCreateResumeText.setVisibility(View.GONE);
            Toast.makeText(this, "Error in method createResume: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e("ResumeActivity", "Error in createResume", e);
        }
    }

    private String getFileNameFromResponse(Response<ResponseBody> response) {
        List<String> contentDisposition = response.headers().values("Content-Disposition");
        String fileName = "resume.docx"; // Значение по умолчанию
        if (!contentDisposition.isEmpty()) {
            String disposition = contentDisposition.get(0);
            String[] parts = disposition.split(";");
            for (String part : parts) {
                if (part.trim().startsWith("filename=")) {
                    fileName = part.split("=")[1].trim().replace("\"", ""); // Убираем кавычки
                    break;
                }
            }
        }
        return fileName;
    }

    private void goToDownloadActivity(String fileName) {
        Intent intent = new Intent(this, DownloadResumeActivity.class);
        intent.putExtra("fileName", fileName);
        intent.putExtra("documentBytes", documentBytes); // Передача байтового массива
        intent.putExtra("vacancy", vacancy);
        startActivity(intent);
    }

    private void displayResumeData(Resume resume) {
        StringBuilder resumeInfo = new StringBuilder();

        resumeInfo.append("Поключение ИИ: ").append(enableAI).append("\n");

        resumeInfo.append("Полное имя: ").append(resume.getFullName()).append("\n")
                .append("Email: ").append(resume.getEmail()).append("\n")
                .append("Телефон: ").append(resume.getPhone()).append("\n")
                .append("Город: ").append(resume.getCity()).append("\n")
                .append("Цель: ").append(resume.getObjective()).append("\n")
                .append("Образование: \n");

        for (Education edu : resume.getEducationList()) {
            resumeInfo.append("Учебное заведение: ").append(edu.getInstitutionName()).append("\n")
                    .append("Специальность: ").append(edu.getSpecialization()).append("\n")
                    .append("Годы обучения: ").append(edu.getYears()).append("\n");
        }

        resumeInfo.append("Опыт работы: \n");
        for (WorkExperience work : resume.getWorkExperienceList()) {
            resumeInfo.append("Компания: ").append(work.getCompanyName()).append("\n")
                    .append("Должность: ").append(work.getPosition()).append("\n")
                    .append("Период: ").append(work.getPeriod()).append("\n");
        }

        // Добавление навыков и опыта
        resumeInfo.append("Навыки и опыт: \n")
                .append("Языки: ").append(resume.getLanguages()).append("\n")
                .append("Навыки: ").append(resume.getSkills()).append("\n")
                .append("Достижения: ").append(resume.getAchievements()).append("\n");

        // Отображение данных о вакансии, если они есть
        if (vacancy != null) {
            resumeInfo.append("ID вакансии: ").append(vacancy.getId()).append("\n") // Добавлено
                    .append("Вакансия: ").append(vacancy.getTitle()).append("\n")
                    .append("Зарплата: ").append(vacancy.getSalary()).append("\n")
                    .append("Город: ").append(vacancy.getCity()).append("\n")
                    .append("Компания: ").append(vacancy.getCompanyName()).append("\n")
                    .append("Сайт: ").append(vacancy.getSiteName()).append("\n")
                    .append("URL: ").append(vacancy.getUrl()).append("\n");
        }

        // Логирование содержимого
        Log.d("ResumeData", resumeInfo.toString());

        // Показать информацию в виде Toast или в диалоговом окне
        Toast.makeText(this, resumeInfo.toString(), Toast.LENGTH_LONG).show();
    }

    // Методы для генерации полей образования и опыта работы
    public void generateEducationFields(View view) {
        educationFields.removeAllViews();
        String countText = educationCount.getText().toString();

        // Проверка на пустую строку
        if (!countText.isEmpty()) {
            int count = Integer.parseInt(countText);
            for (int i = 0; i < count; i++) {
                addEducationField();
            }
        }
    }

    public void generateWorkFields(View view) {
        workFields.removeAllViews();
        String countText = workCount.getText().toString();

        // Проверка на пустую строку
        if (!countText.isEmpty()) {
            int count = Integer.parseInt(countText);
            for (int i = 0; i < count; i++) {
                addWorkField();
            }
        }
    }

    private void addEducationField() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.education_fields, null);
        educationFields.addView(view);
    }

    private void addWorkField() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.work_fields, null);
        workFields.addView(view);
    }

    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void hideInputViews() {
        enableAICheckbox.setVisibility(View.GONE);
        fullName.setVisibility(View.GONE);
        email.setVisibility(View.GONE);
        phone.setVisibility(View.GONE);
        city.setVisibility(View.GONE);
        objective.setVisibility(View.GONE);
        educationCount.setVisibility(View.GONE);
        workCount.setVisibility(View.GONE);
        educationFields.setVisibility(View.GONE);
        workFields.setVisibility(View.GONE);
        languages.setVisibility(View.GONE);
        skills.setVisibility(View.GONE);
        achievements.setVisibility(View.GONE);
        createResumeButton.setVisibility(View.GONE);

        enableAICheckbox.setEnabled(false);
        fullName.setEnabled(false);
        email.setEnabled(false);
        phone.setEnabled(false);
        city.setEnabled(false);
        objective.setEnabled(false);
        educationCount.setEnabled(false);
        workCount.setEnabled(false);
        educationFields.setEnabled(false);
        workFields.setEnabled(false);
        languages.setEnabled(false);
        skills.setEnabled(false);
        achievements.setEnabled(false);
        createResumeButton.setEnabled(false);

        textDataCreateResume.setVisibility(View.GONE);
        textContactData.setVisibility(View.GONE);
        textObjective.setVisibility(View.GONE);
        textEducation.setVisibility(View.GONE);
        textWorkExpeience.setVisibility(View.GONE);
        textSkillsAndExperience.setVisibility(View.GONE);

        textDataCreateResume.setEnabled(false);
        textContactData.setEnabled(false);
        textObjective.setEnabled(false);
        textEducation.setEnabled(false);
        textWorkExpeience.setEnabled(false);
        textSkillsAndExperience.setEnabled(false);
    }

    private void showInputViews() {
        enableAICheckbox.setVisibility(View.VISIBLE);
        fullName.setVisibility(View.VISIBLE);
        email.setVisibility(View.VISIBLE);
        phone.setVisibility(View.VISIBLE);
        city.setVisibility(View.VISIBLE);
        objective.setVisibility(View.VISIBLE);
        educationCount.setVisibility(View.VISIBLE);
        workCount.setVisibility(View.VISIBLE);
        educationFields.setVisibility(View.VISIBLE);
        workFields.setVisibility(View.VISIBLE);
        languages.setVisibility(View.VISIBLE);
        skills.setVisibility(View.VISIBLE);
        achievements.setVisibility(View.VISIBLE);
        createResumeButton.setVisibility(View.VISIBLE);

        enableAICheckbox.setEnabled(true);
        fullName.setEnabled(true);
        email.setEnabled(true);
        phone.setEnabled(true);
        city.setEnabled(true);
        objective.setEnabled(true);
        educationCount.setEnabled(true);
        workCount.setEnabled(true);
        educationFields.setEnabled(true);
        workFields.setEnabled(true);
        languages.setEnabled(true);
        skills.setEnabled(true);
        achievements.setEnabled(true);
        createResumeButton.setEnabled(true);

        textDataCreateResume.setVisibility(View.VISIBLE);
        textContactData.setVisibility(View.VISIBLE);
        textObjective.setVisibility(View.VISIBLE);
        textEducation.setVisibility(View.VISIBLE);
        textWorkExpeience.setVisibility(View.VISIBLE);
        textSkillsAndExperience.setVisibility(View.VISIBLE);

        textDataCreateResume.setEnabled(true);
        textContactData.setEnabled(true);
        textObjective.setEnabled(true);
        textEducation.setEnabled(true);
        textWorkExpeience.setEnabled(true);
        textSkillsAndExperience.setEnabled(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showInputViews(); // Восстанавливаем элементы интерфейса
    }
}
