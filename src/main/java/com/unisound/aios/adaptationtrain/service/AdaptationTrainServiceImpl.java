package com.unisound.aios.adaptationtrain.service;

import com.unisound.aios.adaptationtrain.data.AdaptationTrainErrorCode;
import com.unisound.aios.adaptationtrain.data.Result;
import com.unisound.aios.adaptationtrain.data.TrainRequest;
import com.unisound.aios.adaptationtrain.utils.AdaptationTrainUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AdaptationTrainServiceImpl implements AdaptationTrainService {

    @Autowired
    private AdaptationTrainUtils adaptationTrainUtils;

    @Override
    public Result train(TrainRequest request) {

        AdaptationTrainErrorCode errorCode = adaptationTrainUtils.trainRequestParameterCheck(request);
        if (errorCode != AdaptationTrainErrorCode.SUCCESS) {
            return Result.builder()
                    .code(errorCode.getCode())
                    .message(errorCode.getMessage())
                    .build();
        }

        errorCode = adaptationTrainUtils.pushTrainTaskInQueue(request);
        if (errorCode == AdaptationTrainErrorCode.SUCCESS) {
            return Result.builder()
                    .code(AdaptationTrainErrorCode.SUCCESS.getCode())
                    .message(AdaptationTrainErrorCode.SUCCESS.getMessage())
                    .build();
        }

        return Result.builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .build();
    }
}
