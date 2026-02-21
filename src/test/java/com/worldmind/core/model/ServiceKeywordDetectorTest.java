package com.worldmind.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ServiceKeywordDetector}.
 */
class ServiceKeywordDetectorTest {

    @Test
    @DisplayName("detects PostgreSQL from 'postgresql' keyword")
    void detectsPostgresqlKeyword() {
        var result = ServiceKeywordDetector.detect("This app uses PostgreSQL for data storage");
        assertEquals(1, result.size());
        assertEquals("postgresql", result.get(0).serviceType());
        assertEquals("PostgreSQL Database", result.get(0).displayName());
        assertTrue(result.get(0).matchedKeywords().contains("postgresql"));
    }

    @Test
    @DisplayName("detects PostgreSQL from 'JPA' keyword")
    void detectsPostgresqlFromJpa() {
        var result = ServiceKeywordDetector.detect("The service layer uses JPA for persistence");
        assertEquals(1, result.size());
        assertEquals("postgresql", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("jpa"));
    }

    @Test
    @DisplayName("detects PostgreSQL from 'spring-data-jpa' keyword")
    void detectsPostgresqlFromSpringDataJpa() {
        var result = ServiceKeywordDetector.detect("Add spring-data-jpa dependency for database access");
        assertEquals(1, result.size());
        assertEquals("postgresql", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("spring-data-jpa"));
    }

    @Test
    @DisplayName("detects Redis from 'redis' keyword")
    void detectsRedis() {
        var result = ServiceKeywordDetector.detect("Use Redis for caching frequently accessed data");
        assertEquals(1, result.size());
        assertEquals("redis", result.get(0).serviceType());
        assertEquals("Redis Cache", result.get(0).displayName());
        assertTrue(result.get(0).matchedKeywords().contains("redis"));
    }

    @Test
    @DisplayName("detects Redis from 'distributed cache' keyword")
    void detectsRedisFromDistributedCache() {
        var result = ServiceKeywordDetector.detect("The app needs a distributed cache");
        assertEquals(1, result.size());
        assertEquals("redis", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("distributed cache"));
    }

    @Test
    @DisplayName("detects Redis from 'in-memory cache' keyword")
    void detectsRedisFromInMemoryCache() {
        var result = ServiceKeywordDetector.detect("Use an in-memory cache for fast lookups");
        assertEquals(1, result.size());
        assertEquals("redis", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("in-memory cache"));
    }

    @Test
    @DisplayName("detects Redis from 'redis cache' keyword")
    void detectsRedisFromRedisCache() {
        var result = ServiceKeywordDetector.detect("Add a redis cache layer");
        assertEquals(1, result.size());
        assertEquals("redis", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("redis cache"));
    }

    @Test
    @DisplayName("detects Redis from 'session store' keyword")
    void detectsRedisFromSessionStore() {
        var result = ServiceKeywordDetector.detect("Use an external session store for scaling");
        assertEquals(1, result.size());
        assertEquals("redis", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("session store"));
    }

    @Test
    @DisplayName("detects MySQL from 'mysql' keyword")
    void detectsMysql() {
        var result = ServiceKeywordDetector.detect("Connect to MySQL database");
        assertEquals(1, result.size());
        assertEquals("mysql", result.get(0).serviceType());
        assertEquals("MySQL Database", result.get(0).displayName());
    }

    @Test
    @DisplayName("detects MySQL from 'mariadb' keyword")
    void detectsMysqlFromMariadb() {
        var result = ServiceKeywordDetector.detect("Use MariaDB as the backend database");
        assertEquals(1, result.size());
        assertEquals("mysql", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("mariadb"));
    }

    @Test
    @DisplayName("detects MongoDB from 'mongodb' keyword")
    void detectsMongodb() {
        var result = ServiceKeywordDetector.detect("Store documents in MongoDB");
        assertEquals(1, result.size());
        assertEquals("mongodb", result.get(0).serviceType());
        assertEquals("MongoDB Database", result.get(0).displayName());
    }

