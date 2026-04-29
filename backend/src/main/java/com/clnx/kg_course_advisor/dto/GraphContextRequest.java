package com.clnx.kg_course_advisor.dto;

import lombok.Data;

import java.util.List;

@Data
public class GraphContextRequest {

    private String scenario;
    private List<String> courseIds;
    private List<String> courseNames;
    private List<String> conceptNames;
    private List<String> teacherNames;
    private Boolean includeSchedule;
    private Boolean includePrerequisites;
    private Boolean includeConflicts;
    private Integer limit;
}
