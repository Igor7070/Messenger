package com.example.parsing_vacancies.model;

import com.example.parsing_vacancies.parameters.City;
import com.example.parsing_vacancies.parameters.Language;
import com.example.parsing_vacancies.parameters.TimeDate;

import java.util.Collections;
import java.util.List;

public class Provider {
    private Strategy strategy;

    public Provider(Strategy strategy) {
        this.strategy = strategy;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public List<Vacancy> getJavaVacancies(String searchString) {
        List<Vacancy> vacancies = strategy.getVacancies(searchString);
        return vacancies;
    }

    public List<Vacancy> getJavaVacancies(Language language, City city, String position, TimeDate time) {
        List<Vacancy> vacancies = strategy.getVacancies(language, city, position, time);
        return vacancies;
    }
}
