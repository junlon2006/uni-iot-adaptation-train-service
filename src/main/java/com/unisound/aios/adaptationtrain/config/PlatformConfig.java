package com.unisound.aios.adaptationtrain.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformConfig {
    @Value("${platform.uploadServerUrl}")
    public String uploadServerUrl;

    @Value("${platform.notify.serverUrl}")
    public String notifyServerUrl;
}
