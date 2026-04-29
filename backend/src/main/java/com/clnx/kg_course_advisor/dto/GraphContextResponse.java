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
public class GraphContextResponse {

    private List<GraphNodeDto> nodes;
    private List<GraphEdgeDto> edges;
    private List<String> focusNodeIds;
    private List<GraphAnnotationDto> annotations;
    private GraphStatsDto stats;
}
