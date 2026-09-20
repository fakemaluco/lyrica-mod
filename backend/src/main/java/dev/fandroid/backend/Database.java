package dev.fandroid.backend;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

public final class Database implements AutoCloseable {
    private final HikariDataSource dataSource;

    public Database(BackendConfig config) {
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(config.databaseUrl());
        pool.setUsername(config.databaseUser());
        pool.setPassword(config.databasePassword());
        pool.setMaximumPoolSize(12);
        pool.setMinimumIdle(2);
        pool.setConnectionTimeout(5_000);
        pool.setPoolName("fandroid-backend");
        dataSource = new HikariDataSource(pool);
    }

    public void migrate() {
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}

