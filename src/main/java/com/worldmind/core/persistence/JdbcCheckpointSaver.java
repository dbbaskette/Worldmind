package com.worldmind.core.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * JDBC-based {@link BaseCheckpointSaver} that persists LangGraph4j
 * checkpoints to a PostgreSQL table.
 * <p>
 * Each checkpoint is stored as a JSON-serialized row keyed by
 * {@code (thread_id, checkpoint_id)}. This allows graph executions
 * to be resumed or inspected across JVM restarts.
 * <p>
 * The table {@code lg4j_checkpoints} is created automatically via
 * {@link #createTables()}.
 */
public class JdbcCheckpointSaver implements BaseCheckpointSaver {

    private static final Logger log = LoggerFactory.getLogger(JdbcCheckpointSaver.class);

    private static final String TABLE_NAME = "lg4j_checkpoints";

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS %s (
                thread_id    VARCHAR(255) NOT NULL,
                checkpoint_id VARCHAR(255) NOT NULL,
                node_id      VARCHAR(255),
                next_node_id VARCHAR(255),
                state        TEXT NOT NULL,
                created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (thread_id, checkpoint_id)
            )
            """.formatted(TABLE_NAME);

    private static final String UPSERT_SQL = """
            INSERT INTO %s (thread_id, checkpoint_id, node_id, next_node_id, state)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (thread_id, checkpoint_id)
            DO UPDATE SET node_id = EXCLUDED.node_id,
                          next_node_id = EXCLUDED.next_node_id,
                          state = EXCLUDED.state,
                          created_at = CURRENT_TIMESTAMP
            """.formatted(TABLE_NAME);

    private static final String SELECT_BY_THREAD_SQL = """
            SELECT checkpoint_id, node_id, next_node_id, state
            FROM %s
            WHERE thread_id = ?
            ORDER BY created_at ASC
            """.formatted(TABLE_NAME);

    private static final String SELECT_BY_ID_SQL = """
            SELECT checkpoint_id, node_id, next_node_id, state
            FROM %s
            WHERE thread_id = ? AND checkpoint_id = ?
            """.formatted(TABLE_NAME);

    private static final String SELECT_LATEST_SQL = """
            SELECT checkpoint_id, node_id, next_node_id, state
            FROM %s
            WHERE thread_id = ?
            ORDER BY created_at DESC
            LIMIT 1
            """.formatted(TABLE_NAME);

    private static final String DELETE_BY_THREAD_SQL = """
            DELETE FROM %s WHERE thread_id = ?
            """.formatted(TABLE_NAME);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcCheckpointSaver(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "DataSource must not be null");
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Creates the checkpoint table if it does not already exist.
     * Should be called once during application startup.
     */
    public void createTables() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(CREATE_TABLE_SQL)) {
            stmt.execute();
            log.info("Checkpoint table '{}' ensured", TABLE_NAME);
        }
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        String threadId = resolveThreadId(config);
        List<Checkpoint> checkpoints = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_BY_THREAD_SQL)) {
            stmt.setString(1, threadId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    checkpoints.add(fromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list checkpoints for thread '{}'", threadId, e);
        }

        return checkpoints;
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        String threadId = resolveThreadId(config);
        Optional<String> checkpointId = config.checkPointId();

        String sql = checkpointId.isPresent() ? SELECT_BY_ID_SQL : SELECT_LATEST_SQL;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, threadId);
            if (checkpointId.isPresent()) {
                stmt.setString(2, checkpointId.get());
            }

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(fromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get checkpoint for thread '{}', id '{}'",
                    threadId, checkpointId.orElse("latest"), e);
        }

        return Optional.empty();
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        String threadId = resolveThreadId(config);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
            stmt.setString(1, threadId);
            stmt.setString(2, checkpoint.getId());
            stmt.setString(3, checkpoint.getNodeId());
            stmt.setString(4, checkpoint.getNextNodeId());
            stmt.setString(5, serializeState(checkpoint.getState()));
            stmt.executeUpdate();

            log.debug("Saved checkpoint '{}' for thread '{}'", checkpoint.getId(), threadId);
        }

        return RunnableConfig.builder(config)
                .checkPointId(checkpoint.getId())
                .build();
    }

    @Override
    public Tag release(RunnableConfig config) throws Exception {
        String threadId = resolveThreadId(config);

        // Collect checkpoints before deleting
        Collection<Checkpoint> released = list(config);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_BY_THREAD_SQL)) {
            stmt.setString(1, threadId);
            int deleted = stmt.executeUpdate();
            log.debug("Released {} checkpoints for thread '{}'", deleted, threadId);
        }

        return new Tag(threadId, released);
    }

    // ── Query helpers for CLI ────────────────────────────────────────────

    private static final String SELECT_ALL_THREADS_SQL = """
            SELECT DISTINCT thread_id FROM %s ORDER BY thread_id
            """.formatted(TABLE_NAME);

    /**
     * Returns all distinct thread IDs stored in the checkpoint table.
     * Used by the CLI history command to list all missions.
     */
    public List<String> listAllThreadIds() {
        List<String> threadIds = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_ALL_THREADS_SQL);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                threadIds.add(rs.getString("thread_id"));
            }
        } catch (SQLException e) {
            log.error("Failed to list all thread IDs", e);
        }
        return threadIds;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String resolveThreadId(RunnableConfig config) {
        return config.threadId().orElse(THREAD_ID_DEFAULT);
    }

    private String serializeState(Map<String, Object> state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize checkpoint state", e);
        }
    }

    private Map<String, Object> deserializeState(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize checkpoint state", e);
        }
    }

    private Checkpoint fromResultSet(ResultSet rs) throws SQLException {
        String id = rs.getString("checkpoint_id");
        String nodeId = rs.getString("node_id");
        String nextNodeId = rs.getString("next_node_id");
        String stateJson = rs.getString("state");

        Map<String, Object> state = deserializeState(stateJson);

        var builder = Checkpoint.builder()
                .id(id)
                .state(state);

        if (nodeId != null) {
            builder.nodeId(nodeId);
        }
        if (nextNodeId != null) {
            builder.nextNodeId(nextNodeId);
        }

        return builder.build();
    }
}
