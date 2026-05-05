package io.aeyer.anchor.server.persistence.mapper;

import io.aeyer.anchor.server.domain.Chapter;
import io.aeyer.anchor.server.domain.Chunk;
import io.aeyer.anchor.server.domain.Document;
import io.aeyer.anchor.server.domain.Paragraph;
import io.aeyer.anchor.server.domain.Section;
import io.aeyer.anchor.server.persistence.entity.ChapterDbo;
import io.aeyer.anchor.server.persistence.entity.ChunkDbo;
import io.aeyer.anchor.server.persistence.entity.DocumentDbo;
import io.aeyer.anchor.server.persistence.entity.ParagraphDbo;
import io.aeyer.anchor.server.persistence.entity.SectionDbo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EntityToDomainMapper {

    Document toDomain(DocumentDbo dbo);

    // Record component is `isSynthetic`; bean property on the entity is `synthetic`.
    @Mapping(source = "synthetic", target = "isSynthetic")
    Chapter toDomain(ChapterDbo dbo);

    @Mapping(source = "synthetic", target = "isSynthetic")
    Section toDomain(SectionDbo dbo);

    Paragraph toDomain(ParagraphDbo dbo);

    Chunk toDomain(ChunkDbo dbo);
}
