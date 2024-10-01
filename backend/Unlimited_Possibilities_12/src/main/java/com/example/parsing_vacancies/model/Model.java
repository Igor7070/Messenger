package com.example.parsing_vacancies.model;

import com.example.parsing_vacancies.controller.WebController;
import com.example.parsing_vacancies.parameters.City;
import com.example.parsing_vacancies.parameters.Language;
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

    public void selectPosition(String position, Integer maxVacanciesWorkUa, Integer maxVacanciesRabotaUa) {
        List<Vacancy> vacancies = new ArrayList<>();
        for (Provider provider : providers) {
            List<Vacancy> vacanciesFromSite = null;
            if (provider.getStrategy() instanceof WorkUaStrategy) {
                vacanciesFromSite = provider.getJavaVacancies(position, maxVacanciesWorkUa);
            } else if (provider.getStrategy() instanceof RabotaUaStrategy) {
                vacanciesFromSite = provider.getJavaVacancies(position, maxVacanciesRabotaUa);
            }
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
            if (vacanciesFromSite != null) {
                vacancies.addAll(vacanciesFromSite);
            }
        }
        if (views != null) {
            for (View view : views) {
                view.update(vacancies);
            }
        } else {
            WebController.setFullVacancies(vacancies);
        }
    }

    public void selectParam(Language language, City city, String position, TimeDate time,
                            Integer maxVacanciesWorkUa, Integer maxVacanciesRabotaUa) {
        List<Vacancy> vacancies = new ArrayList<>();
        for (Provider provider : providers) {
            List<Vacancy> vacanciesFromSite = null;
            if (provider.getStrategy() instanceof WorkUaStrategy) {
                vacanciesFromSite = provider.getJavaVacancies(language, city, position, time,
                        maxVacanciesWorkUa);
            } else if (provider.getStrategy() instanceof RabotaUaStrategy) {
                vacanciesFromSite = provider.getJavaVacancies(language, city, position, time,
                        maxVacanciesRabotaUa);
            }
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
            if (vacanciesFromSite != null) {
                vacancies.addAll(vacanciesFromSite);
            }
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
