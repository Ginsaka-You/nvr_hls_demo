package com.example.nvr;

import com.example.nvr.persistence.DatabaseConfig;
import com.example.nvr.persistence.DynamicDataSourceManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/database/config")
public class DatabaseConfigController {

    private final DynamicDataSourceManager dataSourceManager;

    public DatabaseConfigController(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    @GetMapping
    public Map<String, Object> getConfig() {
        DatabaseConfig config = dataSourceManager.getCurrentConfig();
        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        if (config != null) {
            body.put("type", config.type());
            body.put("host", config.host());
            body.put("port", config.port());
            body.put("name", config.name());
            body.put("username", config.username());
            body.put("password", config.password());
        }
        return body;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> update(@RequestBody DatabaseConfigRequest request) {
        if (request == null) {
            return badRequest("请求体不能为空");
        }
        if (request.getType() == null || request.getType().isBlank()) {
            return badRequest("必须填写数据库类型");
        }
        if (!"postgres".equalsIgnoreCase(request.getType())) {
            return badRequest("目前仅支持 PostgreSQL");
        }
        if (request.getHost() == null || request.getHost().isBlank()) {
            return badRequest("必须填写数据库主机");
        }
        if (request.getPort() == null || request.getPort() <= 0 || request.getPort() > 65535) {
            return badRequest("数据库端口不合法");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            return badRequest("必须填写数据库名");
        }
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return badRequest("必须填写数据库用户名");
        }
        DatabaseConfig config = new DatabaseConfig(
                request.getType(),
                request.getHost(),
                request.getPort(),
                request.getName(),
                request.getUsername(),
                request.getPassword() == null ? "" : request.getPassword()
        );
        Map<String, Object> body = new HashMap<>();
        try {
            DatabaseConfig applied = dataSourceManager.update(config);
            body.put("ok", true);
            body.put("type", applied.type());
            body.put("host", applied.host());
            body.put("port", applied.port());
            body.put("name", applied.name());
            body.put("username", applied.username());
            body.put("password", applied.password());
            return ResponseEntity.ok(body);
        } catch (Exception ex) {
            return badRequest("连接数据库失败: " + ex.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("ok", false);
        body.put("error", message);
        return ResponseEntity.badRequest().body(body);
    }

    public static class DatabaseConfigRequest {
        private String type;
        private String host;
        private Integer port;
        private String name;
        private String username;
        private String password;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
