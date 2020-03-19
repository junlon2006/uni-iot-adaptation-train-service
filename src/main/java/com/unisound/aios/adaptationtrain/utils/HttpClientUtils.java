package com.unisound.aios.adaptationtrain.utils;

import java.io.File;
import java.util.Map;

public interface HttpClientUtils {

    int httpDownloadFile(String endPoint, String fileName);

    String httpPostWithJsonString(String endPoint, String jsonParameter);

    String httpUploadMultiPart(Map<String, File> files, Map<String, String> textParameter, String endPoint);
}