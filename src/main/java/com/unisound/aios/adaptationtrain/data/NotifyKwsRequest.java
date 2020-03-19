package com.unisound.aios.adaptationtrain.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NotifyKwsRequest {
    private String modelId;
    private String url;
    private int status;
    private int errorCode;
    private String errorMsg;
}
