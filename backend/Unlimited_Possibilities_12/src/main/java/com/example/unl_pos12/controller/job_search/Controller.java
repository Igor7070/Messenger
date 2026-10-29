package com.example.unl_pos12.controller.job_search;

import com.example.unl_pos12.model.job_search.Model;
import com.example.unl_pos12.parameters.City;
import com.example.unl_pos12.parameters.Language;
import com.example.unl_pos12.parameters.TimeDate;

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
