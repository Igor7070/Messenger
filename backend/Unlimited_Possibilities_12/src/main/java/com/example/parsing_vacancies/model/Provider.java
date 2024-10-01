package com.example.parsing_vacancies.model;

import com.example.parsing_vacancies.parameters.City;
import com.example.parsing_vacancies.parameters.Language;
import com.example.parsing_vacancies.parameters.TimeDate;

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

    public List<Vacancy> getJavaVacancies(String position, Integer maxVacancies) {
        List<Vacancy> vacancies = strategy.getVacancies(position,
                maxVacancies);
        return vacancies;
    }

    public List<Vacancy> getJavaVacancies(Language language, City city, String position, TimeDate time,
                                          Integer maxVacancies) {
        List<Vacancy> vacancies = strategy.getVacancies(language, city, position, time,
                maxVacancies);
        return vacancies;
    }
}
