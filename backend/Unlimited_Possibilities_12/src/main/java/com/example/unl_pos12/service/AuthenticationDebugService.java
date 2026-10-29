package com.example.unl_pos12.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationDebugService {
    public void logCurrentAuthentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            System.out.println("Authenticated user: " + auth.getName());
            System.out.println("Authorities: " + auth.getAuthorities());
        } else {
            System.out.println("User is not authenticated.");
        }
    }
}
