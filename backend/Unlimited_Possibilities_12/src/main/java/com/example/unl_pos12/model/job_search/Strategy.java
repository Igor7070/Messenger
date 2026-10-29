package com.example.unl_pos12.model.job_search;

import com.example.unl_pos12.parameters.City;
import com.example.unl_pos12.parameters.Language;
import com.example.unl_pos12.parameters.TimeDate;

import java.util.List;

public interface Strategy {
    public List<Vacancy> getVacancies(String position,
                                      Integer maxVacancies);
    public List<Vacancy> getVacancies(Language language, City city, String position, TimeDate time,
                                      Integer maxVacancies);
}
