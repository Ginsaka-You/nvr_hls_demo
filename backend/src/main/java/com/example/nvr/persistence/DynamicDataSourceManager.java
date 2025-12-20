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
    private static final int INIT_MAX_RETRIES = 5;
    private static final long INIT_RETRY_DELAY_MS = 2000L;

    private final Object lock = new Object();
    private volatile HikariDataSource dataSource;
    private volatile DatabaseConfig currentConfig;

    public DynamicDataSourceManager(Environment environment) {
        this.currentConfig = new DatabaseConfig(
                environment.getProperty("DB_TYPE", "postgres"),
                environment.getProperty("DB_HOST", "127.0.0.1"),
                parseInt(environment.getProperty("DB_PORT"), 5432),
                environment.getProperty("DB_NAME", "nvr_demo"),
                environment.getProperty("DB_USER", "nvr_app"),
                environment.getProperty("DB_PASS", "nvrdemo")
        );
    }

    public DatabaseConfig getCurrentConfig() {
        return currentConfig;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public synchronized DatabaseConfig update(DatabaseConfig config) throws SQLException {
        if (config == null) {
            throw new SQLException("数据库配置不能为空");
        }
        if (dataSource != null && sameConfig(config, currentConfig)) {
            log.info("Database configuration unchanged, skipping reinitialisation");
            return currentConfig;
        }
        reset(config);
        return currentConfig;
    }

    private void reset(DatabaseConfig config) throws SQLException {
        HikariDataSource newDataSource = createDataSource(config);
        try {
            initSchemaWithRetry(newDataSource);
        } catch (SQLException ex) {
            closeQuietly(newDataSource);
            log.warn("Failed to initialise database schema after retries: {}", ex.getMessage());
            throw ex;
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
        hikariConfig.setConnectionTimeout(5000);
        hikariConfig.setPoolName("nvr-event-pool");
        hikariConfig.setInitializationFailTimeout(-1);
        return new HikariDataSource(hikariConfig);
    }

    private void initSchemaWithRetry(DataSource ds) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= INIT_MAX_RETRIES; attempt++) {
            try {
                initSchema(ds);
                if (attempt > 1) {
                    log.info("Database became ready after {} attempt(s)", attempt);
                }
                return;
            } catch (SQLException ex) {
                last = ex;
                if (attempt == INIT_MAX_RETRIES) {
                    throw ex;
                }
                log.info("Database not ready yet (attempt {}/{}): {}. Retrying in {} ms",
                        attempt, INIT_MAX_RETRIES, ex.getMessage(), INIT_RETRY_DELAY_MS);
                try {
                    Thread.sleep(INIT_RETRY_DELAY_MS);
                } catch (InterruptedException interruptedEx) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        if (last != null) {
            throw last;
        }
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
                    "status VARCHAR(32) DEFAULT '未处理', " +
                    "snapshot_path VARCHAR(512), " +
                    "created_at TIMESTAMPTZ DEFAULT NOW()" +
                    ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_alert_events_event_time ON alert_events(event_time DESC)");
            st.execute("ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS cam_channel VARCHAR(32)");
            st.execute("ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS status VARCHAR(32) DEFAULT '未处理'");
            executeSilently(st, "UPDATE alert_events SET cam_channel = COALESCE(cam_channel, channel_id::text, port::text) WHERE cam_channel IS NULL");
            st.execute("UPDATE alert_events SET status = COALESCE(NULLIF(status, ''), '未处理')");
            st.execute("ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS snapshot_path VARCHAR(512)");
            st.execute("ALTER TABLE alert_events DROP COLUMN IF EXISTS raw_payload");
            st.execute("ALTER TABLE alert_events DROP COLUMN IF EXISTS channel_id");
            st.execute("ALTER TABLE alert_events DROP COLUMN IF EXISTS port");
            st.execute("ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ DEFAULT NOW()");
            st.execute("UPDATE alert_events SET created_at = COALESCE(created_at, NOW())");
            st.execute("CREATE INDEX IF NOT EXISTS idx_alert_events_created_at ON alert_events(created_at DESC)");

            st.execute("CREATE TABLE IF NOT EXISTS camera_alarms (" +
                    "id SERIAL PRIMARY KEY, " +
                    "event_id VARCHAR(128) NOT NULL, " +
                    "event_type VARCHAR(128), " +
                    "cam_channel VARCHAR(32), " +
                    "level VARCHAR(32), " +
                    "event_time VARCHAR(64), " +
                    "created_at TIMESTAMPTZ DEFAULT NOW(), " +
                    "snapshot_path VARCHAR(512)" +
                    ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_camera_alarms_created_at ON camera_alarms(created_at DESC)");
            st.execute("ALTER TABLE camera_alarms ADD COLUMN IF NOT EXISTS cam_channel VARCHAR(32)");
            executeSilently(st, "UPDATE camera_alarms SET cam_channel = COALESCE(cam_channel, channel_id::text, port::text) WHERE cam_channel IS NULL");
            st.execute("ALTER TABLE camera_alarms DROP COLUMN IF EXISTS channel_id");
            st.execute("ALTER TABLE camera_alarms DROP COLUMN IF EXISTS port");
            st.execute("ALTER TABLE camera_alarms DROP COLUMN IF EXISTS raw_payload");
            st.execute("ALTER TABLE camera_alarms ADD COLUMN IF NOT EXISTS snapshot_path VARCHAR(512)");

            st.execute("CREATE TABLE IF NOT EXISTS imsi_records (" +
                    "id SERIAL PRIMARY KEY, " +
                    "device_id VARCHAR(64), " +
                    "imsi VARCHAR(32), " +
                    "operator_code VARCHAR(16), " +
                    "area VARCHAR(128), " +
                    "rpt_date VARCHAR(16), " +
                    "rpt_time VARCHAR(16), " +
                    "source_file VARCHAR(255), " +
                    "line_number INT, " +
                    "host VARCHAR(128), " +
                    "port INT, " +
                    "directory VARCHAR(255), " +
                    "message VARCHAR(255), " +
                    "elapsed_ms BIGINT, " +
                    "fetched_at TIMESTAMPTZ DEFAULT NOW()" +
                    ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_imsi_records_fetched_at ON imsi_records(fetched_at DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_imsi_records_imsi ON imsi_records(imsi)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_imsi_records_source_file ON imsi_records(source_file)");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS device_id VARCHAR(64)");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS imsi VARCHAR(32)");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS operator_code VARCHAR(16)");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS area VARCHAR(128)");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS rpt_date VARCHAR(16)");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS rpt_time VARCHAR(16)");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS source_file VARCHAR(255)");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS line_number INT");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS host VARCHAR(128)");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS port INT");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS directory VARCHAR(255)");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS message VARCHAR(255)");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS elapsed_ms BIGINT");
            st.execute("ALTER TABLE imsi_records ADD COLUMN IF NOT EXISTS fetched_at TIMESTAMPTZ");
            st.execute("UPDATE imsi_records SET fetched_at = COALESCE(fetched_at, NOW())");
            st.execute("ALTER TABLE imsi_records ALTER COLUMN fetched_at SET DEFAULT NOW()");

            st.execute("CREATE TABLE IF NOT EXISTS risk_assessments (" +
                    "id SERIAL PRIMARY KEY, " +
                    "classification VARCHAR(32) NOT NULL, " +
                    "score INT, " +
                    "summary VARCHAR(255), " +
                    "details_json TEXT, " +
                    "window_start TIMESTAMPTZ, " +
                    "window_end TIMESTAMPTZ, " +
                    "updated_at TIMESTAMPTZ DEFAULT NOW(), " +
                    "remote_alarm_gate_triggered BOOLEAN DEFAULT FALSE, " +
                    "sound_light_triggered BOOLEAN DEFAULT FALSE" +
                    ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_risk_assessments_updated_at ON risk_assessments(updated_at DESC)");
            st.execute("ALTER TABLE risk_assessments ADD COLUMN IF NOT EXISTS classification VARCHAR(32)");
            st.execute("ALTER TABLE risk_assessments ADD COLUMN IF NOT EXISTS score INT");
            st.execute("ALTER TABLE risk_assessments ADD COLUMN IF NOT EXISTS summary VARCHAR(255)");
            st.execute("ALTER TABLE risk_assessments ADD COLUMN IF NOT EXISTS details_json TEXT");
            // prefer TEXT over PostgreSQL large object to avoid auto-commit issues
            executeSilently(st, "ALTER TABLE risk_assessments ALTER COLUMN details_json TYPE TEXT USING convert_from(lo_get(details_json), 'UTF8')");
            executeSilently(st, "ALTER TABLE risk_assessments ALTER COLUMN details_json TYPE TEXT USING details_json::text");
            executeSilently(st, "UPDATE risk_assessments SET details_json = convert_from(lo_get(details_json::oid), 'UTF8') WHERE details_json ~ '^[0-9]+$'");
            st.execute("ALTER TABLE risk_assessments ADD COLUMN IF NOT EXISTS window_start TIMESTAMPTZ");
            st.execute("ALTER TABLE risk_assessments ADD COLUMN IF NOT EXISTS window_end TIMESTAMPTZ");
            st.execute("ALTER TABLE risk_assessments ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ");
            st.execute("ALTER TABLE risk_assessments ADD COLUMN IF NOT EXISTS remote_alarm_gate_triggered BOOLEAN");
            st.execute("ALTER TABLE risk_assessments ADD COLUMN IF NOT EXISTS sound_light_triggered BOOLEAN");
            st.execute("UPDATE risk_assessments SET classification = COALESCE(NULLIF(classification, ''), 'P4')");
            st.execute("UPDATE risk_assessments SET remote_alarm_gate_triggered = COALESCE(remote_alarm_gate_triggered, FALSE)");
            st.execute("UPDATE risk_assessments SET sound_light_triggered = COALESCE(sound_light_triggered, FALSE)");
            st.execute("ALTER TABLE risk_assessments ALTER COLUMN classification SET NOT NULL");
            st.execute("ALTER TABLE risk_assessments ALTER COLUMN updated_at SET DEFAULT NOW()");
            st.execute("ALTER TABLE risk_assessments ALTER COLUMN remote_alarm_gate_triggered SET DEFAULT FALSE");
            st.execute("ALTER TABLE risk_assessments ALTER COLUMN sound_light_triggered SET DEFAULT FALSE");
            st.execute("ALTER TABLE risk_assessments DROP COLUMN IF EXISTS subject_key");
            st.execute("ALTER TABLE risk_assessments DROP COLUMN IF EXISTS subject_type");
            st.execute("ALTER TABLE risk_assessments DROP COLUMN IF EXISTS subject_id");
            st.execute("ALTER TABLE risk_assessments DROP COLUMN IF EXISTS subject_label");

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
                    "captured_at TIMESTAMPTZ DEFAULT NOW(), " +
                    "snapshot_path VARCHAR(512)" +
                    ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_radar_targets_captured_at ON radar_targets(captured_at DESC)");
            st.execute("ALTER TABLE radar_targets ADD COLUMN IF NOT EXISTS snapshot_path VARCHAR(512)");
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

    private boolean sameConfig(DatabaseConfig a, DatabaseConfig b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.type(), b.type())
                && Objects.equals(a.host(), b.host())
                && a.port() == b.port()
                && Objects.equals(a.name(), b.name())
                && Objects.equals(a.username(), b.username())
                && Objects.equals(a.password(), b.password());
    }

    private void closeQuietly(HikariDataSource ds) {
        if (ds != null) {
            try {
                ds.close();
            } catch (Exception ignored) {
                // already closed
            }
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
