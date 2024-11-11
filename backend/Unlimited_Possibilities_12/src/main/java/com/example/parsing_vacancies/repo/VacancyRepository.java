package com.example.parsing_vacancies.repo;

import com.example.parsing_vacancies.model.Vacancy;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface VacancyRepository extends CrudRepository<Vacancy, Integer> {
    List<Vacancy> findBySessionId(String sessionId);
    long countBySessionId(String sessionId); // Метод для подсчета вакансий по sessionId
}