    @Test
    @DisplayName("detects MongoDB from 'document database' keyword")
    void detectsMongodbFromDocumentDatabase() {
        var result = ServiceKeywordDetector.detect("Use a document database for flexible schemas");
        assertEquals(1, result.size());
        assertEquals("mongodb", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("document database"));
    }

    @Test
    @DisplayName("detects RabbitMQ from 'rabbitmq' keyword")
    void detectsRabbitmq() {
        var result = ServiceKeywordDetector.detect("Use RabbitMQ for async messaging");
        assertEquals(1, result.size());
        assertEquals("rabbitmq", result.get(0).serviceType());
        assertEquals("RabbitMQ Message Queue", result.get(0).displayName());
    }

    @Test
    @DisplayName("detects RabbitMQ from 'message queue' keyword")
    void detectsRabbitmqFromMessageQueue() {
        var result = ServiceKeywordDetector.detect("Events are processed via a message queue");
        assertEquals(1, result.size());
        assertEquals("rabbitmq", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("message queue"));
    }

    @Test
    @DisplayName("detects RabbitMQ from 'amqp' keyword")
    void detectsRabbitmqFromAmqp() {
        var result = ServiceKeywordDetector.detect("Connect using AMQP protocol");
        assertEquals(1, result.size());
        assertEquals("rabbitmq", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("amqp"));
    }

    @Test
    @DisplayName("detects S3/Blob from 's3' keyword")
    void detectsS3() {
        var result = ServiceKeywordDetector.detect("Upload files to S3 bucket");
        assertEquals(1, result.size());
        assertEquals("s3", result.get(0).serviceType());
        assertEquals("S3/Blob Storage", result.get(0).displayName());
    }

    @Test
    @DisplayName("detects S3/Blob from 'blob storage' keyword")
    void detectsS3FromBlobStorage() {
        var result = ServiceKeywordDetector.detect("Store images in blob storage");
        assertEquals(1, result.size());
        assertEquals("s3", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("blob storage"));
    }

    @Test
    @DisplayName("detects S3/Blob from 'object storage' keyword")
    void detectsS3FromObjectStorage() {
        var result = ServiceKeywordDetector.detect("Use object storage for large files");
        assertEquals(1, result.size());
        assertEquals("s3", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("object storage"));
    }

    @Test
    @DisplayName("detects S3/Blob from 'file storage' keyword")
    void detectsS3FromFileStorage() {
        var result = ServiceKeywordDetector.detect("The app needs file storage capabilities");
        assertEquals(1, result.size());
        assertEquals("s3", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("file storage"));
    }

    @Test
    @DisplayName("detects multiple services from combined text")
    void detectsMultipleServices() {
        var result = ServiceKeywordDetector.detect(
                "Build a Spring Boot app with PostgreSQL database and Redis cache, "
                + "and use RabbitMQ for event processing");
        assertEquals(3, result.size());

        var types = result.stream().map(ServiceKeywordDetector.DetectedService::serviceType).toList();
        assertTrue(types.contains("postgresql"));
        assertTrue(types.contains("redis"));
        assertTrue(types.contains("rabbitmq"));
    }

    @Test
    @DisplayName("reports multiple matched keywords for same service")
    void reportsMultipleMatchedKeywords() {
        var result = ServiceKeywordDetector.detect(
                "Use PostgreSQL as the relational database with JPA");
        assertEquals(1, result.size());
        assertEquals("postgresql", result.get(0).serviceType());
        // Should match "postgresql", "relational database", and "jpa"
        assertTrue(result.get(0).matchedKeywords().size() >= 3);
        assertTrue(result.get(0).matchedKeywords().contains("postgresql"));
        assertTrue(result.get(0).matchedKeywords().contains("relational database"));
        assertTrue(result.get(0).matchedKeywords().contains("jpa"));
    }

