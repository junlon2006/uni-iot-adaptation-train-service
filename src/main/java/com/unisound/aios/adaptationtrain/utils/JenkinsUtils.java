package com.unisound.aios.adaptationtrain.utils;

import java.util.Map;

public interface JenkinsUtils {

    int jenkinsGetNextBuildNumber(String jenkinsUrl, String jenkinsUserName, String jenkinsPassword, String jenkinsJobName);

    int jenkinsBuild(String jenkinsUrl, String jenkinsUserName, String jenkinsPassword, String jobName, Map<String, String> parameter);

    int jenkinsSyncBuildResultSuccess(String jenkinsUrl, String jenkinsUserName, String jenkinsPassword, String jenkinsJobName, int buildId, String syncKey, String syncValue);

    String jenkinsGetLastFailedMessage();

    int jenkinsGetArtifactByBuildNumber(String jenkinsUrl, String jenkinsUserName, String jenkinsPassword, String jenkinsJobName, int buildId, String pathName);
}
