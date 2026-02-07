package com.worldmind.core.security;

import org.springframework.stereotype.Service;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;

@Service
public class PathRestrictionService {

    private final SecurityProperties securityProperties;

    public PathRestrictionService(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public boolean isPathWritable(String centurionType, String relativePath) {
        SecurityProperties.PathRule rule = securityProperties.getPathRestrictions().get(centurionType);
        if (rule == null) {
            return false;
        }
        return matchesAny(rule.getWritable(), relativePath);
    }

    public boolean isPathReadable(String centurionType, String relativePath) {
        SecurityProperties.PathRule rule = securityProperties.getPathRestrictions().get(centurionType);
        if (rule == null) {
            return false;
        }
        List<String> readableGlobs = rule.getReadOnly();
        List<String> writableGlobs = rule.getWritable();
        return matchesAny(readableGlobs, relativePath) || matchesAny(writableGlobs, relativePath);
    }

    private boolean matchesAny(List<String> globs, String relativePath) {
        if (globs == null || globs.isEmpty()) {
            return false;
        }
        for (String glob : globs) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            if (matcher.matches(Paths.get(relativePath))) {
                return true;
            }
        }
        return false;
    }
}
