package com.unisound.aios.adaptationtrain.service;

import com.unisound.aios.adaptationtrain.data.Result;
import com.unisound.aios.adaptationtrain.data.TrainRequest;

public interface AdaptationTrainService {
    Result train(TrainRequest request);
}
