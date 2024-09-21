package com.example.parsing_vacancies.view.databases.hibernate.vac_ent;

public abstract class VacancyEntityHibernate {
    public abstract int getId();
    public abstract String getTitle();
    public abstract String getSalary();
    public abstract String getCity();
    public abstract String getCompanyName();
    public abstract String getSiteName();
    public abstract String getUrl();

    public abstract void setId(int id);
    public abstract void setTitle(String title);
    public abstract void setSalary(String salary);
    public abstract void setCity(String city);
    public abstract void setCompanyName(String companyName);
    public abstract void setSiteName(String siteName);
    public abstract void setUrl(String url);
}
