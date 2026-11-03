package com.klu.sports;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Configuration
public class DatabaseConfig {
    @Bean
    HikariDataSource dataSource(@Value("${DATABASE_URL:postgresql://postgres:postgres@localhost:5432/SPORTS}") String rawUrl) {
        try {
            URI uri = URI.create(rawUrl.replaceFirst("^postgresql://", "http://"));
            String[] credentials = uri.getUserInfo().split(":", 2);
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl("jdbc:postgresql://" + uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 5432) + uri.getPath());
            source.setUsername(credentials[0]);
            source.setPassword(credentials.length > 1 ? URLDecoder.decode(credentials[1], StandardCharsets.UTF_8) : "");
            return source;
        } catch (Exception error) {
            throw new IllegalStateException("DATABASE_URL must be a valid PostgreSQL connection URL.", error);
        }
    }
}