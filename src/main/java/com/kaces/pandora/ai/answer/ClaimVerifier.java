package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ClaimVerifier {

	private static final int MIN_STRONG_CLAIM_OVERLAP = 2;
	private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+|\\n+");
	private static final Pattern NUMBER_OR_DATE_CLAIM = Pattern.compile(
		"\\d[\\d,]*(?:\\.\\d+)?\\s*(?:년|월|일|개월|일내|일 이내|점|%|퍼센트|원|만원|억원|개|건|명|회|차|시간)?"
	);
	private static final Pattern NUMBER_PART = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?");
	static final String INSUFFICIENT_EVIDENCE_MESSAGE =
		"제공된 근거만으로는 답변을 확정하기 어렵습니다. 관련 법령명이나 문서 범위를 더 구체적으로 입력해 주세요.";
	private static final Set<String> STOPWORDS = Set.of(
		"그리고", "그러나", "다만", "또한", "해당", "경우", "관련", "기준", "내용", "문서", "근거",
		"확인", "필요", "가능", "여부", "질문", "답변", "합니다", "됩니다", "있습니다", "없습니다"
	);
	private static final List<String> STRONG_CLAIM_CUES = List.of(
		"반드시", "항상", "무조건", "필수", "해야", "하여야", "대상입니다", "대상에 해당",
		"대상 사업", "대상사업", "대상은", "대상에 포함",
		"비대상", "제외", "면제", "불필요", "수의계약", "가능합니다", "불가능합니다",
		"받아야", "제공해야", "제출해야", "설치해야", "고지해야", "알려야",
		"위반", "과태료", "벌칙", "기한", "금액", "불이익", "제재", "처분",
		"보완", "예산 조정", "입찰 참가자격", "제한"
	);
	private static final List<String> CAUTION_CUES = List.of(
		"근거상", "확인되지", "확인할 필요", "확인이 필요", "확인해야", "추가 확인",
		"별도 확인", "문서에 불충분", "문서상 불충분", "근거문서들만으로는", "명확하지",
		"어렵습니다", "판단해야", "달라질 수", "확정하기 어렵"
	);

	public String verify(String answer, List<LawAiAnswerGround> grounds) {
		return verifyDetailed(answer, grounds).verifiedAnswer();
	}

	public VerificationResult verifyDetailed(String answer, List<LawAiAnswerGround> grounds) {
		if (answer == null || answer.isBlank() || grounds == null || grounds.isEmpty()) {
			return VerificationResult.unchanged(answer);
		}
		Set<String> evidenceTerms = evidenceTerms(grounds);
		if (evidenceTerms.isEmpty()) {
			return VerificationResult.unchanged(answer);
		}
		String evidenceText = evidenceText(grounds);
		List<String> sentences = splitSentences(answer);

		List<String> kept = new ArrayList<>();
		List<String> unsupportedClaims = new ArrayList<>();
		List<String> unsupportedNumericClaims = new ArrayList<>();
		int strongClaims = 0;
		int supportedStrongClaims = 0;
		for (String sentence : sentences) {
			if (sentence.isBlank()) {
				continue;
			}
			if (!isStrongClaim(sentence) || isCautious(sentence)) {
				kept.add(sentence);
				continue;
			}
			strongClaims++;
			if (isSupportedStrongClaim(sentence, evidenceTerms, evidenceText)) {
				kept.add(sentence);
				supportedStrongClaims++;
			} else {
				unsupportedClaims.add(sentence);
				if (!numericClaimsAreSupported(sentence, evidenceText)) {
					unsupportedNumericClaims.add(sentence);
				}
			}
		}
		if (strongClaims == 0) {
			return new VerificationResult(answer, false, false, List.of(), List.of(), 0, 0);
		}
		if (supportedStrongClaims == 0 && kept.isEmpty()) {
			return new VerificationResult(
				INSUFFICIENT_EVIDENCE_MESSAGE,
				true,
				true,
				List.copyOf(unsupportedClaims),
				List.copyOf(unsupportedNumericClaims),
				strongClaims,
				supportedStrongClaims
			);
		}
		if (kept.isEmpty()) {
			return new VerificationResult(
				INSUFFICIENT_EVIDENCE_MESSAGE,
				true,
				true,
				List.copyOf(unsupportedClaims),
				List.copyOf(unsupportedNumericClaims),
				strongClaims,
				supportedStrongClaims
			);
		}
		String verifiedAnswer = String.join("\n", kept).trim();
		return new VerificationResult(
			verifiedAnswer,
			isInsufficientEvidenceAnswer(verifiedAnswer),
			!verifiedAnswer.equals(answer.trim()),
			List.copyOf(unsupportedClaims),
			List.copyOf(unsupportedNumericClaims),
			strongClaims,
			supportedStrongClaims
		);
	}

	boolean isInsufficientEvidenceAnswer(String answer) {
		return INSUFFICIENT_EVIDENCE_MESSAGE.equals(answer == null ? "" : answer.trim());
	}

	private Set<String> evidenceTerms(List<LawAiAnswerGround> grounds) {
		Set<String> terms = new LinkedHashSet<>();
		for (LawAiAnswerGround ground : grounds) {
			addTerms(terms, ground.title());
			addTerms(terms, ground.chunkTitle());
			addTerms(terms, ground.snippet());
			addTerms(terms, ground.matchedChildText());
			addTerms(terms, ground.parentContextText());
		}
		return terms;
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
		return normalizeForTokenize(builder.toString());
	}

	private void addTerms(Set<String> terms, String text) {
		for (String token : tokenize(text)) {
			terms.add(token);
		}
	}

	private List<String> splitSentences(String answer) {
		return SENTENCE_BOUNDARY.splitAsStream(answer)
			.map(String::trim)
			.filter(value -> !value.isBlank())
			.toList();
	}

	private boolean isStrongClaim(String sentence) {
		String normalized = normalize(sentence);
		if (NUMBER_OR_DATE_CLAIM.matcher(sentence).find()) {
			return true;
		}
		return STRONG_CLAIM_CUES.stream()
			.map(this::normalize)
			.anyMatch(cue -> !cue.isBlank() && normalized.contains(cue));
	}

	private boolean isCautious(String sentence) {
		String normalized = normalize(sentence);
		return CAUTION_CUES.stream()
			.map(this::normalize)
			.anyMatch(cue -> !cue.isBlank() && normalized.contains(cue));
	}

	private boolean isSupportedStrongClaim(String sentence, Set<String> evidenceTerms, String evidenceText) {
		if (!numericClaimsAreSupported(sentence, evidenceText)) {
			return false;
		}
		return overlapCount(sentence, evidenceTerms) >= MIN_STRONG_CLAIM_OVERLAP;
	}

	private boolean numericClaimsAreSupported(String sentence, String evidenceText) {
		Set<String> claimNumbers = numericParts(sentence);
		if (claimNumbers.isEmpty()) {
			return true;
		}
		Set<String> evidenceNumbers = numericParts(evidenceText);
		for (String number : claimNumbers) {
			if (!evidenceNumbers.contains(number) && !evidenceText.contains(number)) {
				return false;
			}
		}
		return true;
	}

	private List<String> numericTokens(String sentence) {
		Matcher matcher = NUMBER_OR_DATE_CLAIM.matcher(String.valueOf(sentence == null ? "" : sentence));
		List<String> result = new ArrayList<>();
		while (matcher.find()) {
			String value = matcher.group();
			if (value != null && !value.isBlank()) {
				result.add(value.trim());
			}
		}
		return result;
	}

	private Set<String> numericParts(String text) {
		Matcher matcher = NUMBER_PART.matcher(String.valueOf(text == null ? "" : text));
		Set<String> result = new LinkedHashSet<>();
		while (matcher.find()) {
			String value = matcher.group();
			if (value != null && !value.isBlank()) {
				result.add(value.replace(",", "").trim());
			}
		}
		return result;
	}

	private int overlapCount(String sentence, Set<String> evidenceTerms) {
		int count = 0;
		for (String token : tokenize(sentence)) {
			if (matchesEvidenceTerm(token, evidenceTerms)) {
				count++;
			}
		}
		return count;
	}

	private boolean matchesEvidenceTerm(String token, Set<String> evidenceTerms) {
		if (token == null || token.isBlank() || evidenceTerms == null || evidenceTerms.isEmpty()) {
			return false;
		}
		if (evidenceTerms.contains(token)) {
			return true;
		}
		for (String evidenceTerm : evidenceTerms) {
			if (evidenceTerm.length() < 3 || token.length() < 3) {
				continue;
			}
			if (token.contains(evidenceTerm) || evidenceTerm.contains(token)) {
				return true;
			}
		}
		return false;
	}

	private List<String> tokenize(String text) {
		String normalized = normalizeForTokenize(text);
		if (normalized.isBlank()) {
			return List.of();
		}
		String[] rawTokens = normalized.split("[^0-9a-z가-힣]+");
		List<String> tokens = new ArrayList<>();
		for (String rawToken : rawTokens) {
			String token = rawToken == null ? "" : rawToken.trim();
			if (token.length() < 2 || STOPWORDS.contains(token)) {
				continue;
			}
			tokens.add(token);
		}
		return tokens;
	}

	private String normalize(String text) {
		return KoreanQueryNormalizer.normalizeForMatch(text == null ? "" : text);
	}

	private String normalizeForTokenize(String text) {
		return String.valueOf(text == null ? "" : text)
			.replaceAll("[^\\p{IsHangul}\\p{Alnum}]+", " ")
			.toLowerCase()
			.trim();
	}

	public record VerificationResult(
		String verifiedAnswer,
		boolean insufficientEvidence,
		boolean changed,
		List<String> unsupportedClaims,
		List<String> unsupportedNumericClaims,
		int strongClaimCount,
		int supportedStrongClaimCount
	) {
		static VerificationResult unchanged(String answer) {
			return new VerificationResult(answer, false, false, List.of(), List.of(), 0, 0);
		}
	}
}
