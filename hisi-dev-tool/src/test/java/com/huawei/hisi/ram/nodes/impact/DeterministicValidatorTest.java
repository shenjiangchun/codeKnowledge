package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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

        when(kg.rootEntries(anyString(), anyString(), anyString()))
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
}
