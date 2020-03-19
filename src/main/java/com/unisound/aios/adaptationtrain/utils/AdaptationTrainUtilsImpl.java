package com.unisound.aios.adaptationtrain.utils;

import ch.ethz.ssh2.ChannelCondition;
import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.SCPClient;
import ch.ethz.ssh2.Session;
import com.alibaba.fastjson.JSON;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GetObjectRequest;
import com.unisound.aios.adaptationtrain.JNI.LocalAsrEngine;
import com.unisound.aios.adaptationtrain.config.*;
import com.unisound.aios.adaptationtrain.data.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Component
public class AdaptationTrainUtilsImpl implements AdaptationTrainUtils, Runnable {
    /**
     * 标注+训练服务总队列深度
     */
    private static final int QUEUE_MAX_SIZE = 100;
    /**
     * 同步互斥锁
     */
    private static final Object MUTEX = new Object();
    /**
     * 传给LABS训练目录结构名称
     */
    private static final String ANNOTATION = "annotation";
    private static final String SPEECH = "speech";
    private static final String DOC = "doc";
    /**
     * 命令词 | 唤醒词标识
     */
    private static final String WAKEUP_TYPE = "wakeup";
    private static final String COMMAND_TYPE = "command";
    /**
     * 服务器标注结果目录
     */
    private String uploadFilePath = null;
    /**
     * 训练请求队列
     */
    private static List<TrainRequest> trainRequestQueue = new LinkedList<>();
    @Autowired
    private AliyunOssConfig aliyunOssConfig;
    @Autowired
    private LocalAsrEngine localAsrEngine;
    @Autowired
    private RemoteTrainConfig remoteTrainConfig;
    @Autowired
    private RemoteUploadConfig remoteUploadConfig;
    @Autowired
    private JenkinsUtils jenkinsUtils;
    @Autowired
    private RemoteKwsEngineBuildConfig remoteKwsEngineBuildConfig;
    @Autowired
    private PlatformConfig platformConfig;
    @Autowired
    private HttpClientUtils httpClientUtils;
    @Autowired
    private ExceptionFormatUtils exceptionFormatUtils;
    /**
     * 后台标准训练服务work Thread
     */
    private Thread thread;

    /**
     * 构造函数拉起work Thread，用于标注、模型训练任务调度
     * 单线程串行处理，引擎本身是单例，单线程模式处理简洁高效
     */
    AdaptationTrainUtilsImpl() {
        this.thread = new Thread(this);
        this.thread.setName("train-task");
        this.thread.start();
    }

    private TrainRequest getOneTrainRequest() {
        synchronized (MUTEX) {
            if (trainRequestQueue.isEmpty()) {
                return null;
            }

            TrainRequest trainRequest = trainRequestQueue.get(0);
            trainRequestQueue.remove(0);
            log.info("current train queue size remain={}", trainRequestQueue.size());
            return trainRequest;
        }
    }

    private String getWorkspace() {
        return System.getProperty("user.dir");
    }

    private int exchangeUploadFile2Internal(TrainRequest trainRequest) {
        log.info("start exchange mark result to internal network");
        Map<String, String> parameter = new HashMap<>();
        parameter.put("Press the Build button to begin", trainRequest.getModelId().replace("-", ""));

        int nextBuildNumber = jenkinsUtils.jenkinsGetNextBuildNumber(remoteUploadConfig.jenkinsUrl,
                remoteUploadConfig.jenkinsUserName, remoteUploadConfig.jenkinsPassword, remoteUploadConfig.jenkinsJobName);

        jenkinsUtils.jenkinsBuild(remoteUploadConfig.jenkinsUrl, remoteUploadConfig.jenkinsUserName,
                remoteUploadConfig.jenkinsPassword, remoteUploadConfig.jenkinsJobName, parameter);

        int buildId = jenkinsUtils.jenkinsSyncBuildResultSuccess(remoteUploadConfig.jenkinsUrl, remoteUploadConfig.jenkinsUserName,
                remoteUploadConfig.jenkinsPassword, remoteUploadConfig.jenkinsJobName, nextBuildNumber,
                "Press the Build button to begin", trainRequest.getModelId().replace("-", ""));
        if (buildId == -1) {
            log.error("exchange upload file jenkins build failed, notify platform");
            notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.LABS_DATA_IMPORT_FAILED.getCode(),
                    AdaptationTrainErrorCode.LABS_DATA_IMPORT_FAILED.getMessage() + jenkinsUtils.jenkinsGetLastFailedMessage());
            return -1;
        }

