package com.example.unl_pos12.view;

import com.example.unl_pos12.controller.job_search.Controller;
import com.example.unl_pos12.model.job_search.Provider;
import com.example.unl_pos12.model.job_search.Vacancy;

import java.util.List;

public interface View {
    void update(List<Vacancy> vacancies);
    void update(List<Vacancy> vacancies, Provider provider);
    void setController(Controller controller);
}
