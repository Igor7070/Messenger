package com.example.parsing_vacancies.controller;

import com.example.parsing_vacancies.model.Model;
import com.example.parsing_vacancies.parameters.City;
import com.example.parsing_vacancies.parameters.Language;
import com.example.parsing_vacancies.parameters.RecordingMethod;
import com.example.parsing_vacancies.parameters.TimeDate;

import java.util.List;

public class Controller {
    private Model model;

    public Controller(Model model) {
        if (model == null) throw new IllegalArgumentException();
        this.model = model;
    }

    public void onPositionSelect(String position) {
        model.selectPosition(position);
    }

    public void onParamSelect(Language language, City city, String position, TimeDate time) {
        model.selectParam(language, city, position, time);
    }
}
