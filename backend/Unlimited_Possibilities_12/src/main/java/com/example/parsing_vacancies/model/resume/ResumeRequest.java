package com.example.parsing_vacancies.model.resume;

import com.example.parsing_vacancies.model.Vacancy;

public class ResumeRequest {
    private Resume resume;
    private Vacancy vacancy;
    private boolean enableAI;

    public ResumeRequest(Resume resume, Vacancy vacancy, boolean enableAI) {
        this.resume = resume;
        this.vacancy = vacancy;
        this.enableAI = enableAI;
    }

    public Resume getResume() {
        return resume;
    }

    public Vacancy getVacancy() {
        return vacancy;
    }

    public boolean isEnableAI() {
        return enableAI;
    }
}
