package com.example.nvr.persistence;

public class DatabaseConfig {

    private final String type;
    private final String host;
    private final int port;
    private final String name;
    private final String username;
    private final String password;

    public DatabaseConfig(String type, String host, int port, String name, String username, String password) {
        this.type = type == null ? "postgres" : type.toLowerCase();
        this.host = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        if (port <= 0 || port > 65535) {
            port = 5432;
        }
        this.port = port;
        this.name = (name == null || name.isBlank()) ? "nvr_demo" : name.trim();
        this.username = (username == null || username.isBlank()) ? "nvr_app" : username.trim();
        this.password = password == null ? "" : password;
    }

    public String type() {
        return type;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String name() {
        return name;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }
}
