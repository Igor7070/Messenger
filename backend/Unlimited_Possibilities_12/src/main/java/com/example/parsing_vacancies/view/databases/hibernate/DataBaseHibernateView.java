package com.example.parsing_vacancies.view.databases.hibernate;

import com.example.parsing_vacancies.controller.job_search.Controller;
import com.example.parsing_vacancies.model.job_search.*;
import com.example.parsing_vacancies.view.View;
import com.example.parsing_vacancies.view.databases.hibernate.vac_ent.VacancyEntityHibernate;
import com.example.parsing_vacancies.view.databases.hibernate.vac_ent.VacancyForFullListTable;
import com.example.parsing_vacancies.view.databases.hibernate.vac_ent.VacancyForRabotaUaTable;
import com.example.parsing_vacancies.view.databases.hibernate.vac_ent.VacancyForWorkUaTable;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class DataBaseHibernateView implements View {
    private Controller controller;
    private static SessionFactory sessionFactoryFullList;
    private static SessionFactory sessionFactoryWorkUa;
    private static SessionFactory sessionFactoryRabotaUa;
    private static Properties properties;
    @Override
    public void update(List<Vacancy> vacancies) {
        initFullList();
        if (deleteDataBaseContents(sessionFactoryFullList, "full_list_hibernate")) {
            List<VacancyForFullListTable> vacanciesForFullListTable = convertVacanciesToFullListTable(vacancies);
            boolean writeOk = fillTheDatabaseByVacancies(sessionFactoryFullList, "full_list_hibernate", vacanciesForFullListTable);
            if (writeOk) {
                System.out.println("Data was successfully written to the table full_list_hibernate");
            }
        }
        sessionFactoryFullList.close();
    }

    @Override
    public void update(List<Vacancy> vacancies, Provider provider) {
        Strategy strategy = provider.getStrategy();
        if (strategy instanceof WorkUaStrategy) {
            initWorkUa();
            if (deleteDataBaseContents(sessionFactoryWorkUa, "work_ua_hibernate")) {
                List<VacancyForWorkUaTable> vacanciesForWorkUaTable = convertVacanciesToWorkUaTable(vacancies);
                boolean writeOk = fillTheDatabaseByVacancies(sessionFactoryWorkUa, "work_ua_hibernate", vacanciesForWorkUaTable);
                if (writeOk) {
                    System.out.println("Data was successfully written to the table work_ua_hibernate");
                }
            }
            sessionFactoryWorkUa.close();
        } else if (strategy instanceof RabotaUaStrategy) {
            initRabotaUa();
            if (deleteDataBaseContents(sessionFactoryRabotaUa, "rabota_ua_hibernate")) {
                List<VacancyForRabotaUaTable> vacanciesForRabotaUaTable = convertVacanciesToRabotaUaTable(vacancies);
                boolean writeOk = fillTheDatabaseByVacancies(sessionFactoryRabotaUa, "rabota_ua_hibernate", vacanciesForRabotaUaTable);
                if (writeOk) {
                    System.out.println("Data was successfully written to the table rabota_ua_hibernate");
                }
            }
            sessionFactoryRabotaUa.close();
        }
    }

    @Override
    public void setController(Controller controller) {
        this.controller = controller;
    }

    private static void initFullList() {
        getInitProperties();
        sessionFactoryFullList = new Configuration()
                .setProperties(properties)
                .addAnnotatedClass(VacancyForFullListTable.class)
                .buildSessionFactory();
    }

    private static void initWorkUa() {
        getInitProperties();
        sessionFactoryWorkUa = new Configuration()
                .setProperties(properties)
                .addAnnotatedClass(VacancyForWorkUaTable.class)
                .buildSessionFactory();
    }

    private static void initRabotaUa() {
        getInitProperties();
        sessionFactoryRabotaUa = new Configuration()
                .setProperties(properties)
                .addAnnotatedClass(VacancyForRabotaUaTable.class)
                .buildSessionFactory();
    }

    private static Properties getInitProperties() {
        if (properties == null) {
            properties = new Properties();
            properties.put(Environment.DRIVER, "com.mysql.cj.jdbc.Driver");
            properties.put(Environment.URL, "jdbc:mysql://localhost:3306/sites_vacancies");
            properties.put(Environment.USER, "root");
            properties.put(Environment.PASS, "rembaza1989");
            return properties;
        }
        return properties;
    }

    private static boolean fillTheDatabaseByVacancies(SessionFactory sessionFactory, String nameTable, List<? extends VacancyEntityHibernate> vacancies) {
        try (Session session = sessionFactory.openSession()) {
            Transaction transaction = session.beginTransaction();
            for (VacancyEntityHibernate vacancy : vacancies) {
                session.persist(vacancy);
            }
            transaction.commit();
            return true;
        }
        catch(Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean deleteDataBaseContents(SessionFactory sessionFactory, String nameTable) {
        int rowsCount = 0;
        try(Session session = sessionFactory.openSession()) {
            Transaction transaction = session.beginTransaction();
            rowsCount = session.createNativeQuery("TRUNCATE TABLE " + nameTable).executeUpdate();
            transaction.commit();
        }
        return rowsCount == getCountRowsTable(sessionFactory, nameTable);
    }

    private static int getCountRowsTable(SessionFactory sessionFactory, String nameTable) {
        int countRows = 0;
        try (Session session = sessionFactory.openSession()) {
            NativeQuery<Integer> query = session.createNativeQuery("SELECT COUNT(*) FROM " + nameTable, Integer.class);
            countRows = query.uniqueResult();
        }
        return countRows;
    }

    private static List<? extends VacancyEntityHibernate> getAndPrintAllVacanciesFromTable(String nameTable) {
        if (nameTable.equals("full_list_hibernate")) {
            try (Session session = sessionFactoryFullList.openSession()) {
                Query<VacancyForFullListTable> query =
                        session.createQuery("from VacancyForFullListTable", VacancyForFullListTable.class);
                List<VacancyForFullListTable> vacanciesForFullTable = query.list();
                print(vacanciesForFullTable, nameTable);
                return vacanciesForFullTable;
            }
        } else if (nameTable.equals("work_ua_hibernate")) {
            try (Session session = sessionFactoryWorkUa.openSession()) {
                Query<VacancyForWorkUaTable> query =
                        session.createQuery("from VacancyForWorkUaTable", VacancyForWorkUaTable.class);
                List<VacancyForWorkUaTable> vacanciesForWorkUaTable = query.list();
                print(vacanciesForWorkUaTable, nameTable);
                return vacanciesForWorkUaTable;
            }
        } else if (nameTable.equals("rabota_ua_hibernate")) {
            try (Session session = sessionFactoryRabotaUa.openSession()) {
                Query<VacancyForRabotaUaTable> query =
                        session.createQuery("from VacancyForRabotaUaTable", VacancyForRabotaUaTable.class);
                List<VacancyForRabotaUaTable> vacanciesForRabotaUaTable = query.list();
                print(vacanciesForRabotaUaTable, nameTable);
                return vacanciesForRabotaUaTable;
            }
        }
        return null;
    }

    private static void print(List<? extends VacancyEntityHibernate> vacancies, String nameTable) {
        System.out.println(String.format("Vacancies from table %s:", nameTable));
        for (VacancyEntityHibernate vacancy : vacancies) {
            System.out.println(String.format("%d - %s - %s - %s - %s - %s - %s", vacancy.getId(), vacancy.getTitle(),
                    vacancy.getCompanyName(), vacancy.getSalary(), vacancy.getCity(),
                    vacancy.getSiteName(), vacancy.getUrl()));
        }
    }

    private List<VacancyForFullListTable> convertVacanciesToFullListTable(List<Vacancy> vacancies) {
        List<VacancyForFullListTable> vacanciesForFullListTable = new ArrayList<>();
        for (Vacancy vacancy : vacancies) {
            VacancyForFullListTable vacancyForFullListTable = new VacancyForFullListTable();
            vacancyForFullListTable.setTitle(vacancy.getTitle());
            vacancyForFullListTable.setCompanyName(vacancy.getCompanyName());
            vacancyForFullListTable.setSalary(vacancy.getSalary());
            vacancyForFullListTable.setCity(vacancy.getCity());
            vacancyForFullListTable.setSiteName(vacancy.getSiteName());
            vacancyForFullListTable.setUrl(vacancy.getUrl());

            vacanciesForFullListTable.add(vacancyForFullListTable);
        }
        return vacanciesForFullListTable;
    }

    private List<VacancyForWorkUaTable> convertVacanciesToWorkUaTable(List<Vacancy> vacancies) {
        List<VacancyForWorkUaTable> vacanciesForWorkUaTable = new ArrayList<>();
        for (Vacancy vacancy : vacancies) {
            VacancyForWorkUaTable vacancyForWorkUaTable = new VacancyForWorkUaTable();
            vacancyForWorkUaTable.setTitle(vacancy.getTitle());
            vacancyForWorkUaTable.setCompanyName(vacancy.getCompanyName());
            vacancyForWorkUaTable.setSalary(vacancy.getSalary());
            vacancyForWorkUaTable.setCity(vacancy.getCity());
            vacancyForWorkUaTable.setSiteName(vacancy.getSiteName());
            vacancyForWorkUaTable.setUrl(vacancy.getUrl());

            vacanciesForWorkUaTable.add(vacancyForWorkUaTable);
        }
        return vacanciesForWorkUaTable;
    }

    private List<VacancyForRabotaUaTable> convertVacanciesToRabotaUaTable(List<Vacancy> vacancies) {
        List<VacancyForRabotaUaTable> vacanciesForRabotaUaTable = new ArrayList<>();
        for (Vacancy vacancy : vacancies) {
            VacancyForRabotaUaTable vacancyForRabotaUaTable = new VacancyForRabotaUaTable();
            vacancyForRabotaUaTable.setTitle(vacancy.getTitle());
            vacancyForRabotaUaTable.setCompanyName(vacancy.getCompanyName());
            vacancyForRabotaUaTable.setSalary(vacancy.getSalary());
            vacancyForRabotaUaTable.setCity(vacancy.getCity());
            vacancyForRabotaUaTable.setSiteName(vacancy.getSiteName());
            vacancyForRabotaUaTable.setUrl(vacancy.getUrl());

            vacanciesForRabotaUaTable.add(vacancyForRabotaUaTable);
        }
        return vacanciesForRabotaUaTable;
    }

    public static void main(String[] args) throws IOException {
        initFullList();
        //saveUser();

        getAndPrintAllVacanciesFromTable("full_list_hibernate");
        System.out.println(getCountRowsTable(sessionFactoryFullList, "full_list_hibernate"));
        //System.out.println(deleteDataBaseContents(sessionFactoryFullList, "full_list_hibernate"));
    }

    /*String query = "SELECT COUNT(*) FROM :tablename";
      NativeQuery<Integer> nativeQuery = session.createNativeQuery(query, Integer.class);
      nativeQuery.setParameter("tablename", nameTable);
      countRows = nativeQuery.uniqueResult();*/
}
