package com.exam.allocation.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Drops the legacy {@code fcm_token} column from {@code push_subscriptions}
 * after the move from Firebase FCM to native Web Push.
 *
 * <p>Hibernate's {@code ddl-auto=update} creates and alters the new
 * {@code endpoint} / {@code p256dh} / {@code auth} columns but never drops
 * removed ones, and the old column was {@code NOT NULL} - without this
 * migration every new subscription insert on an existing database would fail.
 * The check runs through {@code information_schema} (MySQL); on other engines
 * the query simply fails and is logged, leaving the table untouched.
 */
@Component
public class PushSubscriptionSchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PushSubscriptionSchemaMigration.class);

    private final JdbcTemplate jdbcTemplate;

    public PushSubscriptionSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer present = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = 'push_subscriptions' "
                            + "AND COLUMN_NAME = 'fcm_token'",
                    Integer.class);
            if (present != null && present > 0) {
                jdbcTemplate.execute("ALTER TABLE push_subscriptions DROP COLUMN fcm_token");
                log.info("Dropped legacy fcm_token column from push_subscriptions "
                        + "(native Web Push is in use).");
            }
        } catch (RuntimeException ex) {
            log.warn("Could not drop the legacy fcm_token column: {} - if the column still "
                    + "exists and is NOT NULL, remove it manually.", ex.getMessage());
        }
    }
}
