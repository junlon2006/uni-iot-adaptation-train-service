package com.unisound.aios.adaptationtrain.utils;

import com.offbytwo.jenkins.JenkinsServer;
import com.offbytwo.jenkins.model.Artifact;
import com.offbytwo.jenkins.model.Build;
import com.offbytwo.jenkins.model.BuildResult;
import com.offbytwo.jenkins.model.JobWithDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class JenkinsUtilsImpl implements JenkinsUtils {
    @Autowired
    private ExceptionFormatUtils exceptionFormatUtils;

    private int lastFailedBuildId;

    private String lastFailedBuildStatus;

    @Override
    public String jenkinsGetLastFailedMessage() {
        return " buildId=" + this.lastFailedBuildId + "; buildStatus=" + this.lastFailedBuildStatus;
    }

    @Override
    public int jenkinsGetNextBuildNumber(String jenkinsUrl, String jenkinsUserName, String jenkinsPassword, String jenkinsJobName) {
        JenkinsServer jenkinsServer = null;
        int nextNumber = -1;
        try {
            jenkinsServer = new JenkinsServer(new URI(jenkinsUrl), jenkinsUserName, jenkinsPassword);
            JobWithDetails job = jenkinsServer.getJob(jenkinsJobName);
            nextNumber = job.getNextBuildNumber();
            log.info("Jenkins get next build number, url={}, userName={}, jobName={}, nextNumber={}",
                    jenkinsUrl, jenkinsUserName, jenkinsJobName, nextNumber);
        } catch (Exception ex) {
            log.error("jenkins failed ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
        } finally {
            if (Objects.nonNull(jenkinsServer)) {
                jenkinsServer.close();
            }
        }

        return nextNumber;
    }

    private void sleep(int msec) {
        try {
            Thread.sleep(msec);
        } catch (Exception ex) {
        }
    }

    private String buildCodetoString(BuildResult buildResult) {
        if (buildResult == BuildResult.FAILURE) {
            return "FAILURE";
        }

        if (buildResult == BuildResult.UNSTABLE) {
            return "UNSTABLE";
        }

        if (buildResult == BuildResult.REBUILDING) {
            return "REBUILDING";
        }

        if (buildResult == BuildResult.BUILDING) {
            return "BUILDING";
        }

        if (buildResult == BuildResult.ABORTED) {
            return "ABORTED";
        }

        if (buildResult == BuildResult.SUCCESS) {
            return "SUCCESS";
        }

        if (buildResult == BuildResult.UNKNOWN) {
            return "UNKNOWN";
        }

        if (buildResult == BuildResult.NOT_BUILT) {
            return "NOT_BUILT";
        }

        if (buildResult == BuildResult.CANCELLED) {
            return "CANCELLED";
        }

        return "N/A";
    }

    @Override
    public int jenkinsSyncBuildResultSuccess(String jenkinsUrl, String jenkinsUserName, String jenkinsPassword,
                                             String jenkinsJobName, int buildId, String syncKey, String syncValue) {
        //TODO 最大尝试次数
        int ret = 0;
        JenkinsServer jenkinsServer = null;
        long start = System.currentTimeMillis();
        while (true) {
            try {
                jenkinsServer = new JenkinsServer(new URI(jenkinsUrl), jenkinsUserName, jenkinsPassword);
                JobWithDetails job = jenkinsServer.getJob(jenkinsJobName);
                Build build = job.getBuildByNumber(buildId);
                if (Objects.isNull(build)) {
                    log.info("cannot find build task by number={}, retry", buildId);
                    jenkinsServer.close();
                    sleep(10 * 1000);
                    continue;
                }

                String v = build.details().getParameters().get(syncKey);
                if (!v.equals(syncValue)) {
                    buildId++;
                    jenkinsServer.close();
                    log.info("sync data not match, buildId={} except={}, actual={}", buildId, syncValue, v);
                    sleep(10 * 1000);
                    continue;
                }

                /**
                 *     FAILURE,
                 *     UNSTABLE,
                 *     REBUILDING,
                 *     BUILDING,
                 *     ABORTED,
                 *     SUCCESS,
                 *     UNKNOWN,
                 *     NOT_BUILT,
                 *     CANCELLED;
                 */

                BuildResult buildResult = build.details().getResult();
                if (buildResult != BuildResult.FAILURE &&
                        buildResult != BuildResult.ABORTED &&
                        buildResult != BuildResult.CANCELLED &&
                        buildResult != BuildResult.UNSTABLE &&
                        buildResult != BuildResult.SUCCESS) {
                    log.info("{} is running, buildId={}, cost time={}s, status={}[{}]", jenkinsJobName, buildId,
                            (System.currentTimeMillis() - start) / 1000, buildCodetoString(buildResult), buildResult);
                    jenkinsServer.close();
                    sleep(10 * 1000);
                    continue;
                }

                if (BuildResult.SUCCESS == buildResult || BuildResult.UNSTABLE == buildResult) {
                    log.info("build task {} finished status={}, build number={}", jenkinsJobName, buildCodetoString(buildResult), buildId);
                } else {
                    ret = -1;
                    this.lastFailedBuildId = buildId;
                    this.lastFailedBuildStatus = buildCodetoString(buildResult);
                    log.info("build task {} failed, status={}, build number={}", jenkinsJobName, buildCodetoString(buildResult), buildId);
                }

                break;
            } catch (Exception ex) {
                sleep(10 * 1000);
                log.error("Jenkins {} {} build failed, ex={}", jenkinsJobName, buildId, exceptionFormatUtils.exceptionFormatPrint(ex));
            } finally {
                if (Objects.nonNull(jenkinsServer)) {
                    jenkinsServer.close();
                }
            }
        }

        return ret == -1 ? -1 : buildId;
    }

    @Override
    public int jenkinsGetArtifactByBuildNumber(String jenkinsUrl, String jenkinsUserName, String jenkinsPassword,
                                               String jenkinsJobName, int buildId, String pathName) {
        JenkinsServer jenkinsServer = null;

        while (true) {
            try {
                jenkinsServer = new JenkinsServer(new URI(jenkinsUrl), jenkinsUserName, jenkinsPassword);
                JobWithDetails job = jenkinsServer.getJob(jenkinsJobName);
                Build build = job.getBuildByNumber(buildId);
                log.info("download artifact, url={}, userName={}, jobName={}, buildId={}", jenkinsUrl,
                        jenkinsUserName, jenkinsJobName, buildId);
                List<Artifact> artifactList = build.details().getArtifacts();
                log.info("list length={}", artifactList.size());

                for (Artifact a : artifactList) {
                    InputStream inputStream = build.details().downloadArtifact(a);
                    String fileName = pathName + File.separator + a.getFileName();

                    FileOutputStream outputStream = null;
                    ByteArrayOutputStream bos = null;
                    try {
                        outputStream = new FileOutputStream(new File(fileName));

                        byte[] buffer = new byte[1024];
                        bos = new ByteArrayOutputStream();
                        int len;
                        while ((len = inputStream.read(buffer)) != -1) {
                            bos.write(buffer, 0, len);
                            outputStream.write(buffer, 0, len);
                        }
                    } catch (Exception ex) {
                    } finally {
                        if (Objects.nonNull(outputStream)) {
                            outputStream.close();
                        }

                        if (Objects.nonNull(bos)) {
                            bos.close();
                        }

                        inputStream.close();
                    }

                    if (Objects.nonNull(bos)) {
                        log.info("total len={}", bos.toByteArray().length);
                    }
                }

                break;
            } catch (Exception ex) {
                log.error("jenkins download artifact failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
                sleep(10 * 1000);
            } finally {
                if (Objects.nonNull(jenkinsServer)) {
                    jenkinsServer.close();
                }
            }
        }

        return 0;
    }

    @Override
    public int jenkinsBuild(String jenkinsUrl, String jenkinsUserName, String jenkinsPassword, String jenkinsJobName,
                            Map<String, String> parameter) {
        JenkinsServer jenkinsServer = null;

        while (true) {
            try {
                jenkinsServer = new JenkinsServer(new URI(jenkinsUrl), jenkinsUserName, jenkinsPassword);
                JobWithDetails job = jenkinsServer.getJob(jenkinsJobName);
                job.build(parameter);
                log.info("Jenkins build triggered, url={}, userName={}, jobName={}",
                        jenkinsUrl, jenkinsUserName, jenkinsJobName);
                break;
            } catch (Exception ex) {
                log.error("jenkins build failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
                sleep(10 * 1000);
            } finally {
                if (Objects.nonNull(jenkinsServer)) {
                    jenkinsServer.close();
                }
            }
        }

        return 0;
    }
}
