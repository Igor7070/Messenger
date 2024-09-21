package com.example.parsing_vacancies.view.databases;

import com.example.parsing_vacancies.controller.Controller;
import com.example.parsing_vacancies.model.*;
import com.example.parsing_vacancies.view.View;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DataBaseJdbsView implements View {
    private Controller controller;

    @Override
    public void update(List<Vacancy> vacancies) {
        try {
            Connection connection = DriverManager.getConnection(
                    "jdbc:mysql://localhost:3306/sites_vacancies",
                    "root", "rembaza1989");
            if (deleteDataBaseContents(connection, "full_list_jdbs")) {
                boolean writeOk = fillTheDatabaseByVacancies(connection, "full_list_jdbs", vacancies);
                if (writeOk) {
                    System.out.println("Data was successfully written to the table full_list_jdbs");
                }
            }
            connection.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void update(List<Vacancy> vacancies, Provider provider) {
        Strategy strategy = provider.getStrategy();
        try {
            Connection connection = DriverManager.getConnection(
                    "jdbc:mysql://localhost:3306/sites_vacancies",
                    "root", "rembaza1989");
            if (strategy instanceof WorkUaStrategy) {
                if (deleteDataBaseContents(connection, "work_ua_jdbs")) {
                    boolean writeOk = fillTheDatabaseByVacancies(connection, "work_ua_jdbs", vacancies);
                    if (writeOk) {
                        System.out.println("Data was successfully written to the table work_ua_jdbs");
                    }
                }
            } else if (strategy instanceof RabotaUaStrategy) {
                if (deleteDataBaseContents(connection, "rabota_ua_jdbs")) {
                    boolean writeOk = fillTheDatabaseByVacancies(connection, "rabota_ua_jdbs", vacancies);
                    if (writeOk) {
                        System.out.println("Data was successfully written to the table rabota_ua_jdbs");
                    }
                }
            }
            connection.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setController(Controller controller) {
        this.controller = controller;
    }

    private static boolean fillTheDatabaseByVacancies(Connection connection, String nameTable, List<Vacancy> vacancies) {
        int count = 0;
        int id = 0;
        try {
            String insertQuery = "INSERT INTO " + nameTable + "(id, title, companyName, salary, city, siteName, url) VALUES (?, ?, ?, ?, ?, ?, ?)";
            PreparedStatement statement = connection.prepareStatement(insertQuery);
            for (Vacancy vacancy : vacancies) {
                //String insertQuery = "INSERT INTO " + nameTable + "(id, title, companyName, salary, city, siteName, url) VALUES (?, ?, ?, ?, ?, ?, ?)";
                //PreparedStatement statement = connection.prepareStatement(insertQuery);

                id++;
                statement.setInt(1, id);
                statement.setString(2, vacancy.getTitle());
                statement.setString(3, vacancy.getCompanyName());
                statement.setString(4, vacancy.getSalary());
                statement.setString(5, vacancy.getCity());
                statement.setString(6, vacancy.getSiteName());
                statement.setString(7, vacancy.getUrl());

                statement.addBatch();
                //count += statement.executeUpdate();
            }
            int[] results = statement.executeBatch();
            count = results.length;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return count == vacancies.size();
    }

    private static boolean deleteDataBaseContents(Connection connection, String nameTable) {
        int rowsCount = 0;
        try {
            Statement statement = connection.createStatement();
            rowsCount = statement.executeUpdate("TRUNCATE TABLE " + nameTable);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return rowsCount == getCountRowsTable(connection, nameTable);
    }

    private static int getCountRowsTable(Connection connection, String nameTable) {
        int countRows = 0;
        try {
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + nameTable);
            resultSet.next();
            countRows = resultSet.getInt("count(*)");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return countRows;
    }

    private static List<Vacancy> getAndPrintAllVacanciesFromTable(Connection connection, String nameTable) throws SQLException {
        Statement statement = connection.createStatement();
        ResultSet results = statement.executeQuery("SELECT * FROM " + nameTable);

        ArrayList<Vacancy> vacancies = new ArrayList<>();
        while (results.next()) {
            Vacancy vacancy = new Vacancy();
            vacancy.setId(results.getInt(1));
            vacancy.setTitle(results.getString(2));
            vacancy.setCompanyName(results.getString(3));
            vacancy.setSalary(results.getString(4));
            vacancy.setCity(results.getString(5));
            vacancy.setSiteName(results.getString(6));
            vacancy.setUrl(results.getString(7));

            vacancies.add(vacancy);
        }
        for (Vacancy vacancy : vacancies) {
            System.out.println(String.format("Vacancies from table %s: \n" +
                            "%d - %s - %s - %s - %s - %s - %s", nameTable, vacancy.getId(), vacancy.getTitle(),
                    vacancy.getCompanyName(), vacancy.getSalary(), vacancy.getCity(),
                    vacancy.getSiteName(), vacancy.getUrl()));
        }
        return vacancies;
    }

    public static void main(String[] args) {
        try {
            Connection connection = DriverManager.getConnection(
                    "jdbc:mysql://localhost:3306/sites_vacancies",
                    "root", "rembaza1989");
            getAndPrintAllVacanciesFromTable(connection, "full_list_jdbs");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
