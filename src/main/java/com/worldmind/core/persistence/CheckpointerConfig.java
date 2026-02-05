package com.worldmind.core.persistence;

import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

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

    /**
     * JDBC-backed checkpoint saver, activated when a DataSource bean exists.
     * Creates the required database table on startup.
     */
    @Bean
    @Primary
    @ConditionalOnBean(DataSource.class)
    public BaseCheckpointSaver jdbcCheckpointSaver(DataSource dataSource) throws Exception {
        log.info("Configuring JDBC checkpoint saver (PostgreSQL)");
        var saver = new JdbcCheckpointSaver(dataSource);
        saver.createTables();
        return saver;
    }

    /**
     * In-memory fallback checkpoint saver, used when no DataSource is configured.
     * State is lost on application restart.
     */
    @Bean
    @ConditionalOnMissingBean(BaseCheckpointSaver.class)
    public BaseCheckpointSaver memoryCheckpointSaver() {
        log.info("No DataSource available; using in-memory checkpoint saver (state will not persist across restarts)");
        return new MemorySaver();
    }
}
