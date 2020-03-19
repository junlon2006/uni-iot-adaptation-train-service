package com.unisound.aios.adaptationtrain.controller;

import com.unisound.aios.adaptationtrain.data.Result;
import com.unisound.aios.adaptationtrain.data.TrainRequest;
import com.unisound.aios.adaptationtrain.service.AdaptationTrainService;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/adaptation-train")
public class AdaptationTrainController {

    @Autowired
    private AdaptationTrainService adaptationTrainService;

    @PostMapping(value = "train")
    @ApiOperation(value = "标注训练请求, 该接口为异步接口，响应将直接返回，训练结果异步回调请求方")
    public Result train(@RequestBody @Validated @ApiParam(value = "模型训练请求参数", required = true) TrainRequest request) {
        return adaptationTrainService.train(request);
    }
}
