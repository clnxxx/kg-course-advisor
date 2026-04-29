package com.clnx.kg_course_advisor.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Utility class for user ID handling.
 * Provides methods to build user ID candidates for Neo4j queries,
 * supporting both raw numeric IDs and prefixed IDs (e.g., "U_123").
 */
public final class UserIdUtils {

    private UserIdUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Build a list of candidate user IDs for Neo4j matching.
     * Given a user ID, returns both the raw ID and the "U_" prefixed version
     * to support different ID formats in the graph database.
     *
     * @param userId the user ID (can be numeric string or "U_" prefixed)
     * @return list of candidate IDs to match against
     */
    public static List<String> buildUserIdCandidates(String userId) {
        String raw = userId == null ? "" : userId.trim();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (!raw.isBlank()) {
            ids.add(raw);
            if (raw.startsWith("U_") && raw.length() > 2) {
                ids.add(raw.substring(2));
            } else {
                ids.add("U_" + raw);
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * Build a list of candidate user IDs from a Long user ID.
     *
     * @param userId the user ID as Long
     * @return list of candidate IDs to match against
     */
    public static List<String> buildUserIdCandidates(Long userId) {
        return buildUserIdCandidates(userId == null ? "" : String.valueOf(userId));
    }
}
