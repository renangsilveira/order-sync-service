package com.renan.ordersync.integration

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Base class for integration tests that require a real PostgreSQL database.
 *
 * Uses a shared Testcontainers PostgreSQL container (singleton pattern) to avoid
 * starting a new container for every test class. Flyway migrations run automatically
 * on context startup, guaranteeing the schema is always up-to-date.
 *
 * The CI pipeline no longer needs a separate `postgres` service because Testcontainers
 * manages its own container. Docker-in-Docker is supported on GitHub Actions runners
 * with no extra configuration.
 *
 * The `test` Spring profile activates `application-test.yml`, which sets retry waits
 * to 50ms so integration tests finish quickly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordersync_test")
            .withUsername("ordersync")
            .withPassword("ordersync")
            .withReuse(true) // reuse between classes within the same JVM session

        @DynamicPropertySource
        @JvmStatic
        fun configureDataSource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
