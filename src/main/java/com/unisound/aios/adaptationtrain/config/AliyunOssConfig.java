package com.unisound.aios.adaptationtrain.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AliyunOssConfig {
    @Value("${aliyun.oss.endPoint}")
    public String aliyunEndpoint;

    @Value("${aliyun.oss.accessKeyId}")
    public String aliyunAccessKeyId;

    @Value("${aliyun.oss.accesskeySecret}")
    public String aliyunAccesskeySecret;

    @Value("${aliyun.oss.bucketName}")
    public String aliyunBucketName;

    @Value("${aliyun.oss.bucketDomain}")
    public String aliyunBucketDomain;
}