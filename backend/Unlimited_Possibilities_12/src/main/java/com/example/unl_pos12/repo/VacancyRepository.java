package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.job_search.Vacancy;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface VacancyRepository extends CrudRepository<Vacancy, Integer> {
    List<Vacancy> findBySessionId(String sessionId);
    long countBySessionId(String sessionId); // Метод для подсчета вакансий по sessionId
}
