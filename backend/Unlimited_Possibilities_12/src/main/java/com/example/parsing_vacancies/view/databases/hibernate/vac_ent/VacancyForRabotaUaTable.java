package com.example.parsing_vacancies.view.databases.hibernate.vac_ent;

import jakarta.persistence.*;

import java.util.Objects;

@Entity
@Table(name= "rabota_ua_hibernate")
public class VacancyForRabotaUaTable extends VacancyEntityHibernate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private int id;
    @Column(name="title")
    private String title;
    @Column(name="salary")
    private String salary;
    @Column(name="city")
    private String city;
    @Column(name="companyName")
    private String companyName;
    @Column(name="siteName")
    private String siteName;
    @Column(name="url")
    private String url;

    public int getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSalary() {
        return salary;
    }

    public String getCity() {
        return city;
    }

    public String getCompanyName() {
        return companyName;
    }
    public String getSiteName() {
        return siteName;
    }

    public String getUrl() {
        return url;
    }

    public void setId(int id) {
        this.id = id;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setSalary(String salary) {
        this.salary = salary;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VacancyForRabotaUaTable vacancy = (VacancyForRabotaUaTable) o;
        return title.equals(vacancy.title) && salary.equals(vacancy.salary) && city.equals(vacancy.city) && companyName.equals(vacancy.companyName) && siteName.equals(vacancy.siteName) && url.equals(vacancy.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, salary, city, companyName, siteName, url);
    }
}
