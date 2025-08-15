package com.example.nvr;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MimeConfig implements WebMvcConfigurer {
    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.mediaType("m3u8", MediaType.valueOf("application/vnd.apple.mpegurl"));
        configurer.mediaType("ts", MediaType.valueOf("video/mp2t"));
    }
}

