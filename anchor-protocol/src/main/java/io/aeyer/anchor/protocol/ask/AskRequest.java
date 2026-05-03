package io.aeyer.anchor.protocol.ask;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AskRequest(@JsonProperty("query") String query) {}
