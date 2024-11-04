package com.example.parsing_vacancies.model.resume;

import com.example.parsing_vacancies.model.Vacancy;

import java.io.Serializable;

public class ResumeSendRequest implements Serializable {
    private String fileName;
    private String accessToken;
    private String email;
    private String firstName;
    private String lastName;
    private Vacancy vacancy;

    public ResumeSendRequest(String fileName, String accessToken, String email,
                             String firstName, String lastName,
                             Vacancy vacancy) {
        this.fileName = fileName;
        this.accessToken = accessToken;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.vacancy = vacancy;
    }

    public String getFileName() {
        return fileName;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public Vacancy getVacancy() {
        return vacancy;
    }
}
