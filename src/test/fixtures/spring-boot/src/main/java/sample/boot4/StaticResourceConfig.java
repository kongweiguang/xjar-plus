package sample.boot4;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {
    /** Add a separate ETag route without wrapping default responses in a caching filter that could conceal broken lengths. */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/etag/**").addResourceLocations("classpath:/static/")
                .setEtagGenerator(StaticResourceConfig::etag);
    }

    /** Hash the actual resource stream so conditional requests prove the same plaintext is visible through Spring's resource API. */
    private static String etag(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return '"' + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())) + '"';
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JDK SHA-256 unavailable", unavailable);
        }
    }
}
