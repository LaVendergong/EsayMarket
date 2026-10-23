package com.mall.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把 {@code /uploads/**} 映射到本机上传目录。
 *
 * <p>换成对象存储后这个类可以直接删掉（那时 URL 指向 CDN，不再由应用提供静态文件）。
 */
@Configuration
public class UploadWebConfig implements WebMvcConfigurer {

    @Value("${mall.upload.dir:./uploads}")
    private String dir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = Paths.get(dir).toAbsolutePath().normalize();
        // toUri() 对目录会带上结尾斜杠，Spring 要求 location 以 / 结尾才当成目录
        String location = root.toUri().toString();
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
