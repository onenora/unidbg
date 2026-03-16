package com.mengying.fqnovel.config;

import com.mengying.fqnovel.utils.Texts;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * PostgreSQL 章节缓存数据源配置（配置 DB_URL 后自动生效）。
 */
@Configuration
@Conditional(FQCachePostgresConfig.DbUrlPresentCondition.class)
public class FQCachePostgresConfig {

    private static final Logger log = LoggerFactory.getLogger(FQCachePostgresConfig.class);

    private final FQCachePostgresProperties properties;

    public FQCachePostgresConfig(FQCachePostgresProperties properties) {
        this.properties = properties;
    }

    @Bean(destroyMethod = "close")
    public DataSource pgCacheDataSource() {
        String rawUrl = Texts.trimToNull(properties.getUrl());
        if (rawUrl == null) {
            throw new IllegalStateException("未配置 DB_URL");
        }

        ResolvedConnection resolved = resolveConnection(rawUrl);
        if (!Texts.hasText(resolved.username())) {
            throw new IllegalStateException("DB_URL 无效：缺少用户名（格式应为 postgresql://user:pass@host:port/db）");
        }

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("fq-pg-cache");
        hikari.setDriverClassName("org.postgresql.Driver");
        hikari.setJdbcUrl(resolved.jdbcUrl());
        hikari.setUsername(resolved.username());
        hikari.setPassword(resolved.password());

        int maxPoolSize = Math.max(1, properties.getMaximumPoolSize());
        int minIdle = Math.max(0, Math.min(properties.getMinimumIdle(), maxPoolSize));
        hikari.setMaximumPoolSize(maxPoolSize);
        hikari.setMinimumIdle(minIdle);
        hikari.setConnectionTimeout(Math.max(1000L, properties.getConnectionTimeoutMs()));

        log.info("章节缓存已启用");
        return new HikariDataSource(hikari);
    }

    @Bean
    public JdbcTemplate pgCacheJdbcTemplate(DataSource pgCacheDataSource) {
        return new JdbcTemplate(pgCacheDataSource);
    }

    private static ResolvedConnection resolveConnection(String rawUrl) {
        String jdbcUrl = toJdbcUrl(rawUrl);
        String username = null;
        String password = "";

        URI uri = parseUriQuietly(rawUrl);
        if (hasPostgresScheme(uri)) {
            String userInfo = Texts.trimToNull(uri.getRawUserInfo());
            if (userInfo != null) {
                int split = userInfo.indexOf(':');
                String userPart = split >= 0 ? userInfo.substring(0, split) : userInfo;
                String passPart = split >= 0 ? userInfo.substring(split + 1) : null;

                String uriUser = decodeUriPart(userPart);
                String uriPass = decodeUriPart(passPart);

                username = Texts.trimToNull(uriUser);
                password = Texts.nullToEmpty(uriPass);
            }
        }

        return new ResolvedConnection(jdbcUrl, username, password);
    }

    private static String toJdbcUrl(String rawUrl) {
        String value = Texts.trimToNull(rawUrl);
        if (value == null) {
            return null;
        }

        URI uri = parseUriQuietly(value);
        if (uri == null) {
            throw new IllegalStateException("DB_URL 无效：格式错误");
        }
        if (!hasPostgresScheme(uri)) {
            throw new IllegalStateException("DB_URL 无效：必须以 postgresql:// 或 postgres:// 开头");
        }

        String host = Texts.trimToNull(uri.getHost());
        if (host == null) {
            throw new IllegalStateException("DB_URL 无效：缺少 host");
        }

        int port = uri.getPort();
        String path = Texts.trimToNull(uri.getRawPath());
        if (path == null || "/".equals(path)) {
            throw new IllegalStateException("DB_URL 无效：缺少数据库名");
        }

        StringBuilder sb = new StringBuilder("jdbc:postgresql://").append(host);
        if (port > 0) {
            sb.append(':').append(port);
        }
        sb.append(path);

        String query = Texts.trimToNull(uri.getRawQuery());
        if (query != null) {
            sb.append('?').append(query);
        }
        return sb.toString();
    }

    private static URI parseUriQuietly(String value) {
        try {
            return URI.create(value);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static boolean hasPostgresScheme(URI uri) {
        String scheme = uri == null ? null : Texts.trimToNull(uri.getScheme());
        return "postgresql".equalsIgnoreCase(scheme) || "postgres".equalsIgnoreCase(scheme);
    }

    private static String decodeUriPart(String value) {
        if (value == null) {
            return null;
        }
        // URLDecoder 会将 '+' 解释为空格，这里先转义为 %2B，避免密码/用户名中 '+' 被误改。
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private record ResolvedConnection(String jdbcUrl, String username, String password) {}

    /**
     * 仅当 fq.cache.postgres.url 有效时启用 PostgreSQL 章节缓存组件。
     */
    public static final class DbUrlPresentCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            if (context == null || context.getEnvironment() == null) {
                return false;
            }
            String url = context.getEnvironment().getProperty("fq.cache.postgres.url");
            return Texts.hasText(url);
        }
    }
}
