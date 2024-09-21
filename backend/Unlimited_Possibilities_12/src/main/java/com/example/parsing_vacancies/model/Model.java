package com.example.parsing_vacancies.model;

import com.example.parsing_vacancies.controller.WebController;
import com.example.parsing_vacancies.parameters.City;
import com.example.parsing_vacancies.parameters.Language;
import com.example.parsing_vacancies.parameters.RecordingMethod;
import com.example.parsing_vacancies.parameters.TimeDate;
import com.example.parsing_vacancies.view.View;

import java.util.ArrayList;
import java.util.List;

public class Model {
    private List<View> views;
    private Provider[] providers;

    public Model(List<View> views, Provider... providers) {
        if (views == null || providers == null || providers.length == 0) {
            throw new IllegalArgumentException();
        }
        this.views = views;
        this.providers = providers;
    }

    public Model(Provider... providers) {
        if (providers == null || providers.length == 0) {
            throw new IllegalArgumentException();
        }
        this.providers = providers;
    }

    public void selectPosition(String position) {
        List<Vacancy> vacancies = new ArrayList<>();
        for (Provider provider : providers) {
            List<Vacancy> vacanciesFromSite = provider.getJavaVacancies(position);
            if (views != null) {
                for (View view : views) {
                    view.update(vacanciesFromSite, provider);
                }
            } else {
                if (provider.getStrategy() instanceof WorkUaStrategy) {
                    WebController.setFromWorkUaVacancies(vacanciesFromSite);
                } else if(provider.getStrategy() instanceof RabotaUaStrategy) {
                    WebController.setFromRabotaUaVacancies(vacanciesFromSite);
                }
            }
            vacancies.addAll(vacanciesFromSite);
        }
        if (views != null) {
            for (View view : views) {
                view.update(vacancies);
            }
        } else {
            WebController.setFullVacancies(vacancies);
        }
    }

    public void selectParam(Language language, City city, String position, TimeDate time) {
        List<Vacancy> vacancies = new ArrayList<>();
        for (Provider provider : providers) {
            List<Vacancy> vacanciesFromSite = provider.getJavaVacancies(language, city, position, time);
            if (views != null) {
                for (View view : views) {
                    view.update(vacanciesFromSite, provider);
                }
            } else {
                if (provider.getStrategy() instanceof WorkUaStrategy) {
                    WebController.setFromWorkUaVacancies(vacanciesFromSite);
                } else if(provider.getStrategy() instanceof RabotaUaStrategy) {
                    WebController.setFromRabotaUaVacancies(vacanciesFromSite);
                }
            }
            vacancies.addAll(vacanciesFromSite);
        }
        if (views != null) {
            for (View view : views) {
                view.update(vacancies);
            }
        } else {
            WebController.setFullVacancies(vacancies);
        }
    }
}
