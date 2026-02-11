package com.worldmind.dispatch.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request body for the retry endpoint.
 */
public record RetryRequest(
    @JsonProperty("directive_ids") List<String> directiveIds
) {}
