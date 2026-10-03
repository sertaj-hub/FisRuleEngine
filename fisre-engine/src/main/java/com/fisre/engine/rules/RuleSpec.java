package com.fisre.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;

/** One rule spec file (specs/rules/*.yml). The {@code tests} section is used by the build, not stored. */
public record RuleSpec(String code, String name, String description, String template, String status,
                       int suppressDays, JsonNode config, String sourceFile) {}
