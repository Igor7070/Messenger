package com.example.parsing_vacancies.model.job_search.resume;

import java.io.Serializable;

public class WorkExperience implements Serializable {
    private String companyName;
    private String position;
    private String period;

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    @Override
    public String toString() {
        return "WorkExperience{" +
                "companyName='" + companyName + '\'' +
                ", position='" + position + '\'' +
                ", period='" + period + '\'' +
                '}';
    }
}