        log.info("exchange mark result to internal network finished");
        return 0;
    }

    private void removeMarkResultFromServer(String dirName) {
        Connection connection = null;
        log.info("start remove mark result from server");
        try {
            connection = new Connection(remoteUploadConfig.url);
            connection.connect();
            boolean isAuthenticated = connection.authenticateWithPassword(remoteUploadConfig.jenkinsUserName, remoteUploadConfig.jenkinsPassword);
            if (isAuthenticated) {
                log.info("auth success");
            } else {
                log.info("auth failed");
            }

            Session session = connection.openSession();
            String cmd = "rm -rf " + dirName;
            log.info("cmd={}", cmd);
            session.execCommand(cmd);
            session.waitForCondition(ChannelCondition.EOF, 0);
            log.info("remove mark result from server done");
        } catch (Exception ex) {
            log.error("remove mark result from server failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
        } finally {
            if (Objects.nonNull(connection)) {
                connection.close();
            }
        }
    }

    private int uploadMarkResult(String uploadDirName, String modelId) {
        int errno = -1;
        log.info("start scp mark result to {}", remoteUploadConfig.url);
        String path = String.format("/home/%s/%s", remoteUploadConfig.jenkinsUserName, remoteUploadConfig.path);

        uploadFilePath = path + File.separator + uploadDirName;

        String format = "%s" + File.separator + "%s" + File.separator + "%s";
        String dstAnnotationDir = String.format(format, path, uploadDirName, ANNOTATION);
        String dstSpeechDir = String.format(format, path, uploadDirName, SPEECH);
        String dstDocDir = String.format(format, path, uploadDirName, DOC);

        String srcAnnotationDir = String.format(format, getWorkspace(), uploadDirName, ANNOTATION);
        String srcSpeechDir = String.format(format, getWorkspace(), uploadDirName, SPEECH);
        String srcDocDir = String.format(format, getWorkspace(), uploadDirName, DOC);

        Connection connection = null;
        try {
            connection = new Connection(remoteUploadConfig.url);
            connection.connect();
            boolean isAuthenticated = connection.authenticateWithPassword(remoteUploadConfig.jenkinsUserName, remoteUploadConfig.jenkinsPassword);
            if (isAuthenticated) {
                log.info("auth success");
            } else {
                log.info("auth failed");
            }

            String mkdirFormat = "mkdir -p %s";

            Session session = connection.openSession();
            session.execCommand(String.format(mkdirFormat, dstAnnotationDir));
            session.waitForCondition(ChannelCondition.EOF, 0);

            session = connection.openSession();
            session.execCommand(String.format(mkdirFormat, dstSpeechDir));
            session.waitForCondition(ChannelCondition.EOF, 0);

            session = connection.openSession();
            session.execCommand(String.format(mkdirFormat, dstDocDir));
            session.waitForCondition(ChannelCondition.EOF, 0);

            SCPClient scpClient = new SCPClient(connection);

            File[] annotation = new File(srcAnnotationDir).listFiles();
            if (Objects.nonNull(annotation)) {
                for (File a : annotation) {
                    log.info("scp annotation={}", a.getName());
                    scpClient.put(a.getAbsolutePath(), dstAnnotationDir);
                }
            }

            File[] speech = new File(srcSpeechDir).listFiles();
            int sent = 0;
            if (Objects.nonNull(speech)) {
                long startTime = System.currentTimeMillis();
                long now;
                long remainCnt;
                for (File s : speech) {
                    now = System.currentTimeMillis();
                    remainCnt = speech.length - sent;
                    log.info("scp speech={}, sent={}个, remain={}个, totalCost={}秒, remain={}秒", s.getName(), sent, remainCnt,
                            (now - startTime) / 1000, (now - startTime) * remainCnt / (sent + 1) / 1000);
                    scpClient.put(s.getAbsolutePath(), dstSpeechDir);
                    sent++;
                }
            }

            File[] doc = new File(srcDocDir).listFiles();
            if (Objects.nonNull(doc)) {
                for (File d : doc) {
                    log.info("scp doc={}", d.getName());
                    scpClient.put(d.getAbsolutePath(), dstDocDir);
                }
            }

            log.info("scp mark result to {} finish", remoteUploadConfig.url);
            errno = 0;
        } catch (Exception ex) {
            log.error("scp to {} failed, ex={}", remoteUploadConfig.url, exceptionFormatUtils.exceptionFormatPrint(ex));
        } finally {
            if (Objects.nonNull(connection)) {
                connection.close();
            }
        }

        if (errno != 0) {
            notifyPlatformResultFailed(modelId, AdaptationTrainErrorCode.SCP_MARK_RESULT_FAILED.getCode(),
                    AdaptationTrainErrorCode.SCP_MARK_RESULT_FAILED.getMessage());
        }

        return errno;
    }

    private int buildKwsEngineProcess(String amVersion, String modelId, String lpAcousticModelId) {
        log.info("start KWS engine build");

        Map<String, String> parameters = new HashMap<>();
        parameters.put("VERSION", remoteKwsEngineBuildConfig.version);
        parameters.put("CUSTOMIZED_AM_VERSION", amVersion);
        parameters.put("KWS_MODE", remoteKwsEngineBuildConfig.kwsMode);
        parameters.put("TARGET_PLAT", remoteKwsEngineBuildConfig.targetPlat);
        parameters.put("LP_LANGUAGE", remoteKwsEngineBuildConfig.lpLanguage);
        parameters.put("LP_ACOUSTIC_MODEL_ID", lpAcousticModelId);
        parameters.put("DYNAMIC_GRAMMAR_COMPILER_MODE", remoteKwsEngineBuildConfig.dynamicGrammarComplierMode);

        int nextBuildNumber = jenkinsUtils.jenkinsGetNextBuildNumber(remoteKwsEngineBuildConfig.jenkinsUrl,
                remoteKwsEngineBuildConfig.jenkinsUserName, remoteKwsEngineBuildConfig.jenkinsPassword,
                remoteKwsEngineBuildConfig.jenkinsJobName);

        jenkinsUtils.jenkinsBuild(remoteKwsEngineBuildConfig.jenkinsUrl, remoteKwsEngineBuildConfig.jenkinsUserName,
                remoteKwsEngineBuildConfig.jenkinsPassword, remoteKwsEngineBuildConfig.jenkinsJobName, parameters);

        int buildId = jenkinsUtils.jenkinsSyncBuildResultSuccess(remoteKwsEngineBuildConfig.jenkinsUrl,
                remoteKwsEngineBuildConfig.jenkinsUserName, remoteKwsEngineBuildConfig.jenkinsPassword,
                remoteKwsEngineBuildConfig.jenkinsJobName, nextBuildNumber, "CUSTOMIZED_AM_VERSION", amVersion);

        if (buildId == -1) {
            log.error("kws jenkins build failed, notify platform");
            notifyPlatformResultFailed(modelId, AdaptationTrainErrorCode.LABS_KWS_ENGINE_BUILD_FAILED.getCode(),
                    AdaptationTrainErrorCode.LABS_KWS_ENGINE_BUILD_FAILED.getMessage() + jenkinsUtils.jenkinsGetLastFailedMessage());
            return -1;
        }

        log.info("KWS engine build success, buildId={}", buildId);
        return buildId;
    }

    private String wordsSetEncodeString(Set<String> wordsSet) {
        StringBuilder stringBuilder = new StringBuilder();

        wordsSet.forEach(w -> stringBuilder.append(w + "\r\n"));

        return stringBuilder.toString();
    }

    private String getCommandWords(TrainRequest trainRequest) {
        Set<String> commandWords = new HashSet<>();
        Set<String> wakeupWords = new HashSet<>();

        getWordsSetByType(trainRequest, wakeupWords, WAKEUP_TYPE);
        getWordsSetByType(trainRequest, commandWords, COMMAND_TYPE);

        removeWakeupWordFromCmdWord(wakeupWords, commandWords);

        return wordsSetEncodeString(commandWords);
    }

    private void getWordsSetByType(TrainRequest trainRequest, Set<String> words, String type) {
        for (String taskId : trainRequest.getTaskCmdWordMapping().keySet()) {
            for (TaskCmdTypeMapping taskCmdTypeMapping : trainRequest.getTaskCmdWordMapping().get(taskId)) {
                if (type.equals(taskCmdTypeMapping.getType())) {
                    words.add(taskCmdTypeMapping.getWord());
                }
            }
        }
    }

    private String getWakeupWords(TrainRequest trainRequest) {
        Set<String> wakeupWords = new HashSet<>();

        getWordsSetByType(trainRequest, wakeupWords, WAKEUP_TYPE);

        return wordsSetEncodeString(wakeupWords);
    }

    private int syncRemoteTrainProcess(TrainRequest trainRequest, String uploadDirName) {
        log.info("start labs am train process");
        String projectName = String.format("automatic%s", trainRequest.getModelId().replace("-", ""));

        String wakeupWords = getWakeupWords(trainRequest);
        String commandWords = getCommandWords(trainRequest);

        Map<String, String> parameters = new HashMap<>();
        parameters.put("project_name", projectName);

        /**
         * 服务器为Linux，不能用File.separator
         */
        parameters.put("train_dataset", String.format("/lustre/data-exchange/%s/import/%s", remoteTrainConfig.jenkinsUserName, uploadDirName));
        parameters.put("kws_version", remoteTrainConfig.kwsVersion);
        parameters.put("kws_type", remoteTrainConfig.kwsType);
        parameters.put("language", remoteTrainConfig.language);

        if (StringUtils.isNotBlank(wakeupWords)) {
            parameters.put("wakeup_words", getWakeupWords(trainRequest));
        }

        if (StringUtils.isNotBlank(commandWords)) {
            parameters.put("command_words", getCommandWords(trainRequest));
        }

        parameters.put("model_ids", remoteTrainConfig.modelIds);
        parameters.put("dsp_mic", remoteTrainConfig.dspMic);
        parameters.put("dsp_type", remoteTrainConfig.dspType);
        parameters.put("debug_mode", remoteTrainConfig.debugMode);

        int nextBuildNumber = jenkinsUtils.jenkinsGetNextBuildNumber(remoteTrainConfig.jenkinsUrl,
                remoteTrainConfig.jenkinsUserName, remoteTrainConfig.jenkinsPassword, remoteTrainConfig.jenkinsJobName);

        jenkinsUtils.jenkinsBuild(remoteTrainConfig.jenkinsUrl, remoteTrainConfig.jenkinsUserName,
                remoteTrainConfig.jenkinsPassword, remoteTrainConfig.jenkinsJobName, parameters);

        int buildId = jenkinsUtils.jenkinsSyncBuildResultSuccess(remoteTrainConfig.jenkinsUrl, remoteTrainConfig.jenkinsUserName,
                remoteTrainConfig.jenkinsPassword, remoteTrainConfig.jenkinsJobName, nextBuildNumber, "project_name", projectName);

        if (buildId == -1) {
            log.error("am train jenkins build failed, notify platform");
            notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.LABS_AM_TRAIN_FAILED.getCode(),
                    AdaptationTrainErrorCode.LABS_AM_TRAIN_FAILED.getMessage() + jenkinsUtils.jenkinsGetLastFailedMessage());
            return -1;
        }

        log.info("labs am train process finish");
        return buildId;
    }

    private void sleep(int msec) {
        try {
            Thread.sleep(msec);
        } catch (Exception ex) {
        }
    }

    private void notifyPlatformResultFailed(String modelId, Integer errorCode, String errorMsg) {
        NotifyKwsRequest notifyKwsRequest = NotifyKwsRequest.builder().modelId(modelId).status(2).errorMsg(errorMsg).errorCode(errorCode).build();
        while (true) {
            log.info("start notify platform train failed result {}", notifyKwsRequest);
            String result = httpClientUtils.httpPostWithJsonString(platformConfig.notifyServerUrl + "/v1/model/train/notify", JSON.toJSONString(notifyKwsRequest));
            if (Objects.nonNull(result)) {
                log.info("notify platform train failed result finish, result={}", result);
                break;
            }

            log.warn("retry notify platform");
            sleep(10 * 1000);
        }
    }

    private int notifyPlatformResultOk(TrainRequest trainRequest, String downloadUrl) {
        String result;
        NotifyKwsRequest notifyKwsRequest = NotifyKwsRequest.builder()
                .modelId(trainRequest.getModelId())
                .url(downloadUrl)
                .status(1)
                .errorCode(AdaptationTrainErrorCode.SUCCESS.getCode())
                .errorMsg(AdaptationTrainErrorCode.SUCCESS.getMessage())
                .build();
        String jsonString = JSON.toJSONString(notifyKwsRequest);

        while (true) {
            log.info("start notify platform train result, param={}", JSON.toJSONString(notifyKwsRequest));
            result = httpClientUtils.httpPostWithJsonString(platformConfig.notifyServerUrl + "/v1/model/train/notify", jsonString);
            if (Objects.nonNull(result)) {
                log.info("notify platform train result finish, result={}", result);
                break;
            }

            log.warn("retry notify platform");
            sleep(10 * 1000);
        }

        return 0;
    }

    private String uploadKwsEngine(TrainRequest trainRequest) {
        log.info("start upload KWS engine to nas");

        File[] kwsFile = new File(getWorkspace() + File.separator + "kws").listFiles();
        if (Objects.isNull(kwsFile)) {
            return null;
        }

        log.info("kws name={}", kwsFile[0].getName());
        log.info("kws path={}", kwsFile[0].getAbsolutePath());

        Map<String, File> fileMap = new HashMap<>(1);
        Map<String, String> textMap = new HashMap<>(4);

        fileMap.put("file", kwsFile[0]);
        textMap.put("serviceId", "unios-adaptation-train-service");
        textMap.put("customDirectory", trainRequest.getModelId());
        textMap.put("customFileName", kwsFile[0].getName());

        int tryCount = 10;
        UploadKwsPlatformResult uploadKwsPlatformResult = null;
        do {
            String responseBody = httpClientUtils.httpUploadMultiPart(fileMap, textMap, platformConfig.uploadServerUrl + "/rpc/v1/upload");
            if (Objects.nonNull(responseBody)) {
                uploadKwsPlatformResult = JSON.parseObject(responseBody, UploadKwsPlatformResult.class);
                if (Objects.nonNull(uploadKwsPlatformResult) && uploadKwsPlatformResult.getCode() == 0) {
                    log.info("upload KWS engine to nas finish, result={}", uploadKwsPlatformResult);
                    break;
                }
            }

            log.warn("upload kws engine to nas failed, retry");
            sleep(10 * 1000);
        } while (--tryCount > 0);

        return Objects.isNull(uploadKwsPlatformResult) ? null : uploadKwsPlatformResult.getData();
    }

    private void downloadKwsEngine(int buildId) {
        log.info("start download KWS engine");
        jenkinsUtils.jenkinsGetArtifactByBuildNumber(remoteKwsEngineBuildConfig.jenkinsUrl,
                remoteKwsEngineBuildConfig.jenkinsUserName, remoteKwsEngineBuildConfig.jenkinsPassword,
                remoteKwsEngineBuildConfig.jenkinsJobName, buildId, getWorkspace() + File.separator + "kws");
        log.info("download KWS engine finish");
    }

    private void downloadTrainReportHtml(int buildId) {
        log.info("start download am train report.html");
        jenkinsUtils.jenkinsGetArtifactByBuildNumber(remoteTrainConfig.jenkinsUrl,
                remoteTrainConfig.jenkinsUserName, remoteTrainConfig.jenkinsPassword,
                remoteTrainConfig.jenkinsJobName, buildId, getWorkspace() + File.separator + "report");
        log.info("download am train report.html finish");
    }

    private String parseTrainVersionByReportHtml(TrainRequest trainRequest) {
        log.info("start parse report.html");
        String version = null;
        FileReader fileReader = null;
        BufferedReader bufferedReader = null;
        File reportHtmlFile = new File(getWorkspace() + File.separator + "report" + File.separator + "report.html");

        try {
            fileReader = new FileReader(reportHtmlFile);
            bufferedReader = new BufferedReader(fileReader);
            String line;

            while ((line = bufferedReader.readLine()) != null) {
                if (line.contains("项目版本")) {
                    int start = line.indexOf("<strong>");
                    int end = line.indexOf("</strong>");
                    version = line.substring(start + "<strong>".length(), end);
                    break;
                }
            }

        } catch (Exception ex) {
            log.error("parse report.html failed ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
        } finally {
            try {
                if (Objects.nonNull(fileReader)) {
                    fileReader.close();
                }
            } catch (Exception ex) {
                log.error("close fileReader failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
            }

            try {
                if (Objects.nonNull(bufferedReader)) {
                    bufferedReader.close();
                }
            } catch (Exception ex) {
                log.error("close bufferReader failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
            }
        }

        if (Objects.isNull(version)) {
            log.error("report.html format changed, cannot find version");
            notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.LABS_AM_REPORT_PARSE_FAILED.getCode(),
                    AdaptationTrainErrorCode.LABS_AM_REPORT_PARSE_FAILED.getMessage());
        } else {
            log.info("parse report.html finish, version={}", version);
        }

        return version;
    }

    private String parseModelIdByReportHtml(TrainRequest trainRequest) {
        log.info("start parse report.html");
        String modelId = null;
        FileReader fileReader = null;
        BufferedReader bufferedReader = null;
        File reportHtmlFile = new File(getWorkspace() + File.separator + "report" + File.separator + "report.html");

        try {
            fileReader = new FileReader(reportHtmlFile);
            bufferedReader = new BufferedReader(fileReader);
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                if (line.contains("模型ID")) {
                    int start = line.indexOf("<strong>");
                    int end = line.indexOf("</strong>");
                    modelId = line.substring(start + "<strong>".length(), end);
                    break;
                }
            }
        } catch (Exception ex) {
            log.error("parse report.html failed ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
        } finally {
            try {
                if (Objects.nonNull(bufferedReader)) {
                    bufferedReader.close();
                }
            } catch (Exception ex) {
                log.error("close bufferReader failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
            }

            try {
                if (Objects.nonNull(fileReader)) {
                    fileReader.close();
                }
            } catch (Exception ex) {
                log.error("close fileReader failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
            }
        }

        if (Objects.isNull(modelId)) {
            log.error("report.html format changed, cannot find modelId");
            notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.LABS_AM_REPORT_PARSE_FAILED.getCode(),
                    AdaptationTrainErrorCode.LABS_AM_REPORT_PARSE_FAILED.getMessage());
        } else {
            log.info("parse report.html finish, modelId={}", modelId);
        }

        return modelId;
    }

    /**
     * 用于将映射表转换成字符串，供JNI c解析用，规则 "key|value;key|value;"
     *
     * @param mappings
     * @return
     */
    private String encodeCmdWordMap2String(List<TaskCmdTypeMapping> mappings) {
        StringBuilder stringBuilder = new StringBuilder();

        /**
         * 根据spell长度降序排序，cover spell有包含关系的case，如15度，设置15度
         */
        mappingListSortBySpellLengthDecrease(mappings);
        for (TaskCmdTypeMapping mapping : mappings) {
            stringBuilder.append(mapping.getSpell()).append("|").append(mapping.getWord()).append(";");
        }

        log.info("encodeMapping={}", stringBuilder.toString());
        return stringBuilder.toString();
    }

    @Override
    public AdaptationTrainErrorCode pushTrainTaskInQueue(TrainRequest trainRequest) {
        synchronized (MUTEX) {
            if (trainRequestQueue.size() == QUEUE_MAX_SIZE) {
                log.info("train requestQueue full");
                return AdaptationTrainErrorCode.QUEUE_FULL;
            }

            trainRequestQueue.add(trainRequest);
            log.info("push new task in trainRequestQueue, modelId={}, mapping={}, now queue size={}",
                    trainRequest.getModelId(), trainRequest.getTaskCmdWordMapping(), trainRequestQueue.size());
            return AdaptationTrainErrorCode.SUCCESS;
        }
    }

    @Override
    public AdaptationTrainErrorCode trainRequestParameterCheck(TrainRequest trainRequest) {
        Set<String> taskSet = new HashSet<>(4);

        Map<String, String> wordSpellMap = new HashMap<>(128);

        int taskCount = 0;

        for (String taskId : trainRequest.getTaskCmdWordMapping().keySet()) {
            if (Objects.isNull(trainRequest.getTaskCmdWordMapping().get(taskId))) {
                log.info("not find mapping info in taskId {}", taskId);
                return AdaptationTrainErrorCode.TRAIN_REQUEST_PARAM_MAPPING_LOST;
            }

            if (!isGrammarExistInAliYunOss(taskId)) {
                log.info("not find grammar in ali yun Oss, taskId {}", taskId);
                return AdaptationTrainErrorCode.TRAIN_REQUEST_PARAM_TASK_ID_INVALID;
            }

            taskSet.add(taskId);
            taskCount++;

            if (taskSet.size() != taskCount) {
                log.info("contain duplicated taskId, do not support");
                return AdaptationTrainErrorCode.TRAIN_REQUEST_PARAM_DUPLICATED_TASK_ID;
            }

            for (TaskCmdTypeMapping taskCmdTypeMapping : trainRequest.getTaskCmdWordMapping().get(taskId)) {
                if (Objects.isNull(taskCmdTypeMapping.getSpell())) {
                    log.info("spell lost in taskId {}", taskId);
                    return AdaptationTrainErrorCode.TRAIN_REQUEST_PARAM_SPELL_LOST;
                }

                if (Objects.isNull(taskCmdTypeMapping.getWord())) {
                    log.info("word lost in taskId {}", taskId);
                    return AdaptationTrainErrorCode.TRAIN_REQUEST_PARAM_WORD_LOST;
                }

                if (Objects.isNull(taskCmdTypeMapping.getType())) {
                    log.info("type lost in taskId {}", taskId);
                    return AdaptationTrainErrorCode.TRAIN_REQUEST_PARAM_TYPE_LOST;
                }

                if (!COMMAND_TYPE.equals(taskCmdTypeMapping.getType()) &&
                        !WAKEUP_TYPE.equals(taskCmdTypeMapping.getType())) {
                    log.info("type invalid {}", taskCmdTypeMapping.getType());
                    return AdaptationTrainErrorCode.TRAIN_REQUEST_PARAM_TYPE_INVALID;
                }

                String spell = wordSpellMap.get(taskCmdTypeMapping.getWord());
                if (Objects.nonNull(spell) && !spell.equals(taskCmdTypeMapping.getSpell())) {
                    log.info("contain conflict spell, not support , word={}, spell={}， spell={}", taskCmdTypeMapping.getWord(),
                            taskCmdTypeMapping.getSpell(), spell);
                    return AdaptationTrainErrorCode.TRAIN_REQUEST_PARAM_DUPLICATED_SPELL;
                }

                wordSpellMap.put(taskCmdTypeMapping.getWord(), taskCmdTypeMapping.getSpell());
            }
        }

        return AdaptationTrainErrorCode.SUCCESS;
    }

    private int unzip(String zipFilePath, String destDir) {
        int errno = 0;

        File dir = new File(destDir);
        if (dir.mkdirs()) {
            log.info("create new unzip out directory {}", dir.getAbsolutePath());
        }

        FileInputStream fis = null;
        ZipInputStream zis = null;
        FileOutputStream fos = null;
        byte[] buffer = new byte[1024];
        try {
            fis = new FileInputStream(zipFilePath);
            zis = new ZipInputStream(fis);
            ZipEntry ze = zis.getNextEntry();

            while (Objects.nonNull(ze)) {
                String fileName = ze.getName();
                File newFile = new File(destDir + File.separator + fileName);

                new File(newFile.getParent()).mkdirs();

                try {
                    fos = new FileOutputStream(newFile);
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                } catch (Exception ex) {
                    errno = -1;
                } finally {
                    if (Objects.nonNull(fos)) {
                        fos.close();
                    }
                    zis.closeEntry();
                    ze = zis.getNextEntry();
                }
            }
        } catch (Exception ex) {
            errno = -1;
            log.error("unzip wav file failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
        } finally {
            try {
                if (Objects.nonNull(zis)) {
                    zis.closeEntry();
                    zis.close();
                }

                if (Objects.nonNull(fis)) {
                    fis.close();
                }
            } catch (Exception ex) {
                log.error("close zip failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
            }
        }

        return errno;
    }

    private void removeWakeupWordFromCmdWord(Set<String> wakeupWordSet, Set<String> cmdWordSet) {
        Set<String> newCmdSet = new HashSet<>(128);
        for (String word : cmdWordSet) {
            if (!wakeupWordSet.contains(word)) {
                newCmdSet.add(word);
            }
        }

        cmdWordSet.clear();
        newCmdSet.forEach(w -> cmdWordSet.add(w));
    }

    private void mappingListSortBySpellLengthDecrease(List<TaskCmdTypeMapping> mappingList) {
        log.info("original list={}", mappingList);
        Collections.sort(mappingList, (o1, o2) -> o2.getSpell().length() - o1.getSpell().length());
        log.info("sort list={}", mappingList);
    }

    private int audioSizeOverThresholdCheck(TrainRequest trainRequest) {
        log.info("start check audio wav file count");

        String wavFilePath = getWorkspace() + File.separator + "wav";

        Set<String> wakeupWordSet = new HashSet<>(4);
        Set<String> cmdWordSet = new HashSet<>(128);

        getWordsSetByType(trainRequest, wakeupWordSet, WAKEUP_TYPE);
        getWordsSetByType(trainRequest, cmdWordSet, COMMAND_TYPE);
        removeWakeupWordFromCmdWord(wakeupWordSet, cmdWordSet);

        Map<String, Integer> wakeupWavCntResult = new HashMap<>(128);
        Map<String, Integer> cmdWavCntResult = new HashMap<>(128);

        for (String taskId : trainRequest.getTaskCmdWordMapping().keySet()) {
            File[] files = new File(wavFilePath + File.separator + taskId).listFiles();
            if (Objects.isNull(files)) {
                continue;
            }

            List<TaskCmdTypeMapping> mappingList = trainRequest.getTaskCmdWordMapping().get(taskId);

            /**
             * 按照spell长度降序排列，cover spell有重叠的case，比如十五度、设置十五度，优先匹配长spell
             */
            mappingListSortBySpellLengthDecrease(mappingList);
            log.info("sort mapping list={}", mappingList);

            boolean isCmdMappingExist;
            for (File file : files) {
                isCmdMappingExist = false;
                for (TaskCmdTypeMapping mapping : mappingList) {
                    if (file.getName().contains(mapping.getSpell())) {
                        if (wakeupWordSet.contains(mapping.getWord())) {
                            wakeupWavCntResult.put(mapping.getWord(), wakeupWavCntResult.getOrDefault(mapping.getWord(), 0) + 1);
                        } else {
                            cmdWavCntResult.put(mapping.getWord(), cmdWavCntResult.getOrDefault(mapping.getWord(), 0) + 1);
                        }

                        isCmdMappingExist = true;
                        break;
                    }
                }

                if (!isCmdMappingExist) {
                    notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.AUDIO_CNT_CHECK_FAILED.getCode(),
                            file.getName() + " not find word mapping, spell word mapping lost, fatal error");
                    return -1;
                }
            }
        }

        log.info("wakeupSet={}", wakeupWordSet);
        log.info("cmdSet={}", cmdWordSet);
        log.info("cmdResult={}", cmdWavCntResult);
        log.info("wakeupResult={}", wakeupWavCntResult);

        String notifyMessageString = null;
        for (String wakeupWord : wakeupWordSet) {
            if (!wakeupWavCntResult.containsKey(wakeupWord)) {
                log.info("<{}> need not less than {} wav files, actual find 0 file", wakeupWord,
                        remoteTrainConfig.wakeupWavCntMinSize);

                notifyMessageString = "<" + wakeupWord + "> need not less than " +
                        remoteTrainConfig.wakeupWavCntMinSize + " wav files, actual find 0 file";
                notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.AUDIO_CNT_CHECK_FAILED.getCode(), notifyMessageString);
                break;
            }

            if (wakeupWavCntResult.get(wakeupWord) < remoteTrainConfig.wakeupWavCntMinSize) {
                log.info("<{}> need not less than {} wav files, actual find {}", wakeupWord,
                        remoteTrainConfig.wakeupWavCntMinSize, wakeupWavCntResult.get(wakeupWord));

                notifyMessageString = "<" + wakeupWord + "> need not less than " +
                        remoteTrainConfig.wakeupWavCntMinSize + " wav files, actual find " + wakeupWavCntResult.get(wakeupWord);
                notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.AUDIO_CNT_CHECK_FAILED.getCode(), notifyMessageString);
                break;
            }
        }

        if (Objects.nonNull(notifyMessageString)) {
            return -1;
        }

        for (String commandWord : cmdWordSet) {
            if (!cmdWavCntResult.containsKey(commandWord)) {
                log.info("<{}> need not less than {} wav files, actual find 0 file", commandWord,
                        remoteTrainConfig.commandWavCntMinSize);

                notifyMessageString = "<" + commandWord + "> need not less than " +
                        remoteTrainConfig.commandWavCntMinSize +
                        " wav files, actual find 0 file";
                notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.AUDIO_CNT_CHECK_FAILED.getCode(), notifyMessageString);
                break;
            }

            if (cmdWavCntResult.get(commandWord) < remoteTrainConfig.commandWavCntMinSize) {
                log.info("<{}> need not less than {} wav files, actual find {}", commandWord,
                        remoteTrainConfig.commandWavCntMinSize, cmdWavCntResult.get(commandWord));

                notifyMessageString = "<" + commandWord + "> need not less than " +
                        remoteTrainConfig.commandWavCntMinSize +
                        " wav files, actual find " + cmdWavCntResult.get(commandWord);
                notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.AUDIO_CNT_CHECK_FAILED.getCode(), notifyMessageString);
                break;
            }
        }

        log.info("check audio wav file count done");

        return Objects.isNull(notifyMessageString) ? 0 : -1;
    }

    private int downloadAudioByModelId(String modelId) {
        log.info("download record wav file start");
        String downloadFileName = getWorkspace() + File.separator + "wav" + File.separator + "audio.zip";
        String unzipDstFilePath = getWorkspace() + File.separator + "wav";
        String httpGetEndPoint = platformConfig.notifyServerUrl + "/v1/audio/download?modelId=" + modelId;
        int errno;
        int tryCount = 10;
        do {
            /**
             * 下载最多尝试10次，每次延时30秒
             */
            errno = httpClientUtils.httpDownloadFile(httpGetEndPoint, downloadFileName);
            if (errno != 0) {
                log.warn("download audio failed, retry, errno={}, tryCount={}", errno, tryCount);
                sleep(30 * 1000);
            }
        } while (errno != 0 && --tryCount > 0);

        if (errno != 0) {
            log.error("download audio failed, notify platform");
            notifyPlatformResultFailed(modelId, AdaptationTrainErrorCode.DOWNLOAD_AUDIO_FAILED.getCode(),
                    AdaptationTrainErrorCode.DOWNLOAD_AUDIO_FAILED.getMessage());
            return -1;
        }

        log.info("download record wav file finish, start unzip process");
        if (0 != unzip(downloadFileName, unzipDstFilePath)) {
            log.error("unzip audio failed, notify platform");
            notifyPlatformResultFailed(modelId, AdaptationTrainErrorCode.UNZIP_AUDIO_FAILED.getCode(),
                    AdaptationTrainErrorCode.UNZIP_AUDIO_FAILED.getMessage());
            return -1;
        }
        log.info("unzip done");

        try {
            FileUtils.forceDelete(new File(downloadFileName));
        } catch (Exception ex) {
            log.error("delete wav/audio.zip failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
        }

        return 0;
    }

    private boolean isGrammarExistInAliYunOss(String grammarName) {
        String objectName = getAliYunOSSObjectName(grammarName);
        OSS ossClient = new OSSClientBuilder().build(aliyunOssConfig.aliyunEndpoint,
                aliyunOssConfig.aliyunAccessKeyId,
                aliyunOssConfig.aliyunAccesskeySecret);
        boolean found = ossClient.doesObjectExist(aliyunOssConfig.aliyunBucketName, objectName);
        ossClient.shutdown();
        return found;
    }

    private String createMarkResultDirectory(String modelId) {
        Calendar now = Calendar.getInstance();
        String format = "%s" + File.separator + "%s";
        String uploadDirectoryName = String.format("V-%d-%02d%02d-%s", now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH), modelId);
        String uploadPath = String.format(format, getWorkspace(), uploadDirectoryName);
        File file = new File(String.format(format, uploadPath, ANNOTATION));
        if (file.mkdirs()) {
            log.info("create annotation dir={}", file.getAbsolutePath());
        }

        file = new File(String.format(format, uploadPath, SPEECH));
        if (file.mkdirs()) {
            log.info("create speech dir={}", file.getAbsolutePath());
        }

        file = new File(String.format(format, uploadPath, DOC));
        if (file.mkdirs()) {
            log.info("create doc dir={}", file.getAbsolutePath());
        }

        return uploadDirectoryName;
    }

    private int copyWavFile(String uploadDirName, String taskId) {
        int errno = -1;
        String format = "%s" + File.separator + "%s";
        String uploadPath = String.format(format, getWorkspace(), uploadDirName);
        log.info("uploadPath={}", uploadPath);
        File[] srcFileList = new File(String.format(format, getWorkspace() + File.separator + "wav", taskId)).listFiles();
        File dstDir = new File(String.format(format, uploadPath, SPEECH));

        try {
            if (Objects.nonNull(srcFileList)) {
                for (File file : srcFileList) {
                    FileUtils.copyFileToDirectory(file, dstDir);
                }
            }
            errno = 0;
        } catch (IOException ex) {
            log.error("copy wav failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
        }

        return errno;
    }

    private int mergeTxtFile(String uploadDirName, String taskId) {
        int errno = 0;

        String uploadPath = getWorkspace() + File.separator + uploadDirName;
        FileWriter fileWriter = null;
        FileReader fileReader = null;
        BufferedReader bufferedReader = null;

        try {
            String dstMergeFileName = uploadPath + File.separator + ANNOTATION + File.separator + uploadDirName + ".txt";
            log.info("mergeFile={}", dstMergeFileName);
            File dstFile = new File(dstMergeFileName);
            if (dstFile.createNewFile()) {
                log.info("create new file {}", dstFile.getAbsolutePath());
            }

            fileWriter = new FileWriter(dstFile, true);
            File[] txtFileList = new File(getWorkspace() + File.separator + "out" + File.separator + taskId).listFiles();

            if (Objects.nonNull(txtFileList)) {
                for (File file : txtFileList) {
                    try {
                        fileReader = new FileReader(file);
                        bufferedReader = new BufferedReader(fileReader);
                        String line;
                        while ((line = bufferedReader.readLine()) != null) {
                            fileWriter.write(line + "\r\n");
                            fileWriter.flush();
                        }
                    } catch (Exception ex) {
                        errno = -1;
                        log.error("merge txt write failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
                    } finally {
                        try {
                            if (Objects.nonNull(bufferedReader)) {
                                bufferedReader.close();
                            }
                        } catch (Exception ex) {
                            log.error("close bufferReader failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
                        }

                        try {
                            if (Objects.nonNull(fileReader)) {
                                fileReader.close();
                            }
                        } catch (Exception ex) {
                            log.error("close fileReader failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
                        }
                    }
                }
            }

        } catch (Exception ex) {
            errno = -1;
            log.error("merge txt failed ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
        } finally {
            if (Objects.nonNull(fileWriter)) {
                try {
                    fileWriter.close();
                } catch (Exception ex) {
                    log.error("merge txt fileWrite close failed ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
                }
            }
        }

        return errno;
    }

    private int markResultParseUploadFormat(String uploadDirName, String taskId) {
        if (copyWavFile(uploadDirName, taskId) != 0) {
            return -1;
        }

        return mergeTxtFile(uploadDirName, taskId);
    }

    private String markProcess(TrainRequest trainRequest) {
        String uploadDirName = createMarkResultDirectory(trainRequest.getModelId().replace("-", ""));
        String modelId = trainRequest.getModelId();
        for (String taskId : trainRequest.getTaskCmdWordMapping().keySet()) {
            String cmdWordMapping = encodeCmdWordMap2String(trainRequest.getTaskCmdWordMapping().get(taskId));

            if (!tryDownloadGrammarFromAliYunOss(taskId)) {
                log.error("download grammar from ali yun oss failed, notify platform train failed result");
                notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.DOWNLOAD_GRAMMAR_FAILED.getCode(),
                        AdaptationTrainErrorCode.DOWNLOAD_GRAMMAR_FAILED.getMessage());
                return null;
            }

            log.info("mark process: modelId={}, taskId={}, cmdMapping={}", modelId, taskId, cmdWordMapping);
            int errno = localAsrEngine.markProcess(taskId, cmdWordMapping);
            if (errno != 0) {
                log.error("native mark process failed errno={}, notify platform train failed result", errno);
                notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.NATIVE_MARK_PROCESS_FAILED.getCode(),
                        AdaptationTrainErrorCode.NATIVE_MARK_PROCESS_FAILED.getMessage());
                return null;
            }

            if (0 != markResultParseUploadFormat(uploadDirName, taskId)) {
                log.error("mark Result parse process failed errno={}, notify platform train failed result", errno);
                notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.MERGE_MARK_RESULT_FAILED.getCode(),
                        AdaptationTrainErrorCode.MERGE_MARK_RESULT_FAILED.getMessage());
                return null;
            }
        }

        return uploadDirName;
    }

    private String getAliYunOSSObjectName(String grammarName) {
        return String.format("GRAMMAR-%s", grammarName);
    }

    private String getGrammarPath() {
        return getWorkspace() + File.separator + "grammar" + File.separator;
    }

    private String getGrammarFileName(String grammarName) {
        return getGrammarPath() + grammarName;
    }

    private boolean tryDownloadGrammarFromAliYunOss(String grammarName) {
        String objectName = getAliYunOSSObjectName(grammarName);
        String grammarFileName = getGrammarFileName(grammarName);

        OSS ossClient = new OSSClientBuilder().build(aliyunOssConfig.aliyunEndpoint,
                aliyunOssConfig.aliyunAccessKeyId,
                aliyunOssConfig.aliyunAccesskeySecret);
        boolean found = ossClient.doesObjectExist(aliyunOssConfig.aliyunBucketName, objectName);
        if (found) {
            ossClient.getObject(new GetObjectRequest(aliyunOssConfig.aliyunBucketName, objectName), new File(grammarFileName));
            log.info("download grammar [{}] from AliYun OSS [{}] to local [{}] success", grammarName, objectName, grammarFileName);
        } else {
            log.warn("grammar [{}] not exist, please build first", grammarName);
        }

        ossClient.shutdown();
        return found;
    }

    private void createWorkspaceDirectory() {
        String workspace = getWorkspace();
        /**
         * 存放下载的录音文件包
         */
        File file = new File(workspace + File.separator + "wav");
        if (file.mkdirs()) {
            log.info("make dir {}", file.getAbsolutePath());
        }

        /**
         * 存放标注结果文件
         */
        file = new File(workspace + File.separator + "out");
        if (file.mkdirs()) {
            log.info("make dir {}", file.getAbsolutePath());
        }

        /**
         * 存放am训练报告
         */
        file = new File(workspace + File.separator + "report");
        if (file.mkdirs()) {
            log.info("make dir {}", file.getAbsolutePath());
        }

        /**
         * 存放KWS相关文件
         */
        file = new File(workspace + File.separator + "kws");
        if (file.mkdirs()) {
            log.info("make dir {}", file.getAbsolutePath());
        }

        log.info("create dir done");
    }

    private void clearTempResource() {
        String workspace = getWorkspace();
        log.info("start clear resource");
        try {
            File[] files = new File(workspace).listFiles();
            if (Objects.nonNull(files)) {
                for (File file : files) {
                    /**
                     * 删除以V-开头的标注结果文件夹
                     */
                    if (file.getName().contains("V-")) {
                        log.info("find {}, delete it", file.getName());
                        FileUtils.forceDelete(file);
                    }
                }
            }

            File out = new File(workspace + File.separator + "out");
            if (out.exists()) {
                FileUtils.forceDelete(out);
            }

            File kws = new File(workspace + File.separator + "kws");
            if (kws.exists()) {
                FileUtils.forceDelete(kws);
            }

            File report = new File(workspace + File.separator + "report");
            if (report.exists()) {
                FileUtils.forceDelete(report);
            }

            File wav = new File(workspace + File.separator + "wav");
            if (wav.exists()) {
                FileUtils.forceDelete(wav);
            }
        } catch (Exception ex) {
            log.error("clear temp resource failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
        }

        log.info("clear resource finish");
    }

    /**
     * 后台任务，用于处理标注和Jenkins模型训练任务
     */
    @Override
    public void run() {

        while (true) {
            TrainRequest trainRequest = getOneTrainRequest();
            if (Objects.nonNull(trainRequest)) {
                try {
                    log.info("start process train request={}", trainRequest);
                    clearTempResource();
                    createWorkspaceDirectory();

                    if (0 != downloadAudioByModelId(trainRequest.getModelId())) {
                        continue;
                    }

                    if (0 != audioSizeOverThresholdCheck(trainRequest)) {
                        continue;
                    }

                    String uploadDirName = markProcess(trainRequest);
                    if (Objects.isNull(uploadDirName)) {
                        continue;
                    }

                    if (0 != uploadMarkResult(uploadDirName, trainRequest.getModelId())) {
                        continue;
                    }

                    if (0 != exchangeUploadFile2Internal(trainRequest)) {
                        continue;
                    }

                    int buildId = syncRemoteTrainProcess(trainRequest, uploadDirName);
                    if (buildId == -1) {
                        continue;
                    }

                    downloadTrainReportHtml(buildId);

                    String amVersion = parseTrainVersionByReportHtml(trainRequest);
                    if (Objects.isNull(amVersion)) {
                        continue;
                    }

                    String modelId = parseModelIdByReportHtml(trainRequest);
                    if (Objects.isNull(modelId)) {
                        continue;
                    }

                    buildId = buildKwsEngineProcess(amVersion, trainRequest.getModelId(), modelId);
                    if (buildId == -1) {
                        continue;
                    }

                    downloadKwsEngine(buildId);

                    String downloadUrl = uploadKwsEngine(trainRequest);
                    if (Objects.isNull(downloadUrl)) {
                        notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.UPLOAD_KWS_ENGINE_TO_NAS_FAILED.getCode(),
                                AdaptationTrainErrorCode.UPLOAD_KWS_ENGINE_TO_NAS_FAILED.getMessage());
                        continue;
                    }

                    notifyPlatformResultOk(trainRequest, downloadUrl);
                } catch (Exception ex) {
                    log.error("train process failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
                    notifyPlatformResultFailed(trainRequest.getModelId(), AdaptationTrainErrorCode.FATAL_SYSTEM_ERROR.getCode(),
                            "fatal system error " + exceptionFormatUtils.exceptionFormatPrint(ex));
                } finally {
                    if (Objects.nonNull(uploadFilePath)) {
                        removeMarkResultFromServer(uploadFilePath);
                        uploadFilePath = null;
                    }
                }
            }

            sleep(1000);
        }
    }
}

