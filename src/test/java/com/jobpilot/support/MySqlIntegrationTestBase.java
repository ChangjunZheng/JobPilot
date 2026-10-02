package com.jobpilot.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 集成测试基类（I-1 顺带清理项）：
 * <ul>
 *   <li><b>Docker 可用</b>：共享一个 MySQL 8.4 容器；每个 Spring 上下文启动前清空业务表，
 *       让 Flyway 从零迁移——context 会按配置缓存复用，若不清表，V3/V4 的 ALTER 会撞「列已存在」；</li>
 *   <li><b>Docker 不可用</b>：什么都不注册，回退 {@code application-local.yml} 的本机 MySQL——
 *       本机没跑 Docker Desktop 时测试依旧可跑，历史行为不变。</li>
 * </ul>
 * 这样「mvn test 必须依赖本机 MySQL」被解除：CI 与任何装有 Docker 的环境都不再需要本地库。
 * 继承类保留各自的 {@code @SpringBootTest} / {@code @ActiveProfiles("local")} / {@code @Transactional} 注解。
 * <p>
 * 清表语句是字面量而非拼接：表的集合是封闭的（业务四表 + flyway 历史），动态拼标识符
 * 既没有必要，也过不了安全扫描（标识符无法参数化，拼接是唯一写法）。
 */
public abstract class MySqlIntegrationTestBase {

    private static final boolean DOCKER_AVAILABLE = detectDocker();

    private static final MySQLContainer<?> MYSQL = createContainer();

    private static boolean detectDocker() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    private static MySQLContainer<?> createContainer() {
        if (!DOCKER_AVAILABLE) {
            return null;
        }
        MySQLContainer<?> container = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("jobpilot")
                .withUsername("jobpilot")
                .withPassword("jobpilot");
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!DOCKER_AVAILABLE) {
            return; // 回退：交给 application-local.yml / CI 环境变量
        }
        resetSchema();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // CI 没有 application-local.yml 与密钥环境变量时，上下文也必须能起
        registry.add("jobpilot.security.jwt-secret",
                () -> "test-only-secret-that-is-at-least-32-bytes!");
    }

    private static void resetSchema() {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            statement.execute("DROP TABLE IF EXISTS kb_chunk");
            statement.execute("DROP TABLE IF EXISTS kb_document");
            statement.execute("DROP TABLE IF EXISTS user_credential");
            statement.execute("DROP TABLE IF EXISTS user_account");
            statement.execute("DROP TABLE IF EXISTS flyway_schema_history");
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        } catch (Exception e) {
            throw new IllegalStateException("重置测试容器 schema 失败", e);
        }
    }
}
