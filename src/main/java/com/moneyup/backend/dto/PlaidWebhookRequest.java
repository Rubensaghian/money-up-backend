package com.moneyup.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaidWebhookRequest(
        @JsonProperty("webhook_type") String webhookType,
        @JsonProperty("webhook_code") String webhookCode,
        @JsonProperty("item_id") String itemId
) {
}
