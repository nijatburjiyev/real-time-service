package com.edwardjones.cre.model.dto;

import lombok.Data;

@Data
public class AdChangeEvent {
    private String pjNumber;
    private String changeType;
    private String property;
    private String beforeValue;
    private String newValue;
}
