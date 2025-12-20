package com.example.nvr;

import com.example.nvr.config.HttpClientProperties;
import com.example.nvr.config.TcbProperties;
import com.example.nvr.config.TcbSecurityProperties;
import com.example.nvr.config.WeixinOpenApiProperties;
import com.example.nvr.config.WxProperties;
import java.util.TimeZone;
import javax.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        WxProperties.class,
        WeixinOpenApiProperties.class,
        TcbProperties.class,
        TcbSecurityProperties.class,
        HttpClientProperties.class
})
public class Application {
    @PostConstruct
    void started() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    public static void main(String[] args) {
        if (System.getProperty("http.auth.digest.reEnabledAlgorithms") == null) {
            System.setProperty("http.auth.digest.reEnabledAlgorithms", "MD5,MD5-sess");
        }
        SpringApplication.run(Application.class, args);
    }
}
