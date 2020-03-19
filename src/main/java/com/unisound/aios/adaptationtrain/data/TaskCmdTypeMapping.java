package com.unisound.aios.adaptationtrain.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotEmpty;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TaskCmdTypeMapping {
    @NotEmpty
    private String word;
    @NotEmpty
    private String spell;
    @NotEmpty
    private String type;
}
