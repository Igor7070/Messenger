package com.example.unl_pos12.model.job_search.resume;

import com.example.unl_pos12.model.job_search.Vacancy;

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
