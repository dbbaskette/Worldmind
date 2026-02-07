package com.worldmind.dispatch.api;

import com.worldmind.core.health.HealthCheckService;
import com.worldmind.core.health.HealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST controller for system health status.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final HealthCheckService healthCheckService;

    public HealthController(@Autowired(required = false) HealthCheckService healthCheckService) {
        this.healthCheckService = healthCheckService;
    }

    /**
     * GET /api/v1/health — System health check.
     * Returns 200 if all components UP, 503 if any DOWN.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (healthCheckService == null) {
            result.put("status", "DOWN");
            result.put("components", Map.of());
            return ResponseEntity.status(503).body(result);
        }

        var checks = healthCheckService.checkAll();
        boolean anyDown = false;

        Map<String, Object> components = new LinkedHashMap<>();
        for (var check : checks) {
            Map<String, String> componentInfo = new LinkedHashMap<>();
            componentInfo.put("status", check.status().name());
            componentInfo.put("detail", check.detail());
            components.put(check.component(), componentInfo);

            if (check.status() == HealthStatus.Status.DOWN) {
                anyDown = true;
            }
        }

        result.put("status", anyDown ? "DOWN" : "UP");
        result.put("components", components);

        return anyDown ? ResponseEntity.status(503).body(result)
                       : ResponseEntity.ok(result);
    }
}
