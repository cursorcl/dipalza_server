package cl.eos.dipalza.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class DownloadsStaticConfig implements WebMvcConfigurer {

    @Value("${app.downloads.location:file:/opt/dipalza-app/downloads/}")
    private String downloadsLocation;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/downloads/**")
                .addResourceLocations(downloadsLocation);
    }
}
