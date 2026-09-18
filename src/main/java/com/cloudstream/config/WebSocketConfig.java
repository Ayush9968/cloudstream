package com.cloudstream.config;

import com.cloudstream.handler.VideoStreamHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Registers the CloudStream WebSocket endpoint and tunes the underlying
 * Tomcat container for large binary video payloads.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final VideoStreamHandler videoStreamHandler;

    public WebSocketConfig(VideoStreamHandler videoStreamHandler) {
        this.videoStreamHandler = videoStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
                .addHandler(videoStreamHandler, "/video-stream")
                // The phone's browser arrives from a different origin during
                // development (ngrok tunnel or LAN IP), so the origin check must
                // be relaxed. LOCK THIS DOWN BEFORE ANY REAL DEPLOYMENT.
                .setAllowedOriginPatterns("*");
    }

    /**
     * Overrides the JSR-356 container defaults.
     *
     * The default binary buffer is 8192 bytes. A 2-second VP9 chunk is far
     * larger, so without this the socket dies with status 1009 (Message Too
     * Big) on the very first real frame.
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();

        container.setMaxBinaryMessageBufferSize(2 * 1024 * 1024);   // 2 MB
        container.setMaxTextMessageBufferSize(64 * 1024);           // control msgs only

        // Mobile networks stall. Don't reap a session that is merely slow.
        container.setMaxSessionIdleTimeout(60_000L);
        container.setAsyncSendTimeout(10_000L);

        return container;
    }
}