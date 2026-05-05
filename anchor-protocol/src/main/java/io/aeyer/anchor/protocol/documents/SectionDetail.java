package io.aeyer.anchor.protocol.documents;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record SectionDetail(
        @JsonProperty("section_id") UUID sectionId,
        @JsonProperty("ordinal") int ordinal,
        @JsonProperty("title") String title,
        @JsonProperty("is_synthetic") boolean isSynthetic,
        @JsonProperty("summary") String summary) {}
