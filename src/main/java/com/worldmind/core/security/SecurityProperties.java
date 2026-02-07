package com.worldmind.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "worldmind.security")
public class SecurityProperties {

    private Jwt jwt = new Jwt();
    private Map<String, List<String>> commandAllowlists = Map.of();
    private Map<String, PathRule> pathRestrictions = Map.of();

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public Map<String, List<String>> getCommandAllowlists() {
        return commandAllowlists;
    }

    public void setCommandAllowlists(Map<String, List<String>> commandAllowlists) {
        this.commandAllowlists = commandAllowlists;
    }

    public Map<String, PathRule> getPathRestrictions() {
        return pathRestrictions;
    }

    public void setPathRestrictions(Map<String, PathRule> pathRestrictions) {
        this.pathRestrictions = pathRestrictions;
    }

    public static class Jwt {
        private String secret = "worldmind-dev-secret-change-in-production";
        private int expirationSeconds = 600;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public int getExpirationSeconds() {
            return expirationSeconds;
        }

        public void setExpirationSeconds(int expirationSeconds) {
            this.expirationSeconds = expirationSeconds;
        }
    }

    public static class PathRule {
        private List<String> writable = List.of();
        private List<String> readOnly = List.of();

        public List<String> getWritable() {
            return writable;
        }

        public void setWritable(List<String> writable) {
            this.writable = writable;
        }

        public List<String> getReadOnly() {
            return readOnly;
        }

        public void setReadOnly(List<String> readOnly) {
            this.readOnly = readOnly;
        }
    }
}
