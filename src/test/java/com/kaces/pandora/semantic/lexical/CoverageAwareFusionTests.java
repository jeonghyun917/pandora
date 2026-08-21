package com.kaces.pandora.semantic.lexical;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoverageAwareFusionTests {

	@Test
	void rescuesAnEligibleSiblingWithoutChangingTheResultSize() {
		Fixture fixture = fixture();

		CoverageAwareFusion.Result result = new CoverageAwareFusion().rerank(
			fixture.baseline(),
			fixture.documentIds(),
			new CoverageAwareFusion.Policy(true, 1, 1, 30),
			30
		);

		assertThat(result.status()).isEqualTo(CoverageAwareFusion.Status.APPLIED);
		assertThat(result.ranking()).hasSize(30);
		assertThat(result.ranking()).extracting(ReciprocalRankFusion.RrfHit::candidateKey)
			.contains("law:31")
			.doesNotContain("law:30")
			.doesNotHaveDuplicates();
		assertThat(result.rescues()).singleElement().satisfies(rescue -> {
			assertThat(rescue.anchorCandidateKey()).isEqualTo("law:1");
			assertThat(rescue.candidateKey()).isEqualTo("law:31");
			assertThat(rescue.documentKey()).isEqualTo("law:100");
			assertThat(rescue.baselineRank()).isEqualTo(31);
			assertThat(rescue.rescuedRank()).isEqualTo(30);
			assertThat(rescue.reason()).isEqualTo("DOCUMENT_SIBLING_RESCUE");
		});
	}

	@Test
	void disabledAndZeroBudgetPoliciesAreOutcomeIdenticalToBaseline() {
		Fixture fixture = fixture();
		CoverageAwareFusion fusion = new CoverageAwareFusion();

		CoverageAwareFusion.Result disabled = fusion.rerank(
			fixture.baseline(), fixture.documentIds(), new CoverageAwareFusion.Policy(false, 1, 1, 30), 30);
		CoverageAwareFusion.Result zeroBudget = fusion.rerank(
			fixture.baseline(), fixture.documentIds(), new CoverageAwareFusion.Policy(true, 0, 1, 30), 30);

		assertThat(disabled.status()).isEqualTo(CoverageAwareFusion.Status.DISABLED);
		assertThat(zeroBudget.status()).isEqualTo(CoverageAwareFusion.Status.DISABLED);
		assertThat(disabled.ranking()).containsExactlyElementsOf(fixture.baseline());
		assertThat(zeroBudget.ranking()).containsExactlyElementsOf(fixture.baseline());
	}

	@Test
	void respectsTheSourceRankBoundary() {
		Fixture fixture = fixture();

		CoverageAwareFusion.Result result = new CoverageAwareFusion().rerank(
			fixture.baseline(), fixture.documentIds(), new CoverageAwareFusion.Policy(true, 1, 1, 20), 30);

		assertThat(result.status()).isEqualTo(CoverageAwareFusion.Status.NO_ELIGIBLE_SIBLING);
		assertThat(result.ranking()).containsExactlyElementsOf(fixture.baseline());
	}

	@Test
	void neverTreatsTheSameNumericDocumentIdAcrossTargetsAsSiblings() {
		Fixture fixture = fixture();
		List<ReciprocalRankFusion.RrfHit> baseline = new ArrayList<>(fixture.baseline());
		baseline.set(30, hit("admrul", 31, 28, null));
		Map<String, Long> documentIds = new LinkedHashMap<>(fixture.documentIds());
		documentIds.remove("law:31");
		documentIds.put("admrul:31", 100L);

		CoverageAwareFusion.Result result = new CoverageAwareFusion().rerank(
			baseline, documentIds, new CoverageAwareFusion.Policy(true, 1, 1, 30), 30);

		assertThat(result.status()).isEqualTo(CoverageAwareFusion.Status.NO_ELIGIBLE_SIBLING);
		assertThat(result.ranking()).containsExactlyElementsOf(baseline);
	}

	@Test
	void appliesAtMostOneRescuePerDocumentAndTheGlobalBudget() {
		Fixture fixture = fixture();
		List<ReciprocalRankFusion.RrfHit> baseline = new ArrayList<>(fixture.baseline());
		baseline.set(31, hit("law", 32, 29, null));
		Map<String, Long> documentIds = new LinkedHashMap<>(fixture.documentIds());
		documentIds.put("law:32", 100L);

		CoverageAwareFusion.Result result = new CoverageAwareFusion().rerank(
			baseline, documentIds, new CoverageAwareFusion.Policy(true, 2, 1, 30), 30);

		assertThat(result.rescues()).singleElement();
		assertThat(result.ranking()).extracting(ReciprocalRankFusion.RrfHit::candidateKey)
			.contains("law:31")
			.doesNotContain("law:32");
	}

	@Test
	void appliesTwoDeterministicallyOrderedRescuesFromDifferentDocuments() {
		Fixture fixture = fixture();
		List<ReciprocalRankFusion.RrfHit> baseline = new ArrayList<>(fixture.baseline());
		baseline.set(1, hit("law", 2, 2, 2));
		baseline.set(31, hit("law", 32, 28, null));
		Map<String, Long> documentIds = new LinkedHashMap<>(fixture.documentIds());
		documentIds.put("law:2", 200L);
		documentIds.put("law:32", 200L);

		CoverageAwareFusion.Result result = new CoverageAwareFusion().rerank(
			baseline, documentIds, new CoverageAwareFusion.Policy(true, 2, 1, 30), 30);

		assertThat(result.rescues()).extracting(CoverageAwareFusion.Rescue::candidateKey)
			.containsExactly("law:31", "law:32");
		assertThat(result.ranking()).extracting(ReciprocalRankFusion.RrfHit::candidateKey)
			.endsWith("law:31", "law:32")
			.doesNotHaveDuplicates();
	}

	@Test
	void neverDisplacesProtectedCrossSourceAnchors() {
		Fixture fixture = fixture();
		List<ReciprocalRankFusion.RrfHit> baseline = new ArrayList<>(fixture.baseline());
		for (int index = 0; index < 30; index++) {
			baseline.set(index, hit("law", index + 1L, index + 1, index + 1));
		}

		CoverageAwareFusion.Result result = new CoverageAwareFusion().rerank(
			baseline, fixture.documentIds(), new CoverageAwareFusion.Policy(true, 1, 1, 30), 30);

		assertThat(result.status()).isEqualTo(CoverageAwareFusion.Status.FALLBACK_BASELINE);
		assertThat(result.ranking()).containsExactlyElementsOf(baseline);
	}

	@Test
	void fallsBackForAnInvalidPolicy() {
		Fixture fixture = fixture();

		CoverageAwareFusion.Result result = new CoverageAwareFusion().rerank(
			fixture.baseline(), fixture.documentIds(), new CoverageAwareFusion.Policy(true, 1, 2, 30), 30);

		assertThat(result.status()).isEqualTo(CoverageAwareFusion.Status.FALLBACK_BASELINE);
		assertThat(result.ranking()).containsExactlyElementsOf(fixture.baseline());
	}

	@Test
	void fallsBackWhenCandidateIdentityIsIncompleteOrDuplicated() {
		Fixture fixture = fixture();
		Map<String, Long> incomplete = new LinkedHashMap<>(fixture.documentIds());
		incomplete.remove("law:20");
		List<ReciprocalRankFusion.RrfHit> duplicated = new ArrayList<>(fixture.baseline());
		duplicated.set(31, duplicated.get(30));
		CoverageAwareFusion fusion = new CoverageAwareFusion();

		CoverageAwareFusion.Result missingIdentity = fusion.rerank(
			fixture.baseline(), incomplete, new CoverageAwareFusion.Policy(true, 1, 1, 30), 30);
		CoverageAwareFusion.Result duplicate = fusion.rerank(
			duplicated, fixture.documentIds(), new CoverageAwareFusion.Policy(true, 1, 1, 30), 30);

		assertThat(missingIdentity.status()).isEqualTo(CoverageAwareFusion.Status.FALLBACK_BASELINE);
		assertThat(missingIdentity.ranking()).containsExactlyElementsOf(fixture.baseline());
		assertThat(duplicate.status()).isEqualTo(CoverageAwareFusion.Status.FALLBACK_BASELINE);
		assertThat(duplicate.ranking()).containsExactlyElementsOf(duplicated);
	}

	private Fixture fixture() {
		List<ReciprocalRankFusion.RrfHit> baseline = new ArrayList<>();
		Map<String, Long> documentIds = new LinkedHashMap<>();
		for (int index = 1; index <= 32; index++) {
			ReciprocalRankFusion.RrfHit hit = index == 1
				? hit("law", index, 1, 1)
				: index == 31
					? hit("law", index, 28, null)
					: hit("law", index, index, null);
			baseline.add(hit);
			documentIds.put(hit.candidateKey(), index == 1 || index == 31 ? 100L : 1_000L + index);
		}
		return new Fixture(List.copyOf(baseline), Map.copyOf(documentIds));
	}

	private ReciprocalRankFusion.RrfHit hit(String target, long chunkId, Integer vectorRank, Integer lexicalRank) {
		int bestSourceRank = Math.min(
			vectorRank == null ? Integer.MAX_VALUE : vectorRank,
			lexicalRank == null ? Integer.MAX_VALUE : lexicalRank
		);
		return new ReciprocalRankFusion.RrfHit(
			target + ':' + chunkId,
			target,
			chunkId,
			1.0 / (60 + chunkId),
			vectorRank,
			lexicalRank,
			bestSourceRank
		);
	}

	private record Fixture(
		List<ReciprocalRankFusion.RrfHit> baseline,
		Map<String, Long> documentIds
	) {
	}
}
