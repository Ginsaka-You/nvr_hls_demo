package com.example.nvr;

import com.example.nvr.config.HttpClientProperties;
import com.example.nvr.config.TcbProperties;
import com.example.nvr.config.TcbSecurityProperties;
import com.example.nvr.config.WeixinOpenApiProperties;
import com.example.nvr.config.WxProperties;
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
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
