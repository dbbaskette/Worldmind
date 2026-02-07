package com.worldmind.core.security;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CommandAllowlistService {

    private final SecurityProperties securityProperties;

    public CommandAllowlistService(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public boolean isCommandAllowed(String centurionType, String command) {
        List<String> allowlist = securityProperties.getCommandAllowlists().get(centurionType);
        if (allowlist == null || allowlist.isEmpty()) {
            return false;
        }

        for (String pattern : allowlist) {
            if (matches(pattern, command)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(String pattern, String command) {
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return command.startsWith(prefix);
        }
        return pattern.equals(command);
    }
}
