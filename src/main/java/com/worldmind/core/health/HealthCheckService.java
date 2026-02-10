package com.worldmind.core.health;

import com.worldmind.core.graph.WorldmindGraph;
import com.worldmind.starblaster.DockerStarblasterProvider;
import com.worldmind.starblaster.StarblasterProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class HealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckService.class);

    private final WorldmindGraph worldmindGraph;
    private final StarblasterProvider starblasterProvider;
    private final DataSource dataSource;

    public HealthCheckService(
            @Autowired(required = false) WorldmindGraph worldmindGraph,
            @Autowired(required = false) StarblasterProvider starblasterProvider,
            @Autowired(required = false) DataSource dataSource) {
        this.worldmindGraph = worldmindGraph;
        this.starblasterProvider = starblasterProvider;
        this.dataSource = dataSource;
    }

    public List<HealthStatus> checkAll() {
        var results = new ArrayList<HealthStatus>();
        results.add(checkGraph());
        results.add(checkDatabase());
        results.add(checkDocker());
        return results;
    }

    private HealthStatus checkGraph() {
        if (worldmindGraph != null) {
            return new HealthStatus("graph", HealthStatus.Status.UP,
                    "Graph compiled and available", Map.of());
        }
        return new HealthStatus("graph", HealthStatus.Status.DOWN,
                "Graph not available", Map.of());
    }

    private HealthStatus checkDatabase() {
        if (dataSource == null) {
            return new HealthStatus("database", HealthStatus.Status.DOWN,
                    "No DataSource configured", Map.of());
        }
        try (var conn = dataSource.getConnection()) {
            if (conn.isValid(5)) {
                return new HealthStatus("database", HealthStatus.Status.UP,
                        "Database connection valid", Map.of());
            }
            return new HealthStatus("database", HealthStatus.Status.DOWN,
                    "Database connection invalid", Map.of());
        } catch (Exception e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return new HealthStatus("database", HealthStatus.Status.DOWN,
                    "Database error: " + e.getMessage(), Map.of());
        }
    }

    private HealthStatus checkDocker() {
        if (starblasterProvider == null) {
            return new HealthStatus("docker", HealthStatus.Status.DOWN,
                    "No StarblasterProvider configured", Map.of());
        }
        String detail = starblasterProvider instanceof DockerStarblasterProvider
                ? "Docker provider available"
                : "StarblasterProvider available (" + starblasterProvider.getClass().getSimpleName() + ")";
        return new HealthStatus("docker", HealthStatus.Status.UP, detail, Map.of());
    }
}
