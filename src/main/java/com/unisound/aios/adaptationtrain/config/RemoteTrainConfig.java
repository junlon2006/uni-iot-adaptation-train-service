package com.unisound.aios.adaptationtrain.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RemoteTrainConfig {

    @Value("${remote.train.jenkins.url}")
    public String jenkinsUrl;

    @Value("${remote.train.jenkins.userName}")
    public String jenkinsUserName;

    @Value("${remote.train.jenkins.password}")
    public String jenkinsPassword;

    @Value("${remote.train.jenkins.jobName}")
    public String jenkinsJobName;

    @Value("${remote.train.KWSVersion}")
    public String kwsVersion;

    @Value("${remote.train.KWSType}")
    public String kwsType;

    @Value("${remote.train.language}")
    public String language;

    @Value("${remote.train.modelIds}")
    public String modelIds;

    @Value("${remote.train.dspMic}")
    public String dspMic;

    @Value("${remote.train.dspType}")
    public String dspType;

    @Value("${remote.train.debugMode}")
    public String debugMode;

    @Value("${remote.train.wakeupWavCntMinSize}")
    public Integer wakeupWavCntMinSize;

    @Value("${remote.train.commandWavCntMinSize}")
    public Integer commandWavCntMinSize;
}
