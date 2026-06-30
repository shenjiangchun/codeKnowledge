package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeterministicValidatorTest {

    @Mock
    KgMcpClient kg;

    @Test
    void validate_flagsClaimedEntryNotReachableAsRoot() {
        Entry entryA = new Entry("EntryA", "ClsA", "ma", "CONTROLLER");
        Entry entryB = new Entry("EntryB", "ClsB", "mb", "CONTROLLER");
        Entry entryX = new Entry("EntryX", "ClsX", "mx", "CONTROLLER");

        // rootEntryAncestors returns entries reachable via caller chain
        // EntryA and EntryB are reachable (they are entry points), EntryX is not
        when(kg.rootEntryAncestors(any(List.class), anyList(), anyInt()))
                .thenReturn(List.of(
                        new Entry("EntryA", "ClsA", "ma", "CONTROLLER"),
                        new Entry("EntryB", "ClsB", "mb", "CONTROLLER")));

        InvolvedRing involved = new InvolvedRing(List.of(), List.of(entryA, entryB, entryX), List.of());
        ModifiedRing modified = new ModifiedRing(List.of());
        ImpactRing impact = new ImpactRing(List.of(), List.of(), List.of(), List.of());

        DeterministicValidator.ValidationOutcome outcome =
                new DeterministicValidator(kg).validate(involved, modified, impact, "/p");

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.violations())
                .anySatisfy(v -> assertThat(v).contains("EntryX"));
    }

    @Test
    void validate_passesWhenAllEntriesAreRootEntryPoints() {
        Entry entryA = new Entry("EntryA", "ClsA", "ma", "CONTROLLER");
        Entry entryB = new Entry("EntryB", "ClsB", "mb", "SCHEDULED");

        // Both entries are themselves root entry points
        when(kg.rootEntryAncestors(any(List.class), anyList(), anyInt()))
                .thenReturn(List.of(
                        new Entry("EntryA", "ClsA", "ma", "CONTROLLER"),
                        new Entry("EntryB", "ClsB", "mb", "SCHEDULED")));

        InvolvedRing involved = new InvolvedRing(List.of(), List.of(entryA, entryB), List.of());
        ModifiedRing modified = new ModifiedRing(List.of());
        ImpactRing impact = new ImpactRing(List.of(), List.of(), List.of(), List.of());

        DeterministicValidator.ValidationOutcome outcome =
                new DeterministicValidator(kg).validate(involved, modified, impact, "/p");

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.violations()).isEmpty();
    }
}
