package com.example.unl_pos12.model.job_search;

import com.example.unl_pos12.parameters.City;
import com.example.unl_pos12.parameters.Language;
import com.example.unl_pos12.parameters.TimeDate;

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
