package com.worldmind.core.persistence;

import com.worldmind.core.state.WorldmindState;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service that provides higher-level query operations over the checkpoint store.
 * Bridges the gap between {@link BaseCheckpointSaver} (which only queries by thread)
 * and CLI commands that need to list all missions, inspect timelines, etc.
 */
@Service
public class CheckpointQueryService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointQueryService.class);

    private final BaseCheckpointSaver saver;

    public CheckpointQueryService(BaseCheckpointSaver saver) {
        this.saver = saver;
    }

    /**
     * Returns all known thread IDs (mission IDs) from the checkpoint store.
     * For {@link JdbcCheckpointSaver}, queries the database directly.
     * For {@link MemorySaver}, reflects into the internal map.
     */
    public List<String> listAllThreadIds() {
        if (saver instanceof JdbcCheckpointSaver jdbc) {
            return jdbc.listAllThreadIds();
        }
        if (saver instanceof MemorySaver mem) {
            try {
                var field = MemorySaver.class.getDeclaredField("_checkpointsByThread");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                var map = (Map<String, ?>) field.get(mem);
                return new ArrayList<>(map.keySet());
            } catch (ReflectiveOperationException e) {
                log.warn("Unable to list thread IDs from MemorySaver", e);
                return List.of();
            }
        }
        return List.of();
    }

    /**
     * Lists all checkpoints for a given mission (thread) ID, ordered chronologically.
     */
    public Collection<Checkpoint> listCheckpoints(String missionId) {
        var config = RunnableConfig.builder().threadId(missionId).build();
        return saver.list(config);
    }

    /**
     * Gets the latest checkpoint for a mission, returning the final state.
     */
    public Optional<Checkpoint> getLatestCheckpoint(String missionId) {
        var config = RunnableConfig.builder().threadId(missionId).build();
        return saver.get(config);
    }

    /**
     * Gets a {@link WorldmindState} from the latest checkpoint for a mission.
     */
    public Optional<WorldmindState> getLatestState(String missionId) {
        return getLatestCheckpoint(missionId)
                .map(cp -> new WorldmindState(cp.getState()));
    }
}
