package com.example.unlimitedpossibilities12.restApi;

import com.example.unlimitedpossibilities12.job_search.Vacancy;
import com.example.unlimitedpossibilities12.job_search.resume.Resume;

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
