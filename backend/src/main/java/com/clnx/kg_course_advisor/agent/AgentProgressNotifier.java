package com.clnx.kg_course_advisor.agent;

import java.util.function.Consumer;

/**
 * Stream progress notifier for tool-calling lifecycle.
 * Uses per-request thread-local callback to push status messages to SSE stream.
 */
public final class AgentProgressNotifier {

    private static final ThreadLocal<Consumer<String>> CALLBACK = new InheritableThreadLocal<>();

    private AgentProgressNotifier() {
    }

    public static void setCallback(Consumer<String> callback) {
        CALLBACK.set(callback);
    }

    public static void clearCallback() {
        CALLBACK.remove();
    }

    public static void publish(String status) {
        Consumer<String> callback = CALLBACK.get();
        if (callback != null && status != null && !status.isBlank()) {
            callback.accept(status);
        }
    }
}
