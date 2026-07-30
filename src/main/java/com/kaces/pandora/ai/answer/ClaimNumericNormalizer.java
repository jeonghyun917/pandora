package com.kaces.pandora.ai.answer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts numeric claim fragments into comparable value-and-unit tokens.
 * Unqualified numbers are retained so article numbers and identifiers continue
 * to match, while qualified values only match the same unit and value.
 */
final class ClaimNumericNormalizer {

	private static final String NUMBER = "\\d+(?:,\\d{3})*(?:\\.\\d+)?";
	private static final String KOREAN_MONEY_SCALE = "(?:천만|백만|십만|억|만|천|백|십)";
	private static final Pattern KOREAN_DATE = Pattern.compile(
		"(?<!\\d)(\\d{4})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일"
	);
	private static final Pattern DOTTED_DATE = Pattern.compile(
		"(?<!\\d)(\\d{4})\\s*[.]\\s*(\\d{1,2})\\s*[.]\\s*(\\d{1,2})(?!\\d)"
	);
	private static final Pattern SCALED_WON = Pattern.compile(
		"(?<![\\d,.])((?:" + NUMBER + "\\s*" + KOREAN_MONEY_SCALE + "\\s*)+(?:" + NUMBER + "\\s*)?원)"
	);
	private static final Pattern MONEY_COMPONENT = Pattern.compile(
		"(" + NUMBER + ")\\s*(천만|백만|십만|억|만|천|백|십)"
	);
	private static final Pattern MONEY_TRAILING_NUMBER = Pattern.compile("(" + NUMBER + ")\\s*원$");
	private static final Pattern WON = Pattern.compile("(?<![\\d,.])(" + NUMBER + ")\\s*원");
	private static final Pattern VALUE_WITH_UNIT = Pattern.compile(
		"(?<![\\d,.])(" + NUMBER + ")\\s*(퍼센트|%|개월|시간|년|월|일|점|개|건|명|회|차|단계)"
	);
	private static final Pattern ANY_NUMBER = Pattern.compile(NUMBER);
	private static final Pattern NUMERIC_BOUND = Pattern.compile(
		"^\\s*(?:을|를)?\\s*"
			+ "(이상|이하|미만|초과|넘지\\s*않는|넘는|보다\\s*(?:큰|많은|작은|적은))"
	);

	private ClaimNumericNormalizer() {
	}

	static Set<String> tokens(String text) {
		return parse(text).uniqueTokens();
	}

	static List<String> orderedTokens(String text) {
		return parse(text).orderedTokens();
	}

	private static NumericTokens parse(String text) {
		String source = String.valueOf(text == null ? "" : text);
		Set<String> tokens = new LinkedHashSet<>();
		List<PositionedToken> positionedTokens = new ArrayList<>();
		BitSet qualifiedCharacters = new BitSet(source.length());

		addKoreanDates(source, tokens, positionedTokens);
		addDottedDates(source, tokens, positionedTokens, qualifiedCharacters);
		addScaledWon(source, tokens, positionedTokens, qualifiedCharacters);
		addSimpleWon(source, tokens, positionedTokens, qualifiedCharacters);
		addUnitValues(source, tokens, positionedTokens, qualifiedCharacters);
		addUnqualifiedNumbers(source, tokens, positionedTokens, qualifiedCharacters);
		List<String> orderedTokens = positionedTokens.stream()
			.sorted(Comparator.comparingInt(PositionedToken::start))
			.map(PositionedToken::token)
			.toList();
		return new NumericTokens(Set.copyOf(tokens), List.copyOf(orderedTokens));
	}

	private static void addKoreanDates(
		String source,
		Set<String> tokens,
		List<PositionedToken> positionedTokens
	) {
		Matcher matcher = KOREAN_DATE.matcher(source);
		while (matcher.find()) {
			addDateToken(tokens, positionedTokens, matcher.start(1), matcher.group(1), matcher.group(2), matcher.group(3));
		}
	}

