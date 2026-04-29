package com.clnx.kg_course_advisor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphStatsDto {

    private int nodeCount;
    private int edgeCount;
}
