package com.clnx.kg_course_advisor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphNodeDto {

    private String id;
    private String label;
    private String group;
    private String title;
    private boolean highlighted;
    private Map<String, Object> metadata;
}
