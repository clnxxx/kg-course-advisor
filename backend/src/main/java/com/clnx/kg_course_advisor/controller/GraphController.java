package com.clnx.kg_course_advisor.controller;

import com.clnx.kg_course_advisor.dto.GraphContextRequest;
import com.clnx.kg_course_advisor.dto.GraphContextResponse;
import com.clnx.kg_course_advisor.service.KnowledgeGraphContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class GraphController {

    private final KnowledgeGraphContextService knowledgeGraphContextService;

    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> getGraphData(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String focusId
    ) {
        return ResponseEntity.ok(knowledgeGraphContextService.getOverviewGraph(limit, focusId));
    }

    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> searchNodes(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ResponseEntity.ok(knowledgeGraphContextService.searchNodes(keyword, limit));
    }

    @PostMapping("/context")
    public ResponseEntity<GraphContextResponse> getGraphContext(@RequestBody GraphContextRequest request) {
        return ResponseEntity.ok(knowledgeGraphContextService.buildContext(request, extractCurrentUserId()));
    }

    private Long extractCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof Long userId ? userId : null;
    }
}
