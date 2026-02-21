package com.worldmind.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scans text content (PRD documents, mission requests) for keywords
 * indicating required Cloud Foundry services.
 * <p>
 * Used by {@link com.worldmind.core.nodes.GenerateClarifyingQuestionsNode}
 * to auto-detect services and present them as pre-filled suggestions
 * in the CF service binding question.
 */
public final class ServiceKeywordDetector {

    /**
     * A service detected from keyword matching in text content.
     */
    public record DetectedService(
        String serviceType,          // e.g., "postgresql"
        String displayName,          // e.g., "PostgreSQL Database"
        List<String> matchedKeywords // keywords that triggered the detection
    ) {}

    private record ServicePattern(
        String serviceType,
        String displayName,
        List<String> keywords
    ) {}

    /**
     * Keywords that require word-boundary matching to avoid false positives.
     * Short or ambiguous tokens (e.g. "s3", "mongo") could otherwise match
     * unrelated content like filenames or longer words.
     */
    private static final Set<String> WORD_BOUNDARY_KEYWORDS = Set.of("mongo", "s3");

    private static final List<ServicePattern> SERVICE_PATTERNS = List.of(
            new ServicePattern("postgresql", "PostgreSQL Database",
                    List.of("postgresql", "postgres", "psql", "relational database", "jpa", "spring-data-jpa")),
            new ServicePattern("mysql", "MySQL Database",
                    List.of("mysql", "mariadb")),
            new ServicePattern("mongodb", "MongoDB Database",
                    List.of("mongodb", "mongo", "document database")),
            new ServicePattern("redis", "Redis Cache",
                    List.of("redis", "redis cache", "distributed cache", "in-memory cache", "session store")),
            new ServicePattern("rabbitmq", "RabbitMQ Message Queue",
                    List.of("rabbitmq", "message queue", "amqp")),
            new ServicePattern("s3", "S3/Blob Storage",
                    List.of("s3", "blob storage", "object storage", "file storage"))
    );

    private ServiceKeywordDetector() {} // utility class

    /**
     * Scans the given text for service keywords and returns detected services.
     * Matching is case-insensitive.
     *
     * @param text the text to scan (PRD content, mission request, or both)
     * @return list of detected services, empty if none found or text is blank
     */
    public static List<DetectedService> detect(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String lowerText = text.toLowerCase();
        var detected = new ArrayList<DetectedService>();

        for (var pattern : SERVICE_PATTERNS) {
            var matched = new ArrayList<String>();
            for (String keyword : pattern.keywords()) {
                if (matchesKeyword(lowerText, keyword)) {
                    matched.add(keyword);
                }
            }
            if (!matched.isEmpty()) {
                detected.add(new DetectedService(pattern.serviceType(), pattern.displayName(), matched));
            }
        }

        return detected;
    }

    private static boolean matchesKeyword(String lowerText, String keyword) {
        if (WORD_BOUNDARY_KEYWORDS.contains(keyword)) {
            return Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b")
                    .matcher(lowerText).find();
        }
        return lowerText.contains(keyword);
    }
}
