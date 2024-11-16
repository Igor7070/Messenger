package com.example.unl_pos12;

import com.example.unl_pos12.controller.job_search.Controller;
import com.example.unl_pos12.model.job_search.Model;
import com.example.unl_pos12.model.job_search.Provider;
import com.example.unl_pos12.model.job_search.RabotaUaStrategy;
import com.example.unl_pos12.model.job_search.WorkUaStrategy;
import com.example.unl_pos12.parameters.*;
import com.example.unl_pos12.view.View;
import com.example.unl_pos12.view.databases.DataBaseJdbsView;
import com.example.unl_pos12.view.databases.hibernate.DataBaseHibernateView;
import com.example.unl_pos12.view.html.HtmlView;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


@SpringBootApplication
@EnableScheduling
public class UnlimitedPossibilities12_Application {
    public static List<RecordingMethod> methods;

    public static void main(String[] args) {
        SpringApplication.run(UnlimitedPossibilities12_Application.class, args);

        setRecordingMethod(RecordingMethod.HTML, RecordingMethod.DATABASE_BY_JDBS);

        //getVacanciesKyivPosition("manager", Sites.WORK_UA);
        //getVacanciesKyivPosition("java", Sites.WORK_UA);
        /*getVacanciesWithParameters("тестировщик", Language.RUSSIAN, City.KIEV, TimeDate.SEVEN_DAYS,
                Sites.WORK_UA);*/

        //getVacanciesKyivPosition("java", Sites.RABOTA_UA);
        /*getVacanciesWithParameters("java", Language.RUSSIAN, City.KIEV, TimeDate.ALL_TIMES,
                Sites.RABOTA_UA);*/

        //getVacanciesKyivPosition("java", Sites.WORK_UA, Sites.RABOTA_UA);
        /*getVacanciesWithParameters("java", Language.RUSSIAN, City.KIEV, TimeDate.ALL_TIMES,
                Sites.WORK_UA, Sites.RABOTA_UA);*/
    }

    private static Controller startConfiguration(Provider... providers) {
        List<View> views = new ArrayList<>();
        for (RecordingMethod method : methods) {
            switch (method) {
                case HTML -> views.add(new HtmlView());
                case DATABASE_BY_JDBS -> views.add(new DataBaseJdbsView());
                case DATABASE_BY_HIBERNATE -> views.add(new DataBaseHibernateView());
            }
        }
        Model model = new Model(views, providers);
        Controller controller = new Controller(model);
        for (View view : views) {
            view.setController(controller);
        }
        return controller;
    }

    public static void getVacanciesKyivPosition(String position, Integer maxVacanciesWorkUa,
                                                Integer maxVacanciesRabotaUa, Sites... sites) {
        List<Provider> providersList = new ArrayList<>();
        for (Sites site : sites) {
            if (site.equals(Sites.WORK_UA)) {
                Provider providerWorkUa = new Provider(new WorkUaStrategy());
                providersList.add(providerWorkUa);
            }
            if (site.equals(Sites.RABOTA_UA)) {
                Provider providerRabotaUa = new Provider(new RabotaUaStrategy());
                providersList.add(providerRabotaUa);
            }
        }
        Provider[] providers = providersList.toArray(new Provider[providersList.size()]);
        Controller controller = startConfiguration(providers);
        executeTask(controller, position, maxVacanciesWorkUa, maxVacanciesRabotaUa);
    }

    public static void getVacanciesWithParameters(String position, Language language, City city,
                                                 TimeDate time, Integer maxVacanciesWorkUa,
                                                  Integer maxVacanciesRabotaUa, Sites... sites) {
        List<Provider> providersList = new ArrayList<>();
        for (Sites site : sites) {
            if (site.equals(Sites.WORK_UA)) {
                Provider providerWorkUa = new Provider(new WorkUaStrategy());
                providersList.add(providerWorkUa);
            }
            if (site.equals(Sites.RABOTA_UA)) {
                Provider providerRabotaUa = new Provider(new RabotaUaStrategy());
                providersList.add(providerRabotaUa);
            }
        }
        Provider[] providers = providersList.toArray(new Provider[providersList.size()]);
        Controller controller = startConfiguration(providers);
        executeTask(controller, language, city, position, time,
                maxVacanciesWorkUa, maxVacanciesRabotaUa);
    }

    private static void executeTask(Controller controller, String position,
                                    Integer maxVacanciesWorkUa, Integer maxVacanciesRabotaUa) {
        controller.onPositionSelect(position, maxVacanciesWorkUa, maxVacanciesRabotaUa);
    }

    private static void executeTask(Controller controller,
                                    Language language, City city, String position, TimeDate time,
                                    Integer maxVacanciesWorkUa, Integer maxVacanciesRabotaUa) {
        controller.onParamSelect(language, city, position, time,
                maxVacanciesWorkUa, maxVacanciesRabotaUa);
    }

    public static void setRecordingMethod(RecordingMethod... methods) {
        UnlimitedPossibilities12_Application.methods = Arrays.asList(methods);
    }
}
