package com.project.userservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.userservice.security.jwt.JwtAuthEntryPoint;

@RestController
public class CsrfController {

    private static final Logger logger = LoggerFactory.getLogger(CsrfController.class);

    @GetMapping("/api/csrf-token")    
    public CsrfToken csrfToken(HttpServletRequest request) {
        logger.error("Fetch Error csrfToken");

        return (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    }
     
}
