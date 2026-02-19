package com.worldmind.dispatch.api;

import com.worldmind.core.security.AuthProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String SESSION_USER_KEY = "worldmind_user";

    private final AuthProperties authProperties;

    public AuthController(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody LoginRequest request,
            HttpSession session) {

        if (!authProperties.isEnabled()) {
            session.setAttribute(SESSION_USER_KEY, "anonymous");
            return ResponseEntity.ok(Map.of(
                    "authenticated", true,
                    "username", "anonymous",
                    "message", "Authentication disabled"
            ));
        }

        if (authProperties.getUsername().equals(request.username()) &&
                authProperties.getPassword().equals(request.password())) {
            session.setAttribute(SESSION_USER_KEY, request.username());
            return ResponseEntity.ok(Map.of(
                    "authenticated", true,
                    "username", request.username()
            ));
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "authenticated", false,
                        "error", "Invalid username or password"
                ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpSession session) {
        String user = (String) session.getAttribute(SESSION_USER_KEY);

        if (!authProperties.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "authenticated", true,
                    "username", "anonymous",
                    "authEnabled", false
            ));
        }

        if (user != null) {
            return ResponseEntity.ok(Map.of(
                    "authenticated", true,
                    "username", user,
                    "authEnabled", true
            ));
        }

        return ResponseEntity.ok(Map.of(
                "authenticated", false,
                "authEnabled", true
        ));
    }

    public record LoginRequest(String username, String password) {}
}
