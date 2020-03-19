package com.unisound.aios.adaptationtrain.data;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotEmpty;
import java.util.Map;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(value = "训练服务请求参数")
public class TrainRequest {
    @NotEmpty
    @ApiModelProperty(name = "模型ID，是32位的uuid", required = true, example = "12345678901234567890asdfghjkjnbg")
    private String modelId;

    @NotEmpty
    @ApiModelProperty(name = "key为task_id, value为词表，包含命令词、拼音、类型", required = true, example = "{\n" +
            "    \"task_id_123456\":[\n" +
            "        {\n" +
            "            \"spell\":\"zhinengdianti\",\n" +
            "            \"type\":\"wakeup\",\n" +
            "            \"word\":\"智能电梯\"\n" +
            "        },\n" +
            "        {\n" +
            "            \"spell\":\"diantishangxing\",\n" +
            "            \"type\":\"command\",\n" +
            "            \"word\":\"电梯上行\"\n" +
            "        }\n" +
            "    ],\n" +
            "    \"task_id_1234567\":[\n" +
            "        {\n" +
            "            \"spell\":\"diantixiaxing\",\n" +
            "            \"type\":\"command\",\n" +
            "            \"word\":\"电梯下行\"\n" +
            "        }\n" +
            "    ]\n" +
            "}")
    private Map<String, List<TaskCmdTypeMapping>> taskCmdWordMapping;
}
