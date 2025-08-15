package com.example.nvr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.PostConstruct;
import java.io.File;

@Configuration
public class StaticMap implements WebMvcConfigurer {
    @Value("${nvr.hlsRoot:/var/www/streams}")
    private String hlsRoot;

    @PostConstruct
    public void logRoots() {
        System.out.println("[static] hlsRoot=\"" + new File(hlsRoot).getAbsolutePath() + "\"");
        System.out.println("[static] altRoot1=\"" + new File("./streams").getAbsolutePath() + "\"");
        System.out.println("[static] altRoot2=\"" + new File("./backend/streams").getAbsolutePath() + "\"");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String primary = "file:" + new File(hlsRoot).getAbsolutePath() + "/";
        String alt1 = "file:" + new File("./streams").getAbsolutePath() + "/";
        String alt2 = "file:" + new File("./backend/streams").getAbsolutePath() + "/";
        registry.addResourceHandler("/streams/**")
                .addResourceLocations(primary, alt1, alt2)
                .setCacheControl(CacheControl.noStore());
    }
}
