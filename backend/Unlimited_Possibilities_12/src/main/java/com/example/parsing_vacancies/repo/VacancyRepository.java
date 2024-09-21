package com.example.parsing_vacancies.repo;

import com.example.parsing_vacancies.model.Vacancy;
import org.springframework.data.repository.CrudRepository;
public interface VacancyRepository extends CrudRepository<Vacancy, Integer> {
}
