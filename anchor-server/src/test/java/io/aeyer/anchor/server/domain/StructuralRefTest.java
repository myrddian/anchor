package io.aeyer.anchor.server.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StructuralRefTest {

    @Test
    void named_section_resolves_to_named_with_real_title() {
        Section s = new Section(UUID.randomUUID(), UUID.randomUUID(), 0, "Introduction", "claim", false);
        assertThat(StructuralRef.ofSection(s))
                .isInstanceOfSatisfying(StructuralRef.Named.class,
                        n -> assertThat(n.title()).isEqualTo("Introduction"));
    }

    @Test
    void synthetic_section_resolves_to_synthetic_regardless_of_stored_title() {
        // The DB still holds the sentinel so a render path that bypasses
        // StructuralRef makes the bug obvious; the helper itself drops it.
        Section s = new Section(UUID.randomUUID(), UUID.randomUUID(), 0,
                SyntheticTitles.SECTION, "claim", true);
        assertThat(StructuralRef.ofSection(s)).isInstanceOf(StructuralRef.Synthetic.class);
    }

    @Test
    void named_chapter_resolves_to_named_with_real_title() {
        Chapter c = new Chapter(UUID.randomUUID(), UUID.randomUUID(), 0, "Chapter 1", "claim", false);
        assertThat(StructuralRef.ofChapter(c))
                .isInstanceOfSatisfying(StructuralRef.Named.class,
                        n -> assertThat(n.title()).isEqualTo("Chapter 1"));
    }

    @Test
    void synthetic_chapter_resolves_to_synthetic() {
        Chapter c = new Chapter(UUID.randomUUID(), UUID.randomUUID(), 0,
                SyntheticTitles.CHAPTER, "claim", true);
        assertThat(StructuralRef.ofChapter(c)).isInstanceOf(StructuralRef.Synthetic.class);
    }

    @Test
    void sentinel_strings_are_obviously_internal_so_a_leaked_value_is_grep_distinct() {
        // Both sentinels start and end with __ — anything containing this
        // substring in API output, logs, or LLM responses is a bug.
        assertThat(SyntheticTitles.SECTION).startsWith("__").endsWith("__");
        assertThat(SyntheticTitles.CHAPTER).startsWith("__").endsWith("__");
        // And they're distinct so logs disambiguate which boundary failed.
        assertThat(SyntheticTitles.SECTION).isNotEqualTo(SyntheticTitles.CHAPTER);
    }
}
