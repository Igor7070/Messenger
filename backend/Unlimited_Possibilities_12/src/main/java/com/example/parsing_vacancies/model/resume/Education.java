package com.example.parsing_vacancies.model.resume;

import java.io.Serializable;

public class Education implements Serializable {
    private String institutionName;
    private String specialization;
    private String years;

    public String getInstitutionName() {
        return institutionName;
    }

    public void setInstitutionName(String institutionName) {
        this.institutionName = institutionName;
    }

    public String getSpecialization() {
        return specialization;
    }

    public void setSpecialization(String specialization) {
        this.specialization = specialization;
    }

    public String getYears() {
        return years;
    }

    public void setYears(String years) {
        this.years = years;
    }

    @Override
    public String toString() {
        return "Education{" +
                "institutionName='" + institutionName + '\'' +
                ", specialization='" + specialization + '\'' +
                ", years='" + years + '\'' +
                '}';
    }
}
