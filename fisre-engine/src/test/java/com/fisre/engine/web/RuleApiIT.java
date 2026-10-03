package com.fisre.engine.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fisre.engine.Fixtures;
import com.fisre.engine.config.FisreProperties;
import com.fisre.engine.spec.Req;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The REST API and its access rules (ADR-0010). */
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
class RuleApiIT {

    static final String PW = "correct-horse-battery";
    static final String BODY = "{\"name\":\"Cash in a day\",\"description\":\"d\",\"template\":\"AGGREGATE\",\"suppressDays\":0,"
            + "\"config\":{\"window_days\":1,\"min_sum\":10000,\"filter\":{\"cash\":true}},\"reason\":\"new rule\"}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FisreProperties props;
    @Autowired RuleUserService users;

    @BeforeEach
    void reset() {
        new Fixtures(jdbc, props).resetAll();
        users.createOrUpdate("viola", PW, "VIEWER");
        users.createOrUpdate("alice", PW, "AUTHOR");
        users.createOrUpdate("bob", PW, "APPROVER");
        new Fixtures(jdbc, props).liveBatch("B-1", "2026-10-01");
        new Fixtures(jdbc, props).account("A1", "DEPOSIT", "C1", "2020-01-01");
        new Fixtures(jdbc, props).txn("T1", "A1", "CASH_DEPOSIT", "CREDIT", "11000.00", "2026-09-30");
    }

    @Test
    @Req("REQ-RUI-012")
    void thePageIsPublicTheApiIsNot_andPasswordsAreHashed() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());      // the welcome page forwards to index.html
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Rule configuration")));
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/api/rules")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rules").with(httpBasic("alice", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        String hash = jdbc.queryForObject("SELECT password_hash FROM " + props.schemas().aml() + ".rule_user WHERE username = 'alice'", String.class);
        org.assertj.core.api.Assertions.assertThat(hash).startsWith("{bcrypt}").doesNotContain(PW);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> users.createOrUpdate("weak", "short", "VIEWER")).hasMessageContaining("at least 12");
    }

    @Test
    @Req("REQ-RUI-005")
    void rolesDecideWhoCanReadWriteAndApprove() throws Exception {
        mvc.perform(get("/api/rules").with(httpBasic("viola", PW))).andExpect(status().isOk());
        mvc.perform(post("/api/rules/API_RULE/versions").with(httpBasic("viola", PW)).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/rules/API_RULE/versions").with(httpBasic("bob", PW)).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/rules/API_RULE/versions").with(httpBasic("alice", PW)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.authoredBy").value("alice"));
        mvc.perform(post("/api/rules/API_RULE/versions/1/dry-run").with(httpBasic("alice", PW))).andExpect(status().isOk());
        mvc.perform(post("/api/rules/API_RULE/versions/1/submit").with(httpBasic("alice", PW))).andExpect(status().isOk());
        mvc.perform(post("/api/rules/API_RULE/versions/1/approve").with(httpBasic("alice", PW))).andExpect(status().isForbidden());   // author role cannot approve
        mvc.perform(post("/api/rules/API_RULE/versions/1/approve").with(httpBasic("bob", PW)).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"ok\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.decidedBy").value("bob"));
        mvc.perform(put("/api/rules/API_RULE/versions/1").with(httpBasic("alice", PW)).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isConflict());
        mvc.perform(get("/api/rules/NOPE/versions").with(httpBasic("viola", PW))).andExpect(status().isNotFound());
        mvc.perform(post("/api/rules/BAD/versions").with(httpBasic("alice", PW)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"template\":\"AGGREGATE\",\"config\":{},\"reason\":\"r\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/me").with(httpBasic("bob", PW))).andExpect(jsonPath("$.roles[0]").value("APPROVER"));
    }

    @Test
    @Req("REQ-RUI-009")
    void templateDescriptionsAreServedForTheEditor() throws Exception {
        mvc.perform(get("/api/templates").with(httpBasic("viola", PW))).andExpect(status().isOk())
                .andExpect(jsonPath("$.AGGREGATE[?(@.key=='window_days')].required").value(true))
                .andExpect(jsonPath("$.BASELINE_DEVIATION[?(@.key=='metric')].options[0]").exists())
                .andExpect(jsonPath("$.ML_SCORE").isArray());
        mvc.perform(get("/api/txn-types").with(httpBasic("viola", PW))).andExpect(status().isOk()).andExpect(jsonPath("$[0].txn_type").exists());
    }

    @Test
    @Req("REQ-RUI-007")
    void adHocDryRunWorksFromTheEditorWithoutSaving() throws Exception {
        mvc.perform(post("/api/dry-run").with(httpBasic("alice", PW)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"TRY_RULE\",\"template\":\"AGGREGATE\",\"days\":3,\"config\":{\"window_days\":1,\"min_sum\":10000,\"filter\":{\"cash\":true}}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalProposed").value(1)).andExpect(jsonPath("$.perDay.length()").value(3));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + props.schemas().aml() + ".rule WHERE rule_code = 'TRY_RULE'", Long.class)).isZero();
    }
}
