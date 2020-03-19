package com.unisound.aios.adaptationtrain.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RemoteKwsEngineBuildConfig {
    @Value("${remote.kws.jenkins.url}")
    public String jenkinsUrl;

    @Value("${remote.kws.jenkins.userName}")
    public String jenkinsUserName;

    @Value("${remote.kws.jenkins.password}")
    public String jenkinsPassword;

    @Value("${remote.kws.jenkins.jobName}")
    public String jenkinsJobName;

    @Value("${remote.kws.version}")
    public String version;

    @Value("${remote.kws.mode}")
    public String kwsMode;

    @Value("${remote.kws.targetPlat}")
    public String targetPlat;

    @Value("${remote.kws.lpLanguage}")
    public String lpLanguage;

    @Value("${remote.kws.dynamicGrammarComplierMode}")
    public String dynamicGrammarComplierMode;
}
