package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ClaimVerifier {

	private static final Pattern SENTENCE_BOUNDARY = Pattern.compile(
		"(?<=[!?])\\s+|(?<=[가-힣A-Za-z][.])\\s+|\\n+"
	);
	private static final Pattern NUMBER_OR_DATE_CLAIM = Pattern.compile(
		"\\d[\\d,]*(?:\\.\\d+)?\\s*(?:년|월|일|개월|일내|일 이내|점|%|퍼센트|원|만원|억원|개|건|명|회|차|시간)?"
	);
	private static final Pattern ASSERTIVE_ENDING = Pattern.compile(
		"(?:입니다|합니다|됩니다|있습니다|없습니다|아닙니다|해당합니다|포함됩니다|제외됩니다|요구됩니다"
			+ "|않습니다|이다|한다|된다|있다|없다|아니다|않는다|않음"
			+ "|이며|이고|있고|없고|하며|되고|되며|으며|하되|지만|습니다만|합니다만|됩니다만"
			+ "|입니다만|아닙니다만|으나|는데|하나)[.!?]?$"
	);
	static final String INSUFFICIENT_EVIDENCE_MESSAGE =
		"제공된 근거만으로는 답변을 확정하기 어렵습니다. 관련 법령명이나 문서 범위를 더 구체적으로 입력해 주세요.";
	private static final List<String> STRONG_CLAIM_CUES = List.of(
		"반드시", "항상", "무조건", "필수", "해야", "하여야", "대상입니다", "대상에 해당",
		"대상 사업", "대상사업", "대상은", "대상에 포함",
		"비대상", "제외", "면제", "불필요", "수의계약", "가능합니다", "불가능합니다",
		"받아야", "제공해야", "제출해야", "설치해야", "고지해야", "알려야",
		"위반", "과태료", "벌칙", "기한", "금액", "불이익", "제재", "처분",
		"보완", "예산 조정", "입찰 참가자격", "제한"
	);
	private static final Set<String> FORMAT_ONLY_LABELS = Set.of(
		"결론", "주의", "참고", "안내", "요약", "근거", "답변", "설명", "출처",
		"확인", "검토", "결과", "구분", "항목", "내용", "제목", "목차"
	);

	private final ClaimEvidenceMatcher evidenceMatcher;
	private final ClaimEvidenceAtomizer claimAtomizer = new ClaimEvidenceAtomizer();

	public ClaimVerifier() {
		this(new ClaimEvidenceMatcher());
	}

	@Autowired
	public ClaimVerifier(ClaimEvidenceMatcher evidenceMatcher) {
		this.evidenceMatcher = evidenceMatcher;
	}

	public String verify(String answer, List<LawAiAnswerGround> grounds) {
		return verifyDetailed(answer, grounds).verifiedAnswer();
	}

	public VerificationResult verifyDetailed(String answer, List<LawAiAnswerGround> grounds) {
		if (isInsufficientEvidenceAnswer(answer)) {
			return result(
				INSUFFICIENT_EVIDENCE_MESSAGE,
				false,
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				0,
				0
			);
		}
		if (answer == null || answer.isBlank() || grounds == null || grounds.isEmpty()) {
			return result(
				INSUFFICIENT_EVIDENCE_MESSAGE,
				true,
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				0,
				0
			);
		}
		List<String> sentences = splitSentences(answer);
		List<String> kept = new ArrayList<>();
		List<String> unsupportedClaims = new ArrayList<>();
		List<String> unsupportedNumericClaims = new ArrayList<>();
		List<String> contradictedClaims = new ArrayList<>();
		List<ClaimEvidenceLink> evidenceLinks = new ArrayList<>();
		ClaimEvidenceMatcher.EvidenceIndex evidenceIndex = evidenceMatcher.index(grounds);
		int substantiveClaims = 0;
		int supportedSubstantiveClaims = 0;

		for (String sentence : sentences) {
			if (sentence.isBlank()) {
				continue;
			}
			if (isFormatOnlyStructuralLabel(sentence)) {
				kept.add(sentence);
				continue;
			}
			substantiveClaims++;
			ClaimEvidenceMatcher.Match match = evidenceMatcher.match(sentence, evidenceIndex);
			if (match.status() == ClaimEvidenceMatcher.Status.SUPPORTED) {
				kept.add(sentence);
				supportedSubstantiveClaims++;
				evidenceLinks.add(ClaimEvidenceLink.from(sentence, match));
				continue;
			}
			unsupportedClaims.add(sentence);
			if (match.status() == ClaimEvidenceMatcher.Status.CONTRADICTED
				|| match.status() == ClaimEvidenceMatcher.Status.CONFLICTED) {
				contradictedClaims.add(sentence);
				evidenceLinks.add(ClaimEvidenceLink.from(sentence, match));
			}
			if (!numericClaimsAreSupported(sentence, grounds)) {
				unsupportedNumericClaims.add(sentence);
			}
		}

		if (!contradictedClaims.isEmpty()) {
			return result(
				INSUFFICIENT_EVIDENCE_MESSAGE,
				true,
				unsupportedClaims,
				unsupportedNumericClaims,
				contradictedClaims,
				evidenceLinks,
				substantiveClaims,
				supportedSubstantiveClaims
			);
		}
		if (supportedSubstantiveClaims == 0) {
			return result(
				INSUFFICIENT_EVIDENCE_MESSAGE,
				true,
				unsupportedClaims,
				unsupportedNumericClaims,
				contradictedClaims,
				evidenceLinks,
				substantiveClaims,
				supportedSubstantiveClaims
			);
		}

		String verifiedAnswer = String.join("\n", kept).trim();
		return result(
			verifiedAnswer,
			!verifiedAnswer.equals(answer.trim()),
			unsupportedClaims,
			unsupportedNumericClaims,
			contradictedClaims,
			evidenceLinks,
			substantiveClaims,
			supportedSubstantiveClaims
		);
	}

	private VerificationResult result(
		String verifiedAnswer,
		boolean changed,
		List<String> unsupportedClaims,
		List<String> unsupportedNumericClaims,
		List<String> contradictedClaims,
		List<ClaimEvidenceLink> evidenceLinks,
		int strongClaims,
		int supportedStrongClaims
	) {
		return new VerificationResult(
			verifiedAnswer,
			isInsufficientEvidenceAnswer(verifiedAnswer),
			changed,
			List.copyOf(unsupportedClaims),
			List.copyOf(unsupportedNumericClaims),
			List.copyOf(contradictedClaims),
			List.copyOf(evidenceLinks),
			strongClaims,
			supportedStrongClaims
		);
	}

	boolean isInsufficientEvidenceAnswer(String answer) {
		return INSUFFICIENT_EVIDENCE_MESSAGE.equals(answer == null ? "" : answer.trim());
	}

	private List<String> splitSentences(String answer) {
		return SENTENCE_BOUNDARY.splitAsStream(answer)
			.map(String::trim)
			.filter(value -> !value.isBlank())
			.flatMap(value -> claimAtomizer.atomize(value).stream())
			.toList();
	}

	private boolean isFormatOnlyStructuralLabel(String atom) {
		String label = String.valueOf(atom == null ? "" : atom)
			.trim()
			.replaceFirst("^(?:#{1,6}|[-*+>])\\s*", "")
			.replaceFirst("[:：]\\s*$", "")
			.trim();
		if (label.isBlank()) {
			return true;
		}
		return FORMAT_ONLY_LABELS.contains(normalize(label));
	}

	private boolean numericClaimsAreSupported(String sentence, List<LawAiAnswerGround> grounds) {
		Set<String> claimNumbers = numericParts(sentence);
		if (claimNumbers.isEmpty()) {
			return true;
		}
		Set<String> evidenceNumbers = numericParts(evidenceText(grounds));
		return evidenceNumbers.containsAll(claimNumbers);
	}

	private String evidenceText(List<LawAiAnswerGround> grounds) {
		StringBuilder builder = new StringBuilder();
		for (LawAiAnswerGround ground : grounds) {
			builder.append(' ').append(ground.title());
			builder.append(' ').append(ground.chunkTitle());
			builder.append(' ').append(ground.snippet());
			builder.append(' ').append(ground.matchedChildText());
			builder.append(' ').append(ground.parentContextText());
		}
		return builder.toString();
	}

	private Set<String> numericParts(String text) {
		return ClaimNumericNormalizer.tokens(text);
	}

	private String normalize(String text) {
		return KoreanQueryNormalizer.normalizeForMatch(text == null ? "" : text);
	}

	public record VerificationResult(
		String verifiedAnswer,
		boolean insufficientEvidence,
		boolean changed,
		List<String> unsupportedClaims,
		List<String> unsupportedNumericClaims,
		List<String> contradictedClaims,
		List<ClaimEvidenceLink> evidenceLinks,
		int strongClaimCount,
		int supportedStrongClaimCount
	) {
	}

	public record ClaimEvidenceLink(
		String claim,
		String relation,
		int groundNumber,
		String evidenceSentence,
		int overlapCount,
		double coverage,
		double score
	) {
		static ClaimEvidenceLink from(String claim, ClaimEvidenceMatcher.Match match) {
			return new ClaimEvidenceLink(
				claim,
				match.status().name(),
				match.groundNumber(),
				match.evidenceSentence(),
				match.overlapCount(),
				match.coverage(),
				match.score()
			);
		}
	}
}
