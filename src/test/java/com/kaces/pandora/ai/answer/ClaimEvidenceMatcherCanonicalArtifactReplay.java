package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ClaimEvidenceMatcherCanonicalArtifactReplay {

	private static final Path CANONICAL_ARTIFACT = Path.of(
		"logs",
		"rag-eval-gate-full-post-hardening-post-retry-fix-20260720.json"
	);
	private static final Set<String> FALSE_POSITIVE_IDS = Set.of(
		"project-review-simple-software",
		"pre-consultation-target",
		"pre-consultation-when",
		"security-review-target",
		"it-compliance-penalty",
		"egov-preliminary-review-target",
		"rfp-tech-score-table",
		"public-data-db-standard",
		"procurement-catalog-contract",
		"whistleblower-protection-scope",
		"video-cctv-guide",
		"personal-info-purpose",
		"privacy-consent-notice-items",
		"pipc-cctv-public-place-exception",
		"pipc-pseudonym-additional-info",
		"project-review-all-sw-projects",
		"project-review-exclusion-hardware",
		"procurement-digital-service-mall",
		"cctv-public-place-rule",
		"cctv-retention-not-fixed-30",
		"security-review-major-infra",
		"security-review-skip-condition",
		"rfp-requirement-method",
		"commercial-sw-direct-buy-target",
		"procurement-catalog-vs-contract",
		"pseudonym-extra-info-separate",
		"privacy-retention-notice",
		"privacy-minimum-collection",
		"privacy-destruction-principle",
		"cctv-install-purpose-limit",
		"public-data-meta-management",
		"admrul-notice-exception",
		"public-data-obligation-system"
	);
	private static final Set<String> GENUINE_FAIL_CLOSED_IDS = Set.of(
		"project-review-pre-consultation-relation",
		"security-review-exception",
		"commercial-sw-direct-purchase",
		"whistleblower-disadvantage",
		"whistleblower-protection-action"
	);

	private final ClaimEvidenceMatcher matcher = new ClaimEvidenceMatcher();
	private final ClaimVerifier verifier = new ClaimVerifier(matcher);

	@Test
	void canonicalArtifactContainsTheAdjudicatedReplayScope() throws IOException {
		Map<String, List<ReplayLink>> cases = loadReplayCases();
		Set<String> expectedIds = new LinkedHashSet<>(FALSE_POSITIVE_IDS);
		expectedIds.addAll(GENUINE_FAIL_CLOSED_IDS);

		assertThat(cases.keySet()).containsExactlyInAnyOrderElementsOf(expectedIds);
		assertThat(cases).hasSize(38);
		assertThat(cases.values().stream().mapToInt(List::size).sum()).isEqualTo(74);
	}

	@Test
	void allThirtyThreeObservedFalsePositiveCasesLoseTheirFalseContradictions() throws IOException {
		Map<String, List<ReplayLink>> cases = loadReplayCases();
		List<String> falseContradictions = new ArrayList<>();

		for (String id : FALSE_POSITIVE_IDS) {
			for (ReplayLink link : cases.getOrDefault(id, List.of())) {
				ClaimEvidenceMatcher.Status status = replay(link).status();
				if (status == ClaimEvidenceMatcher.Status.CONTRADICTED
					|| status == ClaimEvidenceMatcher.Status.CONFLICTED) {
					falseContradictions.add(id + ": " + link.claim() + " -> " + status);
				}
			}
		}

		assertThat(falseContradictions).isEmpty();
	}

	@Test
	void genuineDirectConflictAndOverreachClaimsRemainFailClosed() throws IOException {
		Map<String, List<ReplayLink>> cases = loadReplayCases();
		ReplayLink directConflict = cases.get("security-review-exception").get(0);
		ClaimEvidenceMatcher.Match directMatch = replay(directConflict);
		ClaimVerifier.VerificationResult directResult = verify(directConflict);

		assertThat(directMatch.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		assertThat(directResult.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(directResult.contradictedClaims()).isEmpty();

		List<String> unsafePromotions = new ArrayList<>();
		for (String id : GENUINE_FAIL_CLOSED_IDS) {
			if (id.equals("security-review-exception")) {
				continue;
			}
			for (ReplayLink link : cases.getOrDefault(id, List.of())) {
				ClaimVerifier.VerificationResult result = verify(link);
				if (!result.verifiedAnswer().equals(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE)) {
					unsafePromotions.add(id + ": " + link.claim());
				}
			}
		}

		assertThat(unsafePromotions).isEmpty();
	}

	private ClaimEvidenceMatcher.Match replay(ReplayLink link) {
		return matcher.match(link.claim(), List.of(ground(link)));
	}

	private ClaimVerifier.VerificationResult verify(ReplayLink link) {
		return verifier.verifyDetailed(link.claim(), List.of(ground(link)));
	}

	private LawAiAnswerGround ground(ReplayLink link) {
		return new LawAiAnswerGround(
			link.groundNumber(),
			link.groundNumber(),
			link.groundNumber(),
			"official_doc",
			"canonical replay",
			"official agency",
			"official_doc",
			null,
			null,
			"page 1",
			"canonical replay",
			1,
			link.evidenceSentence(),
			null,
			null,
			0.9
		);
	}

	private Map<String, List<ReplayLink>> loadReplayCases() throws IOException {
		assertThat(Files.isRegularFile(CANONICAL_ARTIFACT))
			.as("canonical evaluation artifact")
			.isTrue();
		JsonNode root = new ObjectMapper().readTree(Files.readString(CANONICAL_ARTIFACT));
		Set<String> targetIds = new LinkedHashSet<>(FALSE_POSITIVE_IDS);
		targetIds.addAll(GENUINE_FAIL_CLOSED_IDS);
		Map<String, List<ReplayLink>> cases = new LinkedHashMap<>();

		for (JsonNode result : root.get("results")) {
			String id = result.get("id").asText();
			if (!targetIds.contains(id)) {
				continue;
			}
			List<ReplayLink> links = new ArrayList<>();
			for (JsonNode link : result.get("claimEvidenceLinks")) {
				String relation = link.get("relation").asText();
				if (!relation.equals("CONTRADICTED") && !relation.equals("CONFLICTED")) {
					continue;
				}
				links.add(new ReplayLink(
					link.get("claim").asText(),
					link.get("groundNumber").asInt(),
					link.get("evidenceSentence").asText()
				));
			}
			cases.put(id, List.copyOf(links));
		}

		return cases;
	}

	private record ReplayLink(String claim, int groundNumber, String evidenceSentence) {
	}
}
