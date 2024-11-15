package com.example.parsing_vacancies.view;

import com.example.parsing_vacancies.controller.job_search.Controller;
import com.example.parsing_vacancies.model.job_search.Provider;
import com.example.parsing_vacancies.model.job_search.Vacancy;

import java.util.List;

public interface View {
    void update(List<Vacancy> vacancies);
    void update(List<Vacancy> vacancies, Provider provider);
    void setController(Controller controller);
}
