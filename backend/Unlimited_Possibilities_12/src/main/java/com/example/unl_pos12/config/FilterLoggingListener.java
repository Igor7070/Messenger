package com.example.unl_pos12.config;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.stereotype.Component;

@Component
public class FilterLoggingListener implements ApplicationListener<ContextRefreshedEvent> {
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        FilterChainProxy filterChainProxy = event.getApplicationContext().getBean(FilterChainProxy.class);
        filterChainProxy.getFilterChains().forEach(chain -> {
            chain.getFilters().forEach(filter -> {
                System.out.println("Filter: " + filter.getClass().getName());
            });
        });
    }
}
