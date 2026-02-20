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
                "CODER", List.of("mvn *", "gradle *", "npm *", "python *", "java *", "go *"),
                "TESTER", List.of("mvn test*", "gradle test*", "npm test*", "pytest*", "go test*"),
                "REVIEWER", List.of("find *", "grep *", "cat *", "head *", "tail *", "wc *", "ls *"),
                "RESEARCHER", List.of("find *", "grep *", "cat *", "ls *")
        ));
        service = new CommandAllowlistService(props);
    }

    @Nested
    @DisplayName("CODER agent")
    class CoderTests {

        @Test
        @DisplayName("allows 'mvn compile'")
        void allowsMvnCompile() {
            assertTrue(service.isCommandAllowed("CODER", "mvn compile"));
        }

        @Test
        @DisplayName("allows 'gradle build'")
        void allowsGradleBuild() {
            assertTrue(service.isCommandAllowed("CODER", "gradle build"));
        }

        @Test
        @DisplayName("allows 'npm install'")
        void allowsNpmInstall() {
            assertTrue(service.isCommandAllowed("CODER", "npm install"));
        }

        @Test
        @DisplayName("denies 'rm -rf /'")
        void deniesRmRf() {
            assertFalse(service.isCommandAllowed("CODER", "rm -rf /"));
        }
    }

    @Nested
    @DisplayName("TESTER agent")
    class TesterTests {

        @Test
        @DisplayName("allows 'mvn test'")
        void allowsMvnTest() {
            assertTrue(service.isCommandAllowed("TESTER", "mvn test"));
        }

        @Test
        @DisplayName("allows 'mvn test -Dtest=FooTest'")
        void allowsMvnTestWithArgs() {
            assertTrue(service.isCommandAllowed("TESTER", "mvn test -Dtest=FooTest"));
        }

        @Test
        @DisplayName("denies 'mvn deploy'")
        void deniesMvnDeploy() {
            assertFalse(service.isCommandAllowed("TESTER", "mvn deploy"));
        }

        @Test
        @DisplayName("denies 'mvn compile'")
        void deniesMvnCompile() {
            assertFalse(service.isCommandAllowed("TESTER", "mvn compile"));
        }
    }

    @Nested
    @DisplayName("REVIEWER agent")
    class ReviewerTests {

        @Test
        @DisplayName("allows 'grep foo'")
        void allowsGrepFoo() {
            assertTrue(service.isCommandAllowed("REVIEWER", "grep foo"));
        }

        @Test
        @DisplayName("allows 'cat README.md'")
        void allowsCat() {
            assertTrue(service.isCommandAllowed("REVIEWER", "cat README.md"));
        }

        @Test
        @DisplayName("denies 'rm -rf /'")
        void deniesRmRf() {
            assertFalse(service.isCommandAllowed("REVIEWER", "rm -rf /"));
        }

        @Test
        @DisplayName("denies 'mvn compile'")
        void deniesMvnCompile() {
            assertFalse(service.isCommandAllowed("REVIEWER", "mvn compile"));
        }
    }

    @Nested
    @DisplayName("unknown agent type")
    class UnknownTypeTests {

        @Test
        @DisplayName("denies all commands for unknown agent type")
        void deniesAllForUnknown() {
            assertFalse(service.isCommandAllowed("UNKNOWN", "mvn compile"));
            assertFalse(service.isCommandAllowed("UNKNOWN", "ls"));
            assertFalse(service.isCommandAllowed("UNKNOWN", "grep foo"));
        }
    }
}
