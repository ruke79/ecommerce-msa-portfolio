package com.project.userservice.security.util;

import com.project.userservice.model.User;

public class VerificationResult {

    private final String status;
    private final User user;
    
    public VerificationResult(String status, User user) {
        this.status = status;
        this.user = user;
    }
    
    public String getStatus() {
        return status;
    }
    
    public User getUser() {
        return user;
    }

}
