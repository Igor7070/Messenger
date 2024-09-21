package com.example.parsing_vacancies.view;

import com.example.parsing_vacancies.controller.Controller;
import com.example.parsing_vacancies.model.Provider;
import com.example.parsing_vacancies.model.Vacancy;

import java.util.List;

public interface View {
    void update(List<Vacancy> vacancies);
    void update(List<Vacancy> vacancies, Provider provider);
    void setController(Controller controller);
}
