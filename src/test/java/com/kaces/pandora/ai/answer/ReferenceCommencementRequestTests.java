package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;

class ReferenceCommencementRequestTests {
    @Test void parsesOnlyAnExplicitGeneralDateAndStatusRequestForOneNamedLaw() {
        assertThat(ReferenceCommencementRequest.parse("2026년11월18일기준예시행정법의일반시행일과현재시행상태는무엇인가요"))
            .hasValueSatisfying(request -> {
                assertThat(request.referenceDate()).isEqualTo(LocalDate.of(2026,11,18));
                assertThat(request.matchesTitle("예시행정법")).isTrue();
                assertThat(request.matchesTitle("다른행정법")).isFalse();
            });
    }
    @Test void rejectsProvisionQueriesInvalidDatesAndUnqualifiedAllProvisionStatus() {
        for(String query:java.util.List.of("예시행정법의일반시행일과현재시행상태는무엇인가요",
            "2026년2월30일기준예시행정법의일반시행일과현재시행상태는무엇인가요",
            "2026년11월18일기준예시행정법제32조의일반시행일과현재시행상태는무엇인가요",
            "2026년11월18일기준예시행정법의모든조문시행일과현재시행상태는무엇인가요",
            "2026년11월18일기준예시행정법및다른행정법의일반시행일과현재시행상태는무엇인가요"))
            assertThat(ReferenceCommencementRequest.parse(query)).isEmpty();
        assertThat(ReferenceCommencementRequest.parse(null)).isEmpty();
    }
}
