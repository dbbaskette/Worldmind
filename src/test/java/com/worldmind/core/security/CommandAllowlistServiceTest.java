package com.worldmind.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandAllowlistServiceTest {

    private CommandAllowlistService service;

    @BeforeEach
    void setUp() {
        SecurityProperties props = new SecurityProperties();
        props.setCommandAllowlists(Map.of(
                "FORGE", List.of("mvn *", "gradle *", "npm *", "python *", "java *", "go *"),
                "GAUNTLET", List.of("mvn test*", "gradle test*", "npm test*", "pytest*", "go test*"),
                "VIGIL", List.of("find *", "grep *", "cat *", "head *", "tail *", "wc *", "ls *"),
                "PULSE", List.of("find *", "grep *", "cat *", "ls *")
        ));
        service = new CommandAllowlistService(props);
    }

    @Nested
    @DisplayName("FORGE centurion")
    class ForgeTests {

        @Test
        @DisplayName("allows 'mvn compile'")
        void allowsMvnCompile() {
            assertTrue(service.isCommandAllowed("FORGE", "mvn compile"));
        }

        @Test
        @DisplayName("allows 'gradle build'")
        void allowsGradleBuild() {
            assertTrue(service.isCommandAllowed("FORGE", "gradle build"));
        }

        @Test
        @DisplayName("allows 'npm install'")
        void allowsNpmInstall() {
            assertTrue(service.isCommandAllowed("FORGE", "npm install"));
        }

        @Test
        @DisplayName("denies 'rm -rf /'")
        void deniesRmRf() {
            assertFalse(service.isCommandAllowed("FORGE", "rm -rf /"));
        }
    }

    @Nested
    @DisplayName("GAUNTLET centurion")
    class GauntletTests {

        @Test
        @DisplayName("allows 'mvn test'")
        void allowsMvnTest() {
            assertTrue(service.isCommandAllowed("GAUNTLET", "mvn test"));
        }

        @Test
        @DisplayName("allows 'mvn test -Dtest=FooTest'")
        void allowsMvnTestWithArgs() {
            assertTrue(service.isCommandAllowed("GAUNTLET", "mvn test -Dtest=FooTest"));
        }

        @Test
        @DisplayName("denies 'mvn deploy'")
        void deniesMvnDeploy() {
            assertFalse(service.isCommandAllowed("GAUNTLET", "mvn deploy"));
        }

        @Test
        @DisplayName("denies 'mvn compile'")
        void deniesMvnCompile() {
            assertFalse(service.isCommandAllowed("GAUNTLET", "mvn compile"));
        }
    }

    @Nested
    @DisplayName("VIGIL centurion")
    class VigilTests {

        @Test
        @DisplayName("allows 'grep foo'")
        void allowsGrepFoo() {
            assertTrue(service.isCommandAllowed("VIGIL", "grep foo"));
        }

        @Test
        @DisplayName("allows 'cat README.md'")
        void allowsCat() {
            assertTrue(service.isCommandAllowed("VIGIL", "cat README.md"));
        }

        @Test
        @DisplayName("denies 'rm -rf /'")
        void deniesRmRf() {
            assertFalse(service.isCommandAllowed("VIGIL", "rm -rf /"));
        }

        @Test
        @DisplayName("denies 'mvn compile'")
        void deniesMvnCompile() {
            assertFalse(service.isCommandAllowed("VIGIL", "mvn compile"));
        }
    }

    @Nested
    @DisplayName("unknown centurion type")
    class UnknownTypeTests {

        @Test
        @DisplayName("denies all commands for unknown centurion type")
        void deniesAllForUnknown() {
            assertFalse(service.isCommandAllowed("UNKNOWN", "mvn compile"));
            assertFalse(service.isCommandAllowed("UNKNOWN", "ls"));
            assertFalse(service.isCommandAllowed("UNKNOWN", "grep foo"));
        }
    }
}
