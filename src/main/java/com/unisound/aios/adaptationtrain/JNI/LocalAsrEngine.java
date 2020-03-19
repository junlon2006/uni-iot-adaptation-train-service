package com.unisound.aios.adaptationtrain.JNI;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LocalAsrEngine {
    static {
        log.info("load library libWrapperEngine.so");
        System.loadLibrary("WrapperEngine");
    }

    public native int markProcess(String grammarName, String cmdWord);
}
