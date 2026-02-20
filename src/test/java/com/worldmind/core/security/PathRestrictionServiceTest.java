package com.worldmind.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PathRestrictionServiceTest {

    private PathRestrictionService service;

    @BeforeEach
    void setUp() {
        SecurityProperties props = new SecurityProperties();

        SecurityProperties.PathRule coderRule = new SecurityProperties.PathRule();
        coderRule.setWritable(List.of("src/**", "lib/**", "app/**", "pkg/**"));
        coderRule.setReadOnly(List.of("**"));

        SecurityProperties.PathRule testerRule = new SecurityProperties.PathRule();
        testerRule.setWritable(List.of("src/test/**", "test/**", "tests/**", "spec/**"));
        testerRule.setReadOnly(List.of("**"));

        SecurityProperties.PathRule reviewerRule = new SecurityProperties.PathRule();
        reviewerRule.setWritable(List.of());
        reviewerRule.setReadOnly(List.of("**"));

        SecurityProperties.PathRule researcherRule = new SecurityProperties.PathRule();
        researcherRule.setWritable(List.of());
        researcherRule.setReadOnly(List.of("**"));

        props.setPathRestrictions(Map.of(
                "CODER", coderRule,
                "TESTER", testerRule,
                "REVIEWER", reviewerRule,
                "RESEARCHER", researcherRule
        ));

        service = new PathRestrictionService(props);
    }

    @Nested
    @DisplayName("CODER agent")
    class CoderTests {

        @Test
        @DisplayName("can write to src/main/java/Foo.java")
        void canWriteToSrcMain() {
            assertTrue(service.isPathWritable("CODER", "src/main/java/Foo.java"));
        }

        @Test
        @DisplayName("can write to src/test/java/FooTest.java")
        void canWriteToSrcTest() {
            assertTrue(service.isPathWritable("CODER", "src/test/java/FooTest.java"));
        }

        @Test
        @DisplayName("can read everything")
        void canReadEverything() {
            assertTrue(service.isPathReadable("CODER", "pom.xml"));
            assertTrue(service.isPathReadable("CODER", "src/main/java/Foo.java"));
            assertTrue(service.isPathReadable("CODER", "README.md"));
        }
    }

    @Nested
    @DisplayName("TESTER agent")
    class TesterTests {

        @Test
        @DisplayName("can write to src/test/java/FooTest.java")
        void canWriteToTestDir() {
            assertTrue(service.isPathWritable("TESTER", "src/test/java/FooTest.java"));
        }

        @Test
        @DisplayName("cannot write to src/main/java/Foo.java")
        void cannotWriteToMainDir() {
            assertFalse(service.isPathWritable("TESTER", "src/main/java/Foo.java"));
        }

        @Test
        @DisplayName("can read everything")
        void canReadEverything() {
            assertTrue(service.isPathReadable("TESTER", "pom.xml"));
            assertTrue(service.isPathReadable("TESTER", "src/main/java/Foo.java"));
        }
    }

    @Nested
    @DisplayName("REVIEWER agent")
    class ReviewerTests {

        @Test
        @DisplayName("cannot write anywhere")
        void cannotWriteAnywhere() {
            assertFalse(service.isPathWritable("REVIEWER", "src/main/java/Foo.java"));
            assertFalse(service.isPathWritable("REVIEWER", "src/test/java/FooTest.java"));
            assertFalse(service.isPathWritable("REVIEWER", "pom.xml"));
        }

        @Test
        @DisplayName("can read everything")
        void canReadEverything() {
            assertTrue(service.isPathReadable("REVIEWER", "pom.xml"));
            assertTrue(service.isPathReadable("REVIEWER", "src/main/java/Foo.java"));
            assertTrue(service.isPathReadable("REVIEWER", "README.md"));
        }
    }

    @Nested
    @DisplayName("unknown agent type")
    class UnknownTypeTests {

        @Test
        @DisplayName("cannot write anywhere")
        void cannotWriteAnywhere() {
            assertFalse(service.isPathWritable("UNKNOWN", "src/main/java/Foo.java"));
        }

        @Test
        @DisplayName("cannot read anything")
        void cannotReadAnything() {
            assertFalse(service.isPathReadable("UNKNOWN", "src/main/java/Foo.java"));
        }
    }
}
