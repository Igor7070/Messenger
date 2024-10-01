package com.example.parsing_vacancies.model;

import com.example.parsing_vacancies.parameters.City;
import com.example.parsing_vacancies.parameters.Language;
import com.example.parsing_vacancies.parameters.TimeDate;

import java.util.List;

public interface Strategy {
    public List<Vacancy> getVacancies(String position,
                                      Integer maxVacancies);
    public List<Vacancy> getVacancies(Language language, City city, String position, TimeDate time,
                                      Integer maxVacancies);
}