	private static void addDottedDates(
		String source,
		Set<String> tokens,
		List<PositionedToken> positionedTokens,
		BitSet qualifiedCharacters
	) {
		Matcher matcher = DOTTED_DATE.matcher(source);
		while (matcher.find()) {
			addDateToken(tokens, positionedTokens, matcher.start(1), matcher.group(1), matcher.group(2), matcher.group(3));
			addToken(tokens, positionedTokens, matcher.start(1), "year", matcher.group(1), "");
			addToken(tokens, positionedTokens, matcher.start(2), "month", matcher.group(2), "");
			addToken(tokens, positionedTokens, matcher.start(3), "day", matcher.group(3), "");
			markNumberGroups(matcher, qualifiedCharacters, 1, 2, 3);
		}
	}

	private static void addDateToken(
		Set<String> tokens,
		List<PositionedToken> positionedTokens,
		int start,
		String rawYear,
		String rawMonth,
		String rawDay
	) {
		String token = "date:"
			+ canonical(decimal(rawYear)) + "-"
			+ canonical(decimal(rawMonth)) + "-"
			+ canonical(decimal(rawDay));
		tokens.add(token);
		positionedTokens.add(new PositionedToken(start, token));
	}

	private static void addScaledWon(
		String source,
		Set<String> tokens,
		List<PositionedToken> positionedTokens,
		BitSet qualifiedCharacters
	) {
		Matcher expressionMatcher = SCALED_WON.matcher(source);
		while (expressionMatcher.find()) {
			String expression = expressionMatcher.group(1);
			Matcher componentMatcher = MONEY_COMPONENT.matcher(expression);
			BigDecimal won = BigDecimal.ZERO;
			BigDecimal minorGroup = BigDecimal.ZERO;
			while (componentMatcher.find()) {
				BigDecimal value = decimal(componentMatcher.group(1));
				String scale = componentMatcher.group(2);
				if (isMinorMoneyScale(scale)) {
					minorGroup = minorGroup.add(value.multiply(moneyFactor(scale)));
				} else if (isCompoundManScale(scale) && minorGroup.signum() != 0) {
					String minorScale = scale.substring(0, scale.length() - 1);
					minorGroup = minorGroup.add(value.multiply(moneyFactor(minorScale)));
					won = won.add(minorGroup.multiply(moneyFactor("만")));
					minorGroup = BigDecimal.ZERO;
				} else if (isGroupingMoneyScale(scale)) {
					won = won.add(minorGroup.add(value).multiply(moneyFactor(scale)));
					minorGroup = BigDecimal.ZERO;
				} else {
					won = won.add(minorGroup).add(value.multiply(moneyFactor(scale)));
					minorGroup = BigDecimal.ZERO;
				}
				int numberStart = expressionMatcher.start(1) + componentMatcher.start(1);
				int numberEnd = expressionMatcher.start(1) + componentMatcher.end(1);
				qualifiedCharacters.set(numberStart, numberEnd);
			}
			Matcher trailingMatcher = MONEY_TRAILING_NUMBER.matcher(expression);
			BigDecimal trailingWon = BigDecimal.ZERO;
			if (trailingMatcher.find()) {
				trailingWon = decimal(trailingMatcher.group(1));
				int numberStart = expressionMatcher.start(1) + trailingMatcher.start(1);
				int numberEnd = expressionMatcher.start(1) + trailingMatcher.end(1);
				qualifiedCharacters.set(numberStart, numberEnd);
			}
			won = won.add(minorGroup).add(trailingWon);
			String token = "money:" + canonical(won) + boundSuffix(source, expressionMatcher.end(1));
			tokens.add(token);
			positionedTokens.add(new PositionedToken(expressionMatcher.start(1), token));
		}
	}

	private static boolean isMinorMoneyScale(String scale) {
		return "천".equals(scale) || "백".equals(scale) || "십".equals(scale);
	}

	private static boolean isGroupingMoneyScale(String scale) {
		return "억".equals(scale) || "만".equals(scale);
	}

	private static boolean isCompoundManScale(String scale) {
		return "천만".equals(scale) || "백만".equals(scale) || "십만".equals(scale);
	}

	private static void addSimpleWon(
		String source,
		Set<String> tokens,
		List<PositionedToken> positionedTokens,
		BitSet qualifiedCharacters
	) {
		Matcher matcher = WON.matcher(source);
		while (matcher.find()) {
			if (isQualified(qualifiedCharacters, matcher.start(1), matcher.end(1))) {
				continue;
			}
			addToken(
				tokens,
				positionedTokens,
				matcher.start(1),
				"money",
				matcher.group(1),
				boundSuffix(source, matcher.end())
			);
			qualifiedCharacters.set(matcher.start(1), matcher.end(1));
		}
	}

