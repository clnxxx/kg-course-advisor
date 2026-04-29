package com.clnx.kg_course_advisor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEvent {

    private String type;
    private Object payload;

    public static ChatStreamEvent of(String type, Object payload) {
        return ChatStreamEvent.builder()
                .type(type)
                .payload(payload)
                .build();
    }
}
