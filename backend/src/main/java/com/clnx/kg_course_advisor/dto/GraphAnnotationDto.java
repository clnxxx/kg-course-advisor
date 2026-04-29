package com.clnx.kg_course_advisor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphAnnotationDto {

    private String id;
    private String title;
    private String content;
    private String tone;
    private List<String> relatedNodeIds;
}
