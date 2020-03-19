package com.unisound.aios.adaptationtrain.utils;

import org.springframework.stereotype.Component;

@Component
public class ExceptionFormatUtilsImpl implements ExceptionFormatUtils {

    @Override
    public String exceptionFormatPrint(Exception exception) {
        return exception.getStackTrace()[0].toString() + " | " + exception.getMessage();
    }
}
