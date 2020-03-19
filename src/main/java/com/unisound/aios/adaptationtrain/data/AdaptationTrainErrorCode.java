package com.unisound.aios.adaptationtrain.data;

public enum AdaptationTrainErrorCode {
    SUCCESS(0, "SUCCESS"),
    QUEUE_FULL(-1, "only 100 task support, train task queue full, please wait one hour"),
    DOWNLOAD_AUDIO_FAILED(-2, "download audio failed"),
    UNZIP_AUDIO_FAILED(-3, "unzip audio failed, only .zip format support, please check"),
    AUDIO_CNT_CHECK_FAILED(-4, "check audio count failed"),
    DOWNLOAD_GRAMMAR_FAILED(-5, "download grammar from AliYun oss failed"),
    NATIVE_MARK_PROCESS_FAILED(-6, "native mark failed, please check wav format, only support 16K 16bit 1channel"),
    MERGE_MARK_RESULT_FAILED(-7, "merge mark result failed, fatal error"),
    SCP_MARK_RESULT_FAILED(-8, "labs scp audio and mark result to 10.10.20.230 failed"),
    LABS_DATA_IMPORT_FAILED(-9, "labs data import jenkins build failed"),
    LABS_AM_TRAIN_FAILED(-10, "labs am train jenkins build failed"),
    LABS_AM_REPORT_PARSE_FAILED(-11, "labs am train report.html parse failed"),
    LABS_KWS_ENGINE_BUILD_FAILED(-12, "labs kws engine jenkins build failed"),
    UPLOAD_KWS_ENGINE_TO_NAS_FAILED(-13, "upload kws engine to nas failed"),
    FATAL_SYSTEM_ERROR(-14, "fatal system error"),

    TRAIN_REQUEST_PARAM_MAPPING_LOST(-15, "not find mapping info in taskId"),
    TRAIN_REQUEST_PARAM_TASK_ID_INVALID(-16, "not find grammar in ali yun Oss, taskId invalid"),
    TRAIN_REQUEST_PARAM_DUPLICATED_TASK_ID(-17, "contain duplicated taskId, do not support"),
    TRAIN_REQUEST_PARAM_SPELL_LOST(-18, "spell lost in taskId"),
    TRAIN_REQUEST_PARAM_WORD_LOST(-19, "word lost in taskId"),
    TRAIN_REQUEST_PARAM_TYPE_LOST(-20, "type lost in taskId"),
    TRAIN_REQUEST_PARAM_TYPE_INVALID(-21, "type invalid, must be command or wakeup"),
    TRAIN_REQUEST_PARAM_DUPLICATED_SPELL(-22, "contain duplicated spell, not support"),
    TRAIN_REQUEST_PARAM_DUPLICATED_WORD(-23, "contain duplicated word, not support");

    private Integer code;
    private String message;

    AdaptationTrainErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
