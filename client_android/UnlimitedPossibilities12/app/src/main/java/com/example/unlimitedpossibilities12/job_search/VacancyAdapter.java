package com.example.unlimitedpossibilities12.job_search;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.unlimitedpossibilities12.R;

import java.util.List;

public class VacancyAdapter extends RecyclerView.Adapter<VacancyAdapter.VacancyViewHolder> {
    private List<Vacancy> vacancies;

    public VacancyAdapter(List<Vacancy> vacancies) {
        this.vacancies = vacancies;
    }

    @NonNull
    @Override
    public VacancyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_vacancy, parent, false);
        return new VacancyViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VacancyViewHolder holder, int position) {
        Vacancy vacancy = vacancies.get(position);
        holder.id.setText(String.valueOf(vacancy.getId()));
        holder.title.setText(vacancy.getTitle());
        holder.salary.setText(vacancy.getSalary());
        holder.city.setText(vacancy.getCity());
        holder.companyName.setText(vacancy.getCompanyName());
        holder.siteName.setText(vacancy.getSiteName());

        /*holder.resumeButton.setOnClickListener(v -> {
            // Логика для создания резюме
            // Вы можете открыть новое Activity или показать диалог
            Toast.makeText(holder.itemView.getContext(), "Создать резюме для " + vacancy.getTitle(), Toast.LENGTH_SHORT).show();
        });*/
        holder.resumeButton.setOnClickListener(v -> {
            // Открытие новой активности с передачей объекта вакансии
            Intent intent = new Intent(holder.itemView.getContext(), ResumeActivity.class);
            intent.putExtra("vacancy", vacancy); // Передача объекта вакансии
            holder.itemView.getContext().startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return vacancies.size();
    }

    public static class VacancyViewHolder extends RecyclerView.ViewHolder {
        TextView id, title, salary, city, companyName, siteName;
        Button resumeButton;

        public VacancyViewHolder(@NonNull View itemView) {
            super(itemView);

            id = itemView.findViewById(R.id.idVacancy);
            title = itemView.findViewById(R.id.title);
            salary = itemView.findViewById(R.id.salary);
            city = itemView.findViewById(R.id.city);
            companyName = itemView.findViewById(R.id.companyName);
            siteName = itemView.findViewById(R.id.siteName);
            resumeButton = itemView.findViewById(R.id.resumeButton);
        }
    }
}
