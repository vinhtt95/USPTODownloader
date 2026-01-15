package com.vinhtt.usptodownloader.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PatentDoc {
    private String documentId;
    private String inventionTitle;
    private String datePublished;
}