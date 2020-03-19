package com.unisound.aios.adaptationtrain.config;

import com.unisound.aios.adaptationtrain.data.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler
    public Result exceptionHandler(Exception ex) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("stack trace: ");
        stringBuilder.append(ex.getStackTrace()[0].toString());

        stringBuilder.append(" exception type: ");
        stringBuilder.append(ex.getMessage());

        String errMessage = stringBuilder.toString();
        log.error("catch global exception: {}", errMessage);

        return Result.builder()
                .code(500)
                .message(errMessage)
                .build();
    }
}
