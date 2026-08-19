package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class KoreanEvidenceAtomParser {

	private static final Pattern SUBJECT = Pattern.compile(
		"(?:^|[,.!?;；]\\s*|\\s)([\\p{IsHangul}A-Za-z0-9()·ㆍ/-]{2,}?)(?:은|는|이|가)(?=\\s)"
	);
	private static final Pattern OBJECT = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}A-Za-z0-9()·ㆍ/-]{2,}?)(?:을|를)(?=\\s|[,.!?]|$)"
	);
	private static final Pattern RECIPIENT = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}A-Za-z0-9()·ㆍ/-]{2,}?)(?:에게|한테)(?=\\s|[,.!?]|$)"
	);
	private static final Pattern ACTION = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}A-Za-z0-9]{2,}?)(?:을|를)?\\s*"
			+ "(?:해야|하여야|받아야|할\\s*수|하지\\s*않|한다|된다|합니다|됩니다|한다면|하면)"
	);
	private static final Pattern DUTY_ACTION = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}A-Za-z0-9]{2,}?)(?:할|할\\s*)?\\s*의무"
	);
	private static final Pattern TWO_TERM_CONDITION = Pattern.compile(
		"([\\p{IsHangul}A-Za-z0-9]{2,}?)(?:은|는|이|가|을|를)?\\s*"
			+ "([\\p{IsHangul}A-Za-z0-9]{2,}?)(?:한\\s*경우|된\\s*경우|하면|되면|받으면|있으면|없으면)"
	);
	private static final Pattern POST_CONDITION = Pattern.compile(
		"([\\p{IsHangul}A-Za-z0-9]{2,}?)(?:을|를)?\\s*(?:완료|종료|제출|신청|통지)(?:한|한\\s*)?\\s*후"
	);
	private static final Pattern EXCEPTION = Pattern.compile("(?:다만|예외적으로)\\s*([^.!?]{2,160})");
	private static final Pattern EXCLUDED_SCOPE = Pattern.compile(
		"([\\p{IsHangul}A-Za-z0-9()·ㆍ/-]{2,}?)(?:은|는|이|가)?\\s*(?:대상에서)?\\s*(?:제외|비대상|면제)"
	);
	private static final Pattern INCLUDED_SCOPE = Pattern.compile(
		"([\\p{IsHangul}A-Za-z0-9()·ㆍ/-]{2,}?)(?:은|는|이|가)?\\s*(?:적용대상|대상에\\s*포함|해당)"
	);
	private static final Pattern NUMERIC = Pattern.compile(
		"\\d[\\d,.]*\\s*(?:원|만원|억원|퍼센트|%|개월|시간|년|월|일|점|개|건|명|회|차)"
			+ "(?:\\s*(?:이상|이하|미만|초과|이내|까지))?"
	);
	private static final Pattern DOUBLE_NEGATION = Pattern.compile(
		"(?:않|아니하|없).{0,24}(?:않|아니|없)"
	);

	public EvidenceAtom parse(String sourceText) {
		String source = Normalizer.normalize(String.valueOf(sourceText == null ? "" : sourceText), Normalizer.Form.NFKC)
			.replaceAll("\\s+", " ")
			.trim();
		Set<String> subjects = matches(source, SUBJECT, 1);
		Set<String> objects = matches(source, OBJECT, 1);
		Set<String> recipients = matches(source, RECIPIENT, 1);
		Set<String> actions = matches(source, ACTION, 1);
		actions.addAll(matches(source, DUTY_ACTION, 1));
		Set<String> conditions = new LinkedHashSet<>();
		Matcher conditionMatcher = TWO_TERM_CONDITION.matcher(source);
		while (conditionMatcher.find()) {
			conditions.add(canonical(conditionMatcher.group(1) + conditionMatcher.group(2)));
		}
		conditions.addAll(matches(source, POST_CONDITION, 1));
		Set<String> exceptions = matches(source, EXCEPTION, 1);
		Set<String> scopes = new LinkedHashSet<>();
		matches(source, EXCLUDED_SCOPE, 1).forEach(value -> scopes.add(value + "제외"));
		matches(source, INCLUDED_SCOPE, 1).forEach(value -> scopes.add(value + "포함"));
		for (String exception : exceptions) {
			String excluded = exception.replaceFirst("(?:은|는|이|가)?제외.*$", "");
			if (!excluded.equals(exception) && excluded.length() >= 2) {
				scopes.add(excluded + "제외");
			}
		}
		Set<String> relations = relations(source);
		Set<String> numericAnchors = matches(source, NUMERIC, 0);

		EvidenceAtom.Modality modality = modality(source);
		EvidenceAtom.Polarity polarity = polarity(source, modality);
		List<String> reasons = DOUBLE_NEGATION.matcher(canonical(source)).find()
			? List.of("AMBIGUOUS_DOUBLE_NEGATION")
			: List.of();
		EvidenceAtom.ParseStatus status = !reasons.isEmpty()
			? EvidenceAtom.ParseStatus.AMBIGUOUS
			: !actions.isEmpty() && (!subjects.isEmpty() || !scopes.isEmpty())
				? EvidenceAtom.ParseStatus.COMPLETE
				: EvidenceAtom.ParseStatus.PARTIAL;
		return new EvidenceAtom(
			source, subjects, objects, recipients, actions, relations, scopes, conditions,
			exceptions, numericAnchors, modality, polarity, status, reasons
		);
	}

	private Set<String> matches(String source, Pattern pattern, int group) {
		Set<String> values = new LinkedHashSet<>();
		Matcher matcher = pattern.matcher(source);
		while (matcher.find()) {
			String value = canonical(matcher.group(group));
			if (value.length() >= 2) {
				values.add(value);
			}
		}
		return values;
	}

	private Set<String> relations(String source) {
		String normalized = canonical(source);
		Set<String> relations = new LinkedHashSet<>();
		for (String relation : List.of("포함", "제외", "해당", "귀속", "부담", "허용", "금지", "신고", "통지")) {
			if (normalized.contains(relation)) {
				relations.add(relation);
			}
		}
		return relations;
	}

	private EvidenceAtom.Modality modality(String source) {
		String normalized = canonical(source);
		if (containsAny(normalized, "의무가없", "의무는없", "요구되지않")) {
			return EvidenceAtom.Modality.REQUIRED;
		}
		if (containsAny(normalized, "할수없", "금지", "불가능", "허용되지않")) {
			return EvidenceAtom.Modality.PROHIBITED;
		}
		if (containsAny(normalized, "해야", "하여야", "받아야", "의무", "필수")) {
			return EvidenceAtom.Modality.REQUIRED;
		}
		if (containsAny(normalized, "할수있", "가능", "허용")) {
			return EvidenceAtom.Modality.PERMITTED;
		}
		if (containsAny(normalized, "하지않아도", "생략할수")) {
			return EvidenceAtom.Modality.OPTIONAL;
		}
		return EvidenceAtom.Modality.UNSPECIFIED;
	}

	private EvidenceAtom.Polarity polarity(String source, EvidenceAtom.Modality modality) {
		String normalized = canonical(source);
		if (containsAny(normalized, "의무가없", "아니다", "않는다", "하지않", "제외", "비대상")) {
			return EvidenceAtom.Polarity.NEGATIVE;
		}
		return source.isBlank() && modality == EvidenceAtom.Modality.UNSPECIFIED
			? EvidenceAtom.Polarity.UNSPECIFIED
			: EvidenceAtom.Polarity.POSITIVE;
	}

	private boolean containsAny(String text, String... values) {
		for (String value : values) {
			if (text.contains(canonical(value))) {
				return true;
			}
		}
		return false;
	}

	private String canonical(String value) {
		return KoreanQueryNormalizer.normalizeForMatch(String.valueOf(value == null ? "" : value))
			.replaceAll("(?:은|는|이|가|을|를)$", "")
			.replaceAll("(?:한경우|된경우|하면|되면|받으면|있으면|없으면)$", "");
	}
}
