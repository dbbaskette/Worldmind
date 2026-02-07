package com.worldmind.dispatch.api;

import com.worldmind.core.graph.WorldmindGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final WorldmindGraph worldmindGraph;

    public HealthController(@Autowired(required = false) WorldmindGraph worldmindGraph) {
        this.worldmindGraph = worldmindGraph;
    }

    /**
     * GET /api/v1/health — System health check.
     */
    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", worldmindGraph != null ? "UP" : "DEGRADED");

        Map<String, String> components = new LinkedHashMap<>();
        components.put("graph", worldmindGraph != null ? "UP" : "DOWN");
        status.put("components", components);

        return status;
    }
}
