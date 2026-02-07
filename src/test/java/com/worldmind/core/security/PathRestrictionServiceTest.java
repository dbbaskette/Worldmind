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

        SecurityProperties.PathRule forgeRule = new SecurityProperties.PathRule();
        forgeRule.setWritable(List.of("src/**", "lib/**", "app/**", "pkg/**"));
        forgeRule.setReadOnly(List.of("**"));

        SecurityProperties.PathRule gauntletRule = new SecurityProperties.PathRule();
        gauntletRule.setWritable(List.of("src/test/**", "test/**", "tests/**", "spec/**"));
        gauntletRule.setReadOnly(List.of("**"));

        SecurityProperties.PathRule vigilRule = new SecurityProperties.PathRule();
        vigilRule.setWritable(List.of());
        vigilRule.setReadOnly(List.of("**"));

        SecurityProperties.PathRule pulseRule = new SecurityProperties.PathRule();
        pulseRule.setWritable(List.of());
        pulseRule.setReadOnly(List.of("**"));

        props.setPathRestrictions(Map.of(
                "FORGE", forgeRule,
                "GAUNTLET", gauntletRule,
                "VIGIL", vigilRule,
                "PULSE", pulseRule
        ));

        service = new PathRestrictionService(props);
    }

    @Nested
    @DisplayName("FORGE centurion")
    class ForgeTests {

        @Test
        @DisplayName("can write to src/main/java/Foo.java")
        void canWriteToSrcMain() {
            assertTrue(service.isPathWritable("FORGE", "src/main/java/Foo.java"));
        }

        @Test
        @DisplayName("can write to src/test/java/FooTest.java")
        void canWriteToSrcTest() {
            assertTrue(service.isPathWritable("FORGE", "src/test/java/FooTest.java"));
        }

        @Test
        @DisplayName("can read everything")
        void canReadEverything() {
            assertTrue(service.isPathReadable("FORGE", "pom.xml"));
            assertTrue(service.isPathReadable("FORGE", "src/main/java/Foo.java"));
            assertTrue(service.isPathReadable("FORGE", "README.md"));
        }
    }

    @Nested
    @DisplayName("GAUNTLET centurion")
    class GauntletTests {

        @Test
        @DisplayName("can write to src/test/java/FooTest.java")
        void canWriteToTestDir() {
            assertTrue(service.isPathWritable("GAUNTLET", "src/test/java/FooTest.java"));
        }

        @Test
        @DisplayName("cannot write to src/main/java/Foo.java")
        void cannotWriteToMainDir() {
            assertFalse(service.isPathWritable("GAUNTLET", "src/main/java/Foo.java"));
        }

        @Test
        @DisplayName("can read everything")
        void canReadEverything() {
            assertTrue(service.isPathReadable("GAUNTLET", "pom.xml"));
            assertTrue(service.isPathReadable("GAUNTLET", "src/main/java/Foo.java"));
        }
    }

    @Nested
    @DisplayName("VIGIL centurion")
    class VigilTests {

        @Test
        @DisplayName("cannot write anywhere")
        void cannotWriteAnywhere() {
            assertFalse(service.isPathWritable("VIGIL", "src/main/java/Foo.java"));
            assertFalse(service.isPathWritable("VIGIL", "src/test/java/FooTest.java"));
            assertFalse(service.isPathWritable("VIGIL", "pom.xml"));
        }

        @Test
        @DisplayName("can read everything")
        void canReadEverything() {
            assertTrue(service.isPathReadable("VIGIL", "pom.xml"));
            assertTrue(service.isPathReadable("VIGIL", "src/main/java/Foo.java"));
            assertTrue(service.isPathReadable("VIGIL", "README.md"));
        }
    }

    @Nested
    @DisplayName("unknown centurion type")
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
