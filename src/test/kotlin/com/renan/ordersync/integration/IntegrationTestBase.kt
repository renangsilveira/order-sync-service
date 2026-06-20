package com.renan.ordersync.integration

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Base class for integration tests that require a real PostgreSQL database.
 *
 * Uses the Testcontainers Singleton pattern: the container is started once when the
 * companion object is first loaded, stays alive for the entire JVM session, and is
 * stopped by Testcontainers' Ryuk sidecar on JVM exit. This avoids the per-class
 * lifecycle that @Testcontainers + @Container would impose, which would restart the
 * container between test classes and invalidate the shared Spring context's HikariCP
 * connection pool.
 *
 * Flyway migrations run automatically on context startup, guaranteeing the schema is
 * always up-to-date.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    companion object {
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordersync_test")
            .withUsername("ordersync")
            .withPassword("ordersync")
            .also { it.start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureDataSource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
