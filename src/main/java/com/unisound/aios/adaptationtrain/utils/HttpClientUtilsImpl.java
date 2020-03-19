package com.unisound.aios.adaptationtrain.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class HttpClientUtilsImpl implements HttpClientUtils {

    @Autowired
    private ExceptionFormatUtils exceptionFormatUtils;

    @Override
    public int httpDownloadFile(String endPoint, String fileName) {
        int errno = -1;
        CloseableHttpClient httpClient = HttpClientBuilder.create().build();
        CloseableHttpResponse response = null;
        HttpGet httpGet = new HttpGet(endPoint);
        try {
            response = httpClient.execute(httpGet);
            HttpEntity httpEntity = response.getEntity();
            if (Objects.nonNull(httpEntity)) {
                FileOutputStream fileOutputStream = new FileOutputStream(new File(fileName));
                httpEntity.writeTo(fileOutputStream);
                fileOutputStream.flush();
                fileOutputStream.close();
            }

            errno = 0;
        } catch (Exception ex) {
            log.error("http get failed ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
        } finally {
            if (Objects.nonNull(response)) {
                try {
                    response.close();
                } catch (Exception ex) {
                    log.error("close httpResponse failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
                }
            }

            try {
                httpClient.close();
            } catch (Exception ex) {
                log.error("close httpClient failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
            }
        }

        return errno;
    }

    @Override
    public String httpPostWithJsonString(String endPoint, String jsonParameter) {
        String result = null;
        CloseableHttpClient httpClient = null;
        CloseableHttpResponse httpResponse = null;
        try {
            httpClient = HttpClientBuilder.create().build();
            HttpPost httpPost = new HttpPost(endPoint);

            httpPost.addHeader("Content-type", "application/json");

            StringEntity stringEntity = new StringEntity(jsonParameter);
            httpPost.setEntity(stringEntity);

            httpResponse = httpClient.execute(httpPost);
            if (httpResponse.getStatusLine().getStatusCode() != 200) {
                log.warn("http request failed, code={} ", httpResponse.getStatusLine().getStatusCode());
                return null;
            }

            HttpEntity responseEntity = httpResponse.getEntity();
            if (Objects.nonNull(responseEntity)) {
                result = EntityUtils.toString(responseEntity, "UTF-8");
                log.info("result={}", result);
            }
        } catch (Exception ex) {
            log.error("http post failed ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
        } finally {
            try {
                if (Objects.nonNull(httpResponse)) {
                    httpResponse.close();
                }
            } catch (Exception ex) {
                log.error("close httpResponse failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
            }

            try {
                if (Objects.nonNull(httpClient)) {
                    httpClient.close();
                }
            } catch (Exception ex) {
                log.error("close httpClient failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
            }
        }

        return result;
    }

    @Override
    public String httpUploadMultiPart(Map<String, File> files, Map<String, String> textParameter, String url) {
        String responseBody = null;
        CloseableHttpClient httpClient = HttpClientBuilder.create().build();
        CloseableHttpResponse httpResponse = null;

        HttpPost httpPost = new HttpPost(url);

        MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create();

        for (String fileName : files.keySet()) {
            File file = files.get(fileName);
            multipartEntityBuilder.addBinaryBody(fileName, file);
        }

        for (String k : textParameter.keySet()) {
            String v = textParameter.get(k);
            multipartEntityBuilder.addTextBody(k, v);
        }

        HttpEntity httpEntity = multipartEntityBuilder.build();
        httpPost.setEntity(httpEntity);

        try {
            httpResponse = httpClient.execute(httpPost);
            HttpEntity responseEntity = httpResponse.getEntity();
            int statusCode = httpResponse.getStatusLine().getStatusCode();
            log.info("start http post={}", statusCode);
            if (statusCode == 200) {
                log.info("http post success");
                BufferedReader reader = new BufferedReader(new InputStreamReader(responseEntity.getContent()));
                StringBuilder buffer = new StringBuilder();
                String str;
                while (!StringUtils.isEmpty(str = reader.readLine())) {
                    buffer.append(str);
                }

                responseBody = buffer.toString();
            }
        } catch (Exception ex) {
            log.info("http multipart post failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
        } finally {
            try {
                httpClient.close();
                if (Objects.nonNull(httpResponse)) {
                    httpResponse.close();
                }
            } catch (Exception ex) {
                log.error("http multipart post close failed, ex={}", exceptionFormatUtils.exceptionFormatPrint(ex));
            }
        }

        return responseBody;
    }
}