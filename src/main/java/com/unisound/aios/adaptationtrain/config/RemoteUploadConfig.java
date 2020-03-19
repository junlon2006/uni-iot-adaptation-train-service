package com.unisound.aios.adaptationtrain.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RemoteUploadConfig {
    @Value("${remote.upload.jenkins.url}")
    public String jenkinsUrl;

    @Value("${remote.upload.jenkins.userName}")
    public String jenkinsUserName;

    @Value("${remote.upload.jenkins.password}")
    public String jenkinsPassword;

    @Value("${remote.upload.jenkins.jobName}")
    public String jenkinsJobName;

    @Value("${remote.upload.internal.path}")
    public String path;

    @Value("${remote.upload.internal.url}")
    public String url;
}
