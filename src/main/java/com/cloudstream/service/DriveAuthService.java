package com.cloudstream.service;

import com.google.auth.oauth2.UserCredentials;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class DriveAuthService {

    private static final Logger log = LoggerFactory.getLogger(DriveAuthService.class);

    @Value("${drive.client.id}")
    private String clientId;

    @Value("${drive.client.secret}")
    private String clientSecret;

    @Value("${drive.refresh.token}")
    private String refreshToken;

    private UserCredentials credentials;

    @PostConstruct
    public void init() throws IOException {
        if (clientId == null || clientSecret == null || refreshToken == null) {
            throw new IllegalStateException("OAuth credentials missing in application.properties");
        }

        this.credentials = UserCredentials.newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(refreshToken)
                .build();

        this.credentials.refreshIfExpired();
        log.info("Google OAuth2 user credentials loaded and verified successfully.");
    }

    public synchronized String getAccessToken() throws IOException {
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }
}