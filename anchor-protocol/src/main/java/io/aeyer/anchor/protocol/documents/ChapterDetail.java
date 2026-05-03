package io.aeyer.anchor.protocol.documents;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public record ChapterDetail(
        @JsonProperty("chapter_id") UUID chapterId,
        @JsonProperty("ordinal") int ordinal,
        @JsonProperty("title") String title,
        @JsonProperty("summary") String summary,
        @JsonProperty("is_synthetic") boolean isSynthetic,
        @JsonProperty("sections") List<SectionDetail> sections) {}
