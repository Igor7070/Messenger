package com.example.parsing_vacancies.controller;

import com.example.parsing_vacancies.model.Model;
import com.example.parsing_vacancies.parameters.City;
import com.example.parsing_vacancies.parameters.Language;
import com.example.parsing_vacancies.parameters.TimeDate;

public class Controller {
    private Model model;

    public Controller(Model model) {
        if (model == null) throw new IllegalArgumentException();
        this.model = model;
    }

    public void onPositionSelect(String position, Integer maxVacanciesWorkUa, Integer maxVacanciesRabotaUa) {
        model.selectPosition(position, maxVacanciesWorkUa, maxVacanciesRabotaUa);
    }

    public void onParamSelect(Language language, City city, String position, TimeDate time,
                              Integer maxVacanciesWorkUa, Integer maxVacanciesRabotaUa) {
        model.selectParam(language, city, position, time, maxVacanciesWorkUa, maxVacanciesRabotaUa);
    }
}
