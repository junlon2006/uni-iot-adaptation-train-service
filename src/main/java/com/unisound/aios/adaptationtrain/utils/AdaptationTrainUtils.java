package com.unisound.aios.adaptationtrain.utils;

import com.unisound.aios.adaptationtrain.data.AdaptationTrainErrorCode;
import com.unisound.aios.adaptationtrain.data.TrainRequest;

public interface AdaptationTrainUtils {

    AdaptationTrainErrorCode trainRequestParameterCheck(TrainRequest trainRequest);

    AdaptationTrainErrorCode pushTrainTaskInQueue(TrainRequest trainRequest);
}