    @Test
    @DisplayName("matching is case-insensitive")
    void caseInsensitiveMatching() {
        var result = ServiceKeywordDetector.detect("POSTGRESQL and REDIS and RABBITMQ");
        assertEquals(3, result.size());
    }

    @Test
    @DisplayName("returns empty list for null input")
    void returnsEmptyForNull() {
        var result = ServiceKeywordDetector.detect(null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("returns empty list for blank input")
    void returnsEmptyForBlank() {
        var result = ServiceKeywordDetector.detect("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("returns empty list for empty input")
    void returnsEmptyForEmpty() {
        var result = ServiceKeywordDetector.detect("");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("returns empty list when no services mentioned")
    void noFalsePositivesForGenericText() {
        var result = ServiceKeywordDetector.detect(
                "Build a simple calculator app with HTML, CSS, and JavaScript. "
                + "It should support basic arithmetic operations.");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("detects services in PRD-style document")
    void detectsServicesInPrdDocument() {
        String prd = """
                # Todo Application PRD

                ## Overview
                Build a todo application with Spring Boot backend.

                ## Technical Requirements
                - Use PostgreSQL for persistent storage
                - Use Redis for session management
                - REST API endpoints for CRUD operations

                ## Data Model
                - TodoItem: id, title, description, completed, createdAt
                """;
        var result = ServiceKeywordDetector.detect(prd);
        assertEquals(2, result.size());

        var types = result.stream().map(ServiceKeywordDetector.DetectedService::serviceType).toList();
        assertTrue(types.contains("postgresql"));
        assertTrue(types.contains("redis"));
    }

    @Test
    @DisplayName("does not false-positive on generic 'cache' without qualifier")
    void noFalsePositiveOnGenericCache() {
        var result = ServiceKeywordDetector.detect(
                "Set cache-control headers and enable browser cache for static assets");
        var types = result.stream().map(ServiceKeywordDetector.DetectedService::serviceType).toList();
        assertFalse(types.contains("redis"));
    }

    @Test
    @DisplayName("does not false-positive on 'Spring @Cacheable' without Redis context")
    void noFalsePositiveOnSpringCacheable() {
        var result = ServiceKeywordDetector.detect(
                "Use Spring @Cacheable annotation with EhCache for method-level caching");
        var types = result.stream().map(ServiceKeywordDetector.DetectedService::serviceType).toList();
        assertFalse(types.contains("redis"));
    }

    @Test
    @DisplayName("does not false-positive 'mongo' as substring in other words")
    void noFalsePositiveOnMongoSubstring() {
        var result = ServiceKeywordDetector.detect(
                "The mongoose library processes data in mongolia");
        var types = result.stream().map(ServiceKeywordDetector.DetectedService::serviceType).toList();
        assertFalse(types.contains("mongodb"));
    }

    @Test
    @DisplayName("does not false-positive 's3' as substring in other tokens")
    void noFalsePositiveOnS3Substring() {
        var result = ServiceKeywordDetector.detect(
                "The AS3 class handles DNS3 configuration");
        var types = result.stream().map(ServiceKeywordDetector.DetectedService::serviceType).toList();
        assertFalse(types.contains("s3"));
    }

    @Test
    @DisplayName("still detects 's3' as a standalone word")
    void detectsS3AsStandaloneWord() {
        var result = ServiceKeywordDetector.detect("Upload user avatars to S3");
        assertEquals(1, result.size());
        assertEquals("s3", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("s3"));
    }

    @Test
    @DisplayName("still detects 'mongo' as a standalone word")
    void detectsMongoAsStandaloneWord() {
        var result = ServiceKeywordDetector.detect("Connect to Mongo for persistence");
        assertEquals(1, result.size());
        assertEquals("mongodb", result.get(0).serviceType());
        assertTrue(result.get(0).matchedKeywords().contains("mongo"));
    }
}
