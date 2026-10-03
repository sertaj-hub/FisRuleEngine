package com.fisre.engine.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fisre.engine.spec.Req;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class JobLockIT {

    @Autowired JobLock lock;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Req("REQ-OPS-001")
    void onlyOneMutatingJobAtATime_andTheLockIsReleasedWhenItEnds() {
        try (JobLock.Held first = lock.acquire("nightly")) {
            assertThat(first).isNotNull();
            assertThatThrownBy(() -> lock.acquire("detect")).hasMessageContaining("another engine job holds the database lock");
        }
        try (JobLock.Held again = lock.acquire("detect")) {
            assertThat(again).as("free again after the first job ended").isNotNull();
        }
    }

    @Test
    @Req("REQ-OPS-002")
    void everyConnectionCarriesTheStatementTimeout() {
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("1h");
    }
}
