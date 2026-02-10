package com.worldmind.core.persistence;

import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Optional;

/**
 * Spring {@link Configuration} that provides a LangGraph4j
 * {@link BaseCheckpointSaver} bean for graph state persistence.
 * <p>
 * When a {@link DataSource} is available (i.e. PostgreSQL is configured),
 * a {@link JdbcCheckpointSaver} is created that persists checkpoints to
 * the database. Otherwise, an in-memory {@link MemorySaver} is used as
 * a fallback -- suitable for development and testing but not durable
 * across restarts.
 */
@Configuration
public class CheckpointerConfig {

    private static final Logger log = LoggerFactory.getLogger(CheckpointerConfig.class);

    @Bean
    public BaseCheckpointSaver checkpointSaver(Optional<DataSource> dataSource) {
        if (dataSource.isPresent()) {
            try {
                var saver = new JdbcCheckpointSaver(dataSource.get());
                saver.createTables();
                log.info("Configuring JDBC checkpoint saver (PostgreSQL)");
                return saver;
            } catch (Exception e) {
                log.warn("JDBC checkpoint saver failed, falling back to in-memory: {}", e.getMessage());
            }
        }
        log.info("Using in-memory checkpoint saver (state will not persist across restarts)");
        return new MemorySaver();
    }
}
