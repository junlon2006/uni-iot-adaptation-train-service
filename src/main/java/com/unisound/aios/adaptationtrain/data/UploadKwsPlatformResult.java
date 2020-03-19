package com.unisound.aios.adaptationtrain.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UploadKwsPlatformResult {
    private String version;
    private String status;
    private int code;
    private String message;
    private String data;
}
