package com.example.nvr.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;

@Component
public class DynamicDataSourceManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceManager.class);

    private final Object lock = new Object();
    private volatile HikariDataSource dataSource;
    private volatile DatabaseConfig currentConfig;

    public DynamicDataSourceManager(Environment environment) {
        DatabaseConfig config = new DatabaseConfig(
                environment.getProperty("DB_TYPE", "postgres"),
                environment.getProperty("DB_HOST", "127.0.0.1"),
                parseInt(environment.getProperty("DB_PORT"), 5432),
                environment.getProperty("DB_NAME", "nvr_demo"),
                environment.getProperty("DB_USER", "nvr_app"),
                environment.getProperty("DB_PASS", "nvrdemo")
        );
        try {
            reset(config);
        } catch (SQLException e) {
            log.error("Failed to initialise database connection: {}", e.getMessage());
        }
    }

    public DatabaseConfig getCurrentConfig() {
        return currentConfig;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public synchronized DatabaseConfig update(DatabaseConfig config) throws SQLException {
        reset(config);
        return currentConfig;
    }

    private void reset(DatabaseConfig config) throws SQLException {
        HikariDataSource newDataSource = createDataSource(config);
        try {
            initSchema(newDataSource);
        } catch (SQLException ex) {
            log.warn("Failed to initialise database schema: {}", ex.getMessage());
        }
        synchronized (lock) {
            HikariDataSource old = this.dataSource;
            this.dataSource = newDataSource;
            this.currentConfig = config;
            if (old != null) {
                old.close();
            }
        }
        log.info("Database connection ready: {}:{} / {}", config.host(), config.port(), config.name());
    }

    private HikariDataSource createDataSource(DatabaseConfig config) throws SQLException {
        String type = config.type().toLowerCase(Locale.ROOT);
        if (!Objects.equals(type, "postgres")) {
            throw new SQLException("仅支持 PostgreSQL 数据库");
        }
        String jdbcUrl = String.format(Locale.ROOT, "jdbc:postgresql://%s:%d/%s", config.host(), config.port(), config.name());
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        hikariConfig.setMinimumIdle(0);
        hikariConfig.setMaximumPoolSize(5);
        hikariConfig.setPoolName("nvr-event-pool");
        hikariConfig.setInitializationFailTimeout(-1);
        return new HikariDataSource(hikariConfig);
    }

    private void initSchema(DataSource ds) throws SQLException {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS alert_events (" +
                    "id SERIAL PRIMARY KEY, " +
                    "event_id VARCHAR(128) NOT NULL, " +
                    "event_type VARCHAR(128), " +
                    "cam_channel VARCHAR(32), " +
                    "level VARCHAR(32), " +
                    "event_time VARCHAR(64), " +
                    "status VARCHAR(32) DEFAULT '未处理'" +
                    ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_alert_events_event_time ON alert_events(event_time DESC)");
            st.execute("ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS cam_channel VARCHAR(32)");
            st.execute("ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS status VARCHAR(32) DEFAULT '未处理'");
            executeSilently(st, "UPDATE alert_events SET cam_channel = COALESCE(cam_channel, channel_id::text, port::text) WHERE cam_channel IS NULL");
            st.execute("UPDATE alert_events SET status = COALESCE(NULLIF(status, ''), '未处理')");
            st.execute("ALTER TABLE alert_events DROP COLUMN IF EXISTS raw_payload");
            st.execute("ALTER TABLE alert_events DROP COLUMN IF EXISTS channel_id");
            st.execute("ALTER TABLE alert_events DROP COLUMN IF EXISTS port");
            st.execute("ALTER TABLE alert_events DROP COLUMN IF EXISTS created_at");

            st.execute("CREATE TABLE IF NOT EXISTS camera_alarms (" +
                    "id SERIAL PRIMARY KEY, " +
                    "event_id VARCHAR(128) NOT NULL, " +
                    "event_type VARCHAR(128), " +
                    "cam_channel VARCHAR(32), " +
                    "level VARCHAR(32), " +
                    "event_time VARCHAR(64), " +
                    "created_at TIMESTAMPTZ DEFAULT NOW()" +
                    ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_camera_alarms_created_at ON camera_alarms(created_at DESC)");
            st.execute("ALTER TABLE camera_alarms ADD COLUMN IF NOT EXISTS cam_channel VARCHAR(32)");
            executeSilently(st, "UPDATE camera_alarms SET cam_channel = COALESCE(cam_channel, channel_id::text, port::text) WHERE cam_channel IS NULL");
            st.execute("ALTER TABLE camera_alarms DROP COLUMN IF EXISTS channel_id");
            st.execute("ALTER TABLE camera_alarms DROP COLUMN IF EXISTS port");
            st.execute("ALTER TABLE camera_alarms DROP COLUMN IF EXISTS raw_payload");

            st.execute("CREATE TABLE IF NOT EXISTS radar_targets (" +
                    "id SERIAL PRIMARY KEY, " +
                    "radar_host VARCHAR(128), " +
                    "control_port INT, " +
                    "data_port INT, " +
                    "actual_data_port INT, " +
                    "transport_tcp BOOLEAN, " +
                    "status_code INT, " +
                    "payload_length INT, " +
                    "target_count INT, " +
                    "target_id INT, " +
                    "longitudinal_distance DOUBLE PRECISION, " +
                    "lateral_distance DOUBLE PRECISION, " +
                    "speed DOUBLE PRECISION, " +
                    "range_value DOUBLE PRECISION, " +
                    "angle DOUBLE PRECISION, " +
                    "amplitude INT, " +
                    "snr INT, " +
                    "rcs DOUBLE PRECISION, " +
                    "element_count INT, " +
                    "target_length INT, " +
                    "detection_frames INT, " +
                    "track_state INT, " +
                    "reserve1 INT, " +
                    "reserve2 INT, " +
                    "captured_at TIMESTAMPTZ DEFAULT NOW()" +
                    ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_radar_targets_captured_at ON radar_targets(captured_at DESC)");
            executeSilently(st, "ALTER TABLE imsi_sync_config ADD COLUMN IF NOT EXISTS device_filter VARCHAR(255)");
        }
    }

    private void executeSilently(Statement st, String sql) {
        try {
            st.execute(sql);
        } catch (SQLException ex) {
            log.debug("Skipping optional schema statement '{}': {}", sql, ex.getMessage());
        }
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lock) {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }
}
