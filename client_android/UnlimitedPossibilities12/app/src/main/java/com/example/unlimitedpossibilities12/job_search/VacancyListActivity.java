package com.example.unlimitedpossibilities12.job_search;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.unlimitedpossibilities12.R;

import java.util.List;

public class VacancyListActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private VacancyAdapter vacancyAdapter;
    private List<Vacancy> vacancyList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vacancy_list);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Получение списка вакансий из Intent
        vacancyList = (List<Vacancy>) getIntent().getSerializableExtra("vacancyList");

        if (vacancyList != null) {
            vacancyAdapter = new VacancyAdapter(vacancyList);
            recyclerView.setAdapter(vacancyAdapter);
        } else {
            Toast.makeText(this, "Нет вакансий для отображения", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        Log.d("MainActivity", "onBackPressed called");
        super.onBackPressed(); // Убедитесь, что вызывается родительский метод
    }
}
