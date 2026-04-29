package com.clnx.kg_course_advisor.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    @Value("${app.agent.mode:multi}")
    private String agentMode;

    @Value("${spring.ai.openai.base-url:}")
    private String modelBaseUrl;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String modelName;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "UP");
        payload.put("agentMode", agentMode);
        payload.put("model", modelName);
        payload.put("baseUrl", modelBaseUrl);
        return ResponseEntity.ok(payload);
    }
}