	private static void addUnitValues(
		String source,
		Set<String> tokens,
		List<PositionedToken> positionedTokens,
		BitSet qualifiedCharacters
	) {
		Matcher matcher = VALUE_WITH_UNIT.matcher(source);
		while (matcher.find()) {
			if (isQualified(qualifiedCharacters, matcher.start(1), matcher.end(1))) {
				continue;
			}
			addToken(
				tokens,
				positionedTokens,
				matcher.start(1),
				unitKey(matcher.group(2)),
				matcher.group(1),
				boundSuffix(source, matcher.end())
			);
			qualifiedCharacters.set(matcher.start(1), matcher.end(1));
		}
	}

	private static void addUnqualifiedNumbers(
		String source,
		Set<String> tokens,
		List<PositionedToken> positionedTokens,
		BitSet qualifiedCharacters
	) {
		Matcher matcher = ANY_NUMBER.matcher(source);
		while (matcher.find()) {
			if (!isQualified(qualifiedCharacters, matcher.start(), matcher.end())) {
				addToken(
					tokens,
					positionedTokens,
					matcher.start(),
					"number",
					matcher.group(),
					boundSuffix(source, matcher.end())
				);
			}
		}
	}

	private static void addToken(
		Set<String> tokens,
		List<PositionedToken> positionedTokens,
		int start,
		String unit,
		String rawValue,
		String suffix
	) {
		String token = unit + ":" + canonical(decimal(rawValue)) + suffix;
		tokens.add(token);
		positionedTokens.add(new PositionedToken(start, token));
	}

	private static String boundSuffix(String source, int valueEnd) {
		Matcher matcher = NUMERIC_BOUND.matcher(source.substring(Math.min(valueEnd, source.length())));
		if (!matcher.find()) {
			return "";
		}
		String bound = matcher.group(1).replaceAll("\\s+", "");
		return switch (bound) {
			case "이상" -> ":gte";
			case "이하", "넘지않는" -> ":lte";
			case "미만", "보다작은", "보다적은" -> ":lt";
			case "초과", "넘는", "보다큰", "보다많은" -> ":gt";
			default -> throw new IllegalArgumentException("Unsupported numeric bound: " + bound);
		};
	}

	private static void markNumberGroups(Matcher matcher, BitSet qualifiedCharacters, int... groups) {
		for (int group : groups) {
			qualifiedCharacters.set(matcher.start(group), matcher.end(group));
		}
	}

	private static boolean isQualified(BitSet qualifiedCharacters, int start, int end) {
		int qualified = qualifiedCharacters.nextSetBit(start);
		return qualified >= start && qualified < end;
	}

	private static String unitKey(String unit) {
		return switch (unit) {
			case "%", "퍼센트" -> "percent";
			case "년" -> "year";
			case "개월" -> "duration-month";
			case "월" -> "month";
			case "일" -> "day";
			case "점" -> "point";
			case "개" -> "item-count";
			case "건" -> "case-count";
			case "명" -> "person-count";
			case "회" -> "occurrence-count";
			case "차" -> "sequence-count";
			case "단계" -> "stage";
			case "시간" -> "hour";
			default -> throw new IllegalArgumentException("Unsupported numeric unit: " + unit);
		};
	}

	private static BigDecimal moneyFactor(String scale) {
		return switch (scale) {
			case "억" -> new BigDecimal("100000000");
			case "천만" -> new BigDecimal("10000000");
			case "백만" -> new BigDecimal("1000000");
			case "십만" -> new BigDecimal("100000");
			case "만" -> new BigDecimal("10000");
			case "천" -> new BigDecimal("1000");
			case "백" -> new BigDecimal("100");
			case "십" -> BigDecimal.TEN;
			default -> throw new IllegalArgumentException("Unsupported money scale: " + scale);
		};
	}

	private static BigDecimal decimal(String rawValue) {
		return new BigDecimal(rawValue.replace(",", ""));
	}

	private static String canonical(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	private record PositionedToken(int start, String token) {
	}

	private record NumericTokens(Set<String> uniqueTokens, List<String> orderedTokens) {
	}
}
