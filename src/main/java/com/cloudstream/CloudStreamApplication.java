package com.cloudstream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CloudStream entry point.
 * Note that we do NOT annotate with @EnableWebSocket here - that lives on
 * WebSocketConfig, keeping socket concerns in one place.
 */
@SpringBootApplication
public class CloudStreamApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudStreamApplication.class, args);
    }
}