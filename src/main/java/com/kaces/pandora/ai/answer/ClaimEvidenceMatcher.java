package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Matches one answer claim to one concrete evidence sentence. Sentence-local
 * matching prevents unrelated terms from separate grounds becoming artificial
 * support for a claim.
 */
@Component
public class ClaimEvidenceMatcher {

	private static final int MIN_OVERLAP = 2;
	private static final double MIN_COVERAGE = 0.34d;
	private static final Pattern LEADING_CONCESSIVE_FRAME = Pattern.compile(
		"^\\s*.{1,120}?(?:이)?라도\\s+(.+)$",
		Pattern.DOTALL
	);
	private static final Pattern LEADING_SUMMARY_DISCOURSE_FRAME = Pattern.compile(
		"^\\s*(?:(?:결론|요점|핵심)(?:만|부터)?|한마디로)\\s*"
			+ "(?:먼저\\s*)?(?:말하면|말하자면|정리하면|요약하면)[,，:]?\\s*",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern PERMISSION_ACTION = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)(?:은|는|이|가|을|를)?\\s*"
			+ "(?:할\\s*수\\s*(?:있|없)|(?:불)?가능)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern SUBJECT_ROLE = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)(?:은|는|이|가)(?=\\s|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern LABELED_SUBJECT_ROLE = Pattern.compile(
		"(?:^|[.!?;；\\n]\\s*)"
			+ "([\\p{IsHangul}a-z0-9][\\p{IsHangul}a-z0-9()·ㆍ/\\-\\s]{1,80}?)"
			+ "(?=\\s*[:：])",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern COORDINATED_SUBJECT_ROLE = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)"
			+ "(?:(?:·|ㆍ|와|과|하고)\\s*|\\s+(?:및|또는|하고)\\s+)"
			+ "([\\p{IsHangul}a-z0-9]{2,})(?:은|는|이|가)(?=\\s|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern PARENTHETICAL_SUBJECT_ROLE = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,})\\([^)]{1,80}\\)(?:은|는|이|가)(?=\\s|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern OBJECT_ROLE = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)(?:을|를)"
			+ "(?!\\s*(?:위하여|위해))(?=\\s|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern COORDINATED_OBJECT_ROLE = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)"
			+ "(?:(?:·|ㆍ|와|과|하고)\\s*|\\s+(?:및|또는|하고)\\s+)"
			+ "([\\p{IsHangul}a-z0-9]{2,})(?:을|를)"
			+ "(?!\\s*(?:위하여|위해))(?=\\s|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern PROHIBITION_OBJECT_ROLE = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)(?:을|를)?\\s+금지(?:를|을)?(?=\\s|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern PROHIBITION_REQUEST_OBJECT = Pattern.compile(
		"금지(?:를|을)?"
			+ "(?:(?:바로|직접|즉시|곧바로|우선|먼저|별도로|재차|다시))?"
			+ "(?:신청|요청|청구|요구)(?:을|를)?"
			+ "(?:할수|가능|해야|하여야)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern PERMISSION_TARGET_ROLE = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)(?:을|를)?\\s+(?:금지|허용)"
			+ "(?=\\s|되|됩|된|될|됨|하|합|한|할|해|했|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern RECIPIENT_ROLE = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)(?:에게|한테)(?=\\s|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern EXCLUSIVE_ROLE_ANCHOR = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9()·ㆍ/\\-]{2,}?)(?:(에게|한테)만|만(?:을|를)?)(?=\\s|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern GENERIC_TARGET_DEFINITION_LABEL = Pattern.compile(
		"^적용대상(?:은|는|이|가).*(?:입니다|이다|임)$"
	);
	private static final Pattern ATTRIBUTIVE_OBJECT_SEQUENCE = Pattern.compile(
		"(?:^|\\s)[\\p{IsHangul}a-z0-9()·ㆍ/\\-]{2,}?(?:이|가)\\s+"
			+ "[^.!?;；\\n]{0,100}?(?:한|하는|할|된|되는|될|던)\\s+"
			+ "[^.!?;；\\n]{0,60}?(?:을|를|임|입니다|이다)(?=\\s|[.!?]|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern ATTRIBUTIVE_VERB_ROLE_TOKEN = Pattern.compile(
		"(?:하|되|있|없|받)(?:은|는)$"
	);
	private static final Pattern RESPONSIBILITY_RECIPIENT = Pattern.compile(
		"(?:입증)?책임(?:은|는|이|가)?\\s*"
			+ "([\\p{IsHangul}a-z0-9]{2,}?)(?:에게|한테)\\s*(?:있|귀속|부과)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern RESPONSIBILITY_ACTOR = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)(?:은|는|이|가)\\s+"
			+ "(?:(?![\\p{IsHangul}a-z0-9]{2,}?(?:은|는|이|가)\\s+)"
			+ "[^.!?\\n]){0,80}?"
			+ "(?:입증(?:하여야|해야|합니다|한다)"
			+ "|(?:입증)?책임(?:을|를)?\\s*(?:부담|집니다|진다|진))",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern RESPONSIBILITY_POSSESSOR = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)의\\s*(?:입증)?책임"
			+ "(?=[.!?,\\s]|(?:입니다|이다|임)|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern ADDITIVE_RELATION_ANCHOR = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)도(?=\\s|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern EXPLICIT_RELATION_CUE_ANCHOR = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{3,}?)(?:도|와|과|을|를|이|가|은|는|의)?\\s+"
			+ "(?:함께|같이|동시에|병행(?:하여|해서)?|연계(?:하여|해서)?|관계|연관)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern RELATION_TOPIC_ANCHOR = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)(?:은|는|이|가)\\s+[^.!?\\n]{0,80}?"
			+ "(?:함께|같이|동시에|병행(?:하여|해서)?|연계(?:하여|해서)?|관계|연관)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern NUMERIC_NARROWING_ANCHOR = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z][\\p{IsHangul}a-z0-9]{1,}?)(?:은|는|이|가|의)?\\s+"
			+ "\\d[\\d,.]*(?:\\s*(?:원|만원|억원|퍼센트|%|개월|시간|년|월|일|점|개|건|명|회|차))?"
			+ "(?:을|를)?\\s*(?:이상|이하|미만|초과|넘는|넘지\\s*않는|보다\\s*(?:큰|작은|많은|적은))",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern SUFFIX_CONDITION_ANCHOR = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)(?:이면|라면|일때)(?=\\s|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern VERB_ENDING_CONDITION_ANCHOR = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)"
			+ "(?:한다면|된다면|하면|되면|받으면|있으면|없으면|않으면|지나면|넘으면)"
			+ "(?=\\s|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern DOCUMENT_IDENTITY_DESCRIPTOR_REMAINDER = Pattern.compile(
		"(?:(?:결론부터|요약하면|정리하면)(?:말씀드리면|말하면)?)?"
			+ "(?:찾으시는|요청하신|해당)?"
			+ "(?:문서|문서명|문서제목)(?:은|는|이|가)?"
			+ "제목(?:은|는|이|가)?인"
			+ "([\\p{IsHangul}a-z0-9]{2,40})"
			+ "문서(?:입니다|이다|임)?",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern POST_EVENT_CONDITION = Pattern.compile(
		"[\\p{IsHangul}]{2,}(?:한|된|받은|마친)후(?:에는|에|부터)?"
	);
	private static final Pattern CASE_CONDITION_PREFIX = Pattern.compile(
		"(?:^|[.!?]\\s*)([^.!?\\n]{2,80}?)\\s+(?:경우|때)(?:에는|엔|에|는)?(?=\\s|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern NAMED_CONDITION_PREFIX = Pattern.compile(
		"(?:^|[.!?;；,\\n]\\s*)([^.!?;；,\\n]{2,60}?)\\s+(?:요건|조건)(?:을|를)?\\s*"
			+ "(?:충족|만족|갖춘|갖추)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern PURPOSE_CONDITION_PREFIX = Pattern.compile(
		"(?:^|[.!?;；,\\n]\\s*)([^.!?;；,\\n]{2,80}?)\\s+(?:위하여|위해)(?=\\s|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern ATTRIBUTIVE_SCOPE_CONDITION = Pattern.compile(
		"(?:^|[.!?;；,，\\n]\\s*)([^.!?;；,，\\n]{2,80}?)\\s*"
			+ "(?:으?로|에서|부터|에\\s*따라)\\s+"
			+ "(?:추진|수행|운영|구축|개발|조달|구매|제공|수집|처리|생산)"
			+ "(?:하|되)(?:는|ㄴ|은)(?=\\s)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern AFFIRMED_PREDICATE_ACTION = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{1,}?)(?:을|를)?\\s*"
			+ "(?:(?:합니다|한다|함|됩니다|된다|됨|습니다|는다)"
			+ "|(?:하|되)?고\\s*있(?:습니다|다|음)"
			+ "|하고|하되|되며)(?=[.!?\\s]|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern AFFIRMED_NOMINAL_PREDICATE_ACTION = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)(?:을|를|이|가)?\\s+"
			+ "(?:원칙|의무|방침)(?:에\\s*따라|으로\\s*(?:합니다|한다|정합니다|정한다)|입니다|이다)"
			+ "(?=[.!?\\s]|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern NEGATED_PREDICATE_ACTION = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{1,}?)(?:을|를)?\\s*"
			+ "(?:하|되)?지\\s*(?:는\\s*)?"
			+ "(?:않(?:습니다|는다|음|다|고\\s*있(?:습니다|다|음))"
			+ "|못(?:합니다|한다|함|하고\\s*있(?:습니다|다|음)))"
			+ "(?=[.!?\\s]|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern PLANNED_PREDICATE_ACTION = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{1,}?)(?:을|를)?\\s*"
			+ "(?:할\\s*)?(?:예정|계획)(?:입니다|이다|임)(?=[.!?\\s]|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern ONGOING_PREDICATE_ACTION = Pattern.compile(
		"(?:^|\\s)([\\p{IsHangul}a-z0-9]{1,}?)(?:을|를)?\\s+"
			+ "중(?:입니다|이다|임)(?=[.!?\\s]|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern OPEN_ENDED_ENUMERATION = Pattern.compile(
		"([^.!?;；\\n]{1,240}?(?:[·ㆍ/,]|\\s+(?:및|또는)\\s+)"
			+ "[^.!?;；\\n]{1,160}?)\\s+등"
			+ "(?:입니다|이다|임|의|은|는|이|가|을|를|에|에서|으로|과|와|도|만)?"
			+ "(?=\\s|[,.!?]|$)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern ARTICLE_HEADING_ONLY = Pattern.compile(
		"^\\s*제\\s*\\d+\\s*조(?:의\\s*\\d+)?\\s*\\([^\\r\\n]{1,100}\\)\\s*$"
	);
	private static final Pattern SHORT_NOMINAL_HEADING_ONLY = Pattern.compile(
		"^[\\p{IsHangul}A-Za-z0-9()·ㆍ/\\-\\s]{2,48}"
			+ "(?:대상|비대상|제외|면제|절차|기준|방법|시기|범위|개요|목적|역할|현황|결과"
			+ "|검토내용|신청서|결과서)$"
	);
	private static final Pattern ASSERTIVE_EVIDENCE_CUE = Pattern.compile(
		"(?:하여야|해야)\\s*(?:합니다|한다)"
			+ "|할\\s*수\\s*(?:있|없)(?:습니다|다)"
			+ "|(?:입니다|합니다|됩니다|있습니다|없습니다|아닙니다"
			+ "|한다|된다|있다|없다|아니다|않는다|함|됨|이며|이고|되며|하되)"
			+ "(?=[.!?,\\s]|$)"
	);
	private static final Pattern FINITE_ASSERTIVE_ENDING = Pattern.compile(
		"[\\p{IsHangul}]+(?:니다|다|함|됨|임)(?=[.!?\\s]*$)"
	);
	private static final String STRUCTURAL_SOURCE_MARKER =
		"(?:(?:(?:(?:[Ⅰ-Ⅻ]+|\\d{1,2})[.)．]|[①-⑳•‣□○※])\\s*)?"
			+ "(?:목\\s*차|개요|차\\s*례|(?i:contents))"
			+ "|[\\[【(]\\s*(?:목\\s*차|개요|차\\s*례|(?i:contents))\\s*[\\]】)])";
	private static final Pattern STRUCTURAL_SELF_ASSERTION = Pattern.compile(
		"^\\s*" + STRUCTURAL_SOURCE_MARKER + "(?:"
			+ "(?:은|는)?\\s*(?:입니다|이다|임)(?=[.!?,\\s]|$)"
			+ "|(?:은|는)?\\s*(?:다음과|아래와)\\s*같(?:습니다|다|음)(?=[.!?,\\s]|$)"
			+ "|\\s*[.:：]?\\s*(?=\\r?\\n|$)"
			+ ")"
	);
	private static final Pattern STRUCTURAL_LABEL = Pattern.compile(
		"목\\s*차|개요|차\\s*례|(?i:contents)|대상\\s*사업(?:\\s*및\\s*시기)?|추진체계|역할|추진절차|검토내용"
			+ "|별첨|참조|신청서|결과서"
	);
	private static final Pattern FORM_FIELD_LABEL = Pattern.compile(
		"번호|품목|수량|(?:직접구매\\s*)?여부|제외\\s*사유|비고|성명|소속|서명|담당자|연락처"
	);
	private static final Pattern CHECKBOX_MARKER = Pattern.compile(
		"\\[\\s*(?:[√✓vVxX])?\\s*\\]"
	);
	private static final Pattern STRUCTURAL_CLASSIFICATION_HEADING = Pattern.compile(
		"(?:적용\\s*)?(?:대상|비대상|제외|면제)(?:\\s*사업)?"
			+ "|(?:금지|허용|필수|의무)(?:\\s*(?:대상|항목|사항))?",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern TERMINAL_NOMINAL_COORDINATION = Pattern.compile(
		"(?:\\s(?:또는|혹은|및)\\s|[·ㆍ/&,，–—-]"
			+ "|[\\p{IsHangul}]{2,}(?:와|과)\\s+[\\p{IsHangul}])"
	);
	private static final Pattern NUMERIC_VALUE = Pattern.compile("\\d[\\d,.]*");
	private static final Pattern KOREAN_WORD_NUMBER_WITH_UNIT = Pattern.compile(
		"[일이삼사오육칠팔구십백천만억]+(?:퍼센트|개월|시간|원|년|월|일|점|개|건|명|회|차|단계)"
	);
	private static final Set<String> STOPWORDS = Set.of(
		"그리고", "그러나", "다만", "또한", "해당", "경우", "관련", "기준", "내용", "문서", "근거",
		"확인", "필요", "가능", "여부", "질문", "답변", "못", "않", "있", "없",
		"합니다", "됩니다", "있습니다", "없습니다"
	);
	private static final Set<String> PERMISSION_PREDICATE_TERMS = Set.of(
		"가능", "불가능", "허용", "금지"
	);
	private static final Set<String> PATIENT_TOPIC_ACTIONS = Set.of(
		"수집", "처리", "보관", "파기", "삭제", "제공", "공개", "관리", "이용", "활용", "저장", "전송"
	);
	private static final Set<String> SHORT_LEGAL_ANCHORS = Set.of(
		"대상", "제외", "면제", "필수", "기한", "기간", "벌칙", "제재", "처분", "금지", "허용"
	);
	private static final Set<String> TARGET_SCOPE_SUFFIXES = Set.of(
		"심의", "협의", "검토", "평가", "심사", "승인", "허가", "신고"
	);
	private static final Set<String> TARGET_SCOPE_CONNECTORS = Set.of(
		"적용", "해당", "제외", "일반", "원칙"
	);
	private static final Set<String> TARGET_SCOPE_QUALIFIER_STOPWORDS = Set.of(
		"모든", "전체", "해당", "관련", "각", "각종", "주요", "일반", "원칙", "통상",
		"및", "또는"
	);
	private static final List<String> UNIVERSAL_SCOPE_CUES = List.of(
		"예외없이", "모든", "전체", "전부", "일체", "각"
	);
	private static final Set<String> STRUCTURED_PREDICATE_TERMS = Set.of(
		"순서", "절차", "흐름", "과정"
	);
	private static final Set<String> NEGATED_TARGET_COPULA_STEMS = Set.of(
		"대상이아니", "대상이아닙", "대상은아닙", "대상이아닌", "대상은아닌",
		"대상이아님", "대상은아님", "대상이아닐", "대상은아닐", "대상아님"
	);
	private static final Pattern NEGATED_TARGET_CLASSIFICATION = Pattern.compile(
		"대상으로(?:(?:보|분류하|판단하|간주하|취급하)지(?:는|도|조차)?(?:않|못)"
			+ "|(?:보|분류|판단|간주|취급)(?:은|는|도|조차)?하지"
			+ "(?:는|도|조차)?(?:않|못)"
			+ "|(?:보|분류|판단|간주|취급)(?:은|는|도|조차)?못)"
	);
	private static final Pattern NEGATED_TARGET_INCLUSION = Pattern.compile(
		"대상에포함(?:은|는|도|조차)?"
			+ "(?:(?:되지|하지)(?:는|도|조차)?(?:않|못)?|못)"
	);
	private static final Pattern AMBIGUOUS_TARGET_POLARITY = Pattern.compile(
		"(?:대상(?:이|은)?아닌(?:것|건|경우)(?:이|은|는|가)?(?:아니|아닙|않)"
			+ "|대상으로(?:보지|분류하지|판단하지|간주하지|취급하지)"
			+ "(?:는|도|조차)?않는"
			+ "(?:것|건|경우)(?:이|은|는|가)?(?:아니|아닙|않)"
			+ "|대상으로(?:보|분류|판단|간주|취급)(?:은|는|도|조차)?하지"
			+ "(?:는|도|조차)?않는(?:것|건|경우)(?:이|은|는|가)?(?:아니|아닙|않)"
			+ "|대상으로(?:보지|분류하지|판단하지|간주하지|취급하지)않을수(?:는)?없"
			+ "|(?:비대상|제외대상)(?:이|은)?(?:아니|아닙)"
			+ "|대상(?:이|은)?아니(?:라고)?(?:볼|판단할|단정할|간주할)수없"
			+ "|대상(?:이|은)?아니지는않"
			+ "|대상에서제외(?:하|되)?지(?:는|도|조차)?않"
			+ "|대상에포함(?:은|는|도|조차)?(?:되지|하지)(?:는|도|조차)?"
			+ "않는(?:것|건|경우)"
			+ "(?:이|은|는|가)?(?:아니|아닙|않))"
	);
	private static final Pattern ADDITIONAL_RESTRICTIVE_REQUIREMENT = Pattern.compile(
		"(?:지만|하고|하되)[^.!?]{0,100}"
			+ "(?:필요(?:합니다|하다|함)|요건(?:입니다|이다|임)|조건(?:입니다|이다|임)"
			+ "|경우에한(?:합니다|한다|함)|있어야|충족해야|갖추어야"
			+ "|(?:요구|전제)(?:됩니다|된다|합니다|한다|됨|함))",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern ADDITIONAL_APPROVAL_REQUIREMENT = Pattern.compile(
		"(?:지만|하고|하되)[^.!?]{0,100}"
			+ "(?:승인|허가|동의|심사|검토|확인)[^.!?]{0,40}"
			+ "(?:받아야|거쳐야|취득해야|얻어야|확보해야|제출해야)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern ADDITIONAL_MODAL_REQUIREMENT = Pattern.compile(
		"(?:지만|하고|하되)[^.!?]{0,160}"
			+ "(?:(?:해야|하여야|되어야|받아야|있어야|없어야)(?:만)?"
			+ "|(?:요구|전제)(?:됩니다|된다|합니다|한다|됨|함|로합니다|로한다)"
			+ "|조건|요건|경우에한)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Set<String> NON_ENTITY_SUBJECT_ROLES = Set.of(
		"경우", "경우에", "때", "때에", "사례", "사례로", "책임", "입증책임"
	);
	private static final Set<String> GENERIC_TARGET_DEFINITION_SUBJECTS = Set.of(
		"대상", "대상사업", "적용대상", "범위"
	);
	private final ClaimEvidenceAtomizer atomizer = new ClaimEvidenceAtomizer();

	public Match match(String claim, List<LawAiAnswerGround> grounds) {
		return match(claim, index(grounds));
	}

	public EvidenceIndex index(List<LawAiAnswerGround> grounds) {
		return new EvidenceIndex(evidenceSentences(grounds));
	}

	public Match match(String claim, EvidenceIndex evidenceIndex) {
		List<String> claimTokens = tokenize(claim);
		Set<String> claimNumbers = ClaimNumericNormalizer.tokens(claim);
		List<String> orderedClaimNumbers = ClaimNumericNormalizer.orderedTokens(claim);
		ClaimSemantics claimSemantics = ClaimSemantics.from(claim);
		boolean singleActionPermissionClaim = claimTokens.size() == 1
			&& claimSemantics.permissionActions().size() == 1
			&& claimSemantics.permissionMode() != PermissionMode.UNSPECIFIED;
		List<EvidenceSentence> sentences = evidenceIndex == null ? List.of() : evidenceIndex.sentences;
		if (claimTokens.isEmpty() || sentences.isEmpty()) {
			return Match.insufficient();
		}

		List<Candidate> supported = new ArrayList<>();
		List<Candidate> contradicted = new ArrayList<>();
		for (EvidenceSentence sentence : sentences) {
			if (sentence.documentTitleMetadata()) {
				if (isDocumentIdentityClaim(
					claim,
					sentence.text(),
					sentence.anchorContext()
				)) {
					int overlap = overlapCount(claimTokens, sentence.tokens());
					double coverage = (double) overlap
						/ Math.max(1, new LinkedHashSet<>(claimTokens).size());
					supported.add(new Candidate(
						sentence,
						overlap,
						coverage,
						10.0d + overlap + coverage
					));
				}
				continue;
			}
			if (isStructuralOnlyEvidence(sentence.text())
				|| (sentence.denseStructuralSource()
					&& !isIndependentAssertiveEvidence(sentence.text(), claimNumbers))) {
				continue;
			}
			int overlap = overlapCount(claimTokens, sentence.tokens());
			int requiredOverlap = claimNumbers.isEmpty() && !singleActionPermissionClaim
				? MIN_OVERLAP
				: 1;
			if (overlap < requiredOverlap
				|| !numbersSupported(claimNumbers, sentence.numbers())
				|| !numbersInOrder(orderedClaimNumbers, sentence.orderedNumbers())) {
				continue;
			}
			ClaimSemantics evidenceSemantics = ClaimSemantics.from(sentence.text());
			double coverage = (double) overlap / Math.max(1, new LinkedHashSet<>(claimTokens).size());
			if (conditionalGenericBusinessSubjectAligned(claimSemantics, evidenceSemantics)) {
				coverage = Math.max(
					coverage,
					coverageAfterLeadingConcession(claim, sentence.tokens())
				);
			}
			double requiredCoverage = claimNumbers.isEmpty() ? MIN_COVERAGE : 0.20d;
			if (coverage < requiredCoverage) {
				continue;
			}
			Relation relation = relation(claimSemantics, evidenceSemantics, sentence.anchorContext());
			double score = overlap + (coverage * 3.0d)
				+ (relation == Relation.COMPATIBLE ? 1.5d : 0.0d)
				+ exactPhraseBonus(claim, sentence.text());
			Candidate candidate = new Candidate(sentence, overlap, coverage, score);
			if (relation == Relation.CONTRADICTED) {
				contradicted.add(candidate);
				continue;
			}
			if (relation != Relation.NOT_ENTAILED) {
				supported.add(candidate);
			}
		}

		if (!supported.isEmpty() && !contradicted.isEmpty()) {
			return best(contradicted).toMatch(Status.CONFLICTED);
		}
		if (!supported.isEmpty()) {
			return best(supported).toMatch(Status.SUPPORTED);
		}
		if (!contradicted.isEmpty()) {
			return best(contradicted).toMatch(Status.CONTRADICTED);
		}
		return Match.insufficient();
	}

	private Candidate best(List<Candidate> candidates) {
		return candidates.stream().max(Comparator.comparingDouble(Candidate::score)).orElseThrow();
	}

	private double coverageAfterLeadingConcession(String claim, List<String> evidenceTokens) {
		Matcher matcher = LEADING_CONCESSIVE_FRAME.matcher(String.valueOf(claim == null ? "" : claim));
		if (!matcher.matches()) {
			return 0.0d;
		}
		List<String> propositionTokens = tokenize(matcher.group(1));
		if (propositionTokens.isEmpty()) {
			return 0.0d;
		}
		return (double) overlapCount(propositionTokens, evidenceTokens)
			/ new LinkedHashSet<>(propositionTokens).size();
	}

	private boolean isStructuralOnlyEvidence(String text) {
		String source = String.valueOf(text == null ? "" : text)
			.replaceAll("\\s+", " ")
			.trim();
		if (source.isBlank()) {
			return true;
		}
		if (isDenseCheckboxForm(source)) {
			return true;
		}
		if (ARTICLE_HEADING_ONLY.matcher(source).matches()) {
			return true;
		}
		if (SHORT_NOMINAL_HEADING_ONLY.matcher(source).matches()
			&& !SUBJECT_ROLE.matcher(source).find()
			&& !PARENTHETICAL_SUBJECT_ROLE.matcher(source).find()) {
			return true;
		}
		boolean denseStructuralLabels = hasDenseStructuralLabels(source);
		if (isDenseStructuralSelfAssertion(source)) {
			return true;
		}
		if (ASSERTIVE_EVIDENCE_CUE.matcher(source).find()) {
			return false;
		}
		return denseStructuralLabels;
	}

	static boolean isDocumentIdentityClaim(String claim, String documentTitle) {
		return isDocumentIdentityClaim(claim, documentTitle, "");
	}

	static boolean isDocumentIdentityClaim(
		String claim,
		String documentTitle,
		String documentMetadataContext
	) {
		String normalizedClaim = KoreanQueryNormalizer.normalizeForMatch(claim).replaceAll("\\s+", "");
		String normalizedTitle = KoreanQueryNormalizer.normalizeForMatch(documentTitle).replaceAll("\\s+", "");
		if (normalizedClaim.isBlank()
			|| normalizedTitle.length() < 4
			|| !normalizedClaim.contains(normalizedTitle)) {
			return false;
		}
		String remainder = normalizedClaim.replace(normalizedTitle, "");
		if (remainder.matches(
			"(?:(?:결론부터|요약하면|정리하면)(?:말씀드리면|말하면)?)?"
				+ "(?:찾으시는|요청하신|해당)?"
				+ "(?:문서|문서명|문서제목)(?:은|는|이|가)?(?:바로)?"
				+ "(?:입니다|이다|임)?"
		)) {
			return true;
		}
		Matcher descriptorMatcher = DOCUMENT_IDENTITY_DESCRIPTOR_REMAINDER.matcher(remainder);
		if (!descriptorMatcher.matches()) {
			return false;
		}
		String normalizedContext = KoreanQueryNormalizer
			.normalizeForMatch(documentMetadataContext)
			.replaceAll("\\s+", "");
		String descriptor = descriptorMatcher.group(1);
		return normalizedContext.contains(descriptor) && normalizedContext.contains("문서");
	}

	private boolean isDenseCheckboxForm(String text) {
		Matcher checkboxes = CHECKBOX_MARKER.matcher(String.valueOf(text == null ? "" : text));
		int checkboxCount = 0;
		while (checkboxes.find() && checkboxCount < 2) {
			checkboxCount++;
		}
		if (checkboxCount < 2) {
			return false;
		}
		Matcher labels = FORM_FIELD_LABEL.matcher(text);
		Set<String> distinctLabels = new LinkedHashSet<>();
		while (labels.find() && distinctLabels.size() < 3) {
			distinctLabels.add(normalize(labels.group()));
		}
		return distinctLabels.size() >= 3;
	}

	private boolean isDenseStructuralSelfAssertion(String text) {
		String source = String.valueOf(text == null ? "" : text)
			.trim();
		return isStructuralSourceIntroduction(source)
			&& hasDenseStructuralLabels(source);
	}

	private boolean isStructuralSourceIntroduction(String text) {
		return STRUCTURAL_SELF_ASSERTION.matcher(
			String.valueOf(text == null ? "" : text).trim()
		).find();
	}

	private boolean isIndependentAssertiveEvidence(String text, Set<String> requiredClaimNumbers) {
		String source = String.valueOf(text == null ? "" : text)
			.replaceAll("\\s+", " ")
			.trim();
		if (isStructuralSourceIntroduction(source)) {
			return false;
		}
		if (ASSERTIVE_EVIDENCE_CUE.matcher(source).find()) {
			return true;
		}
		Set<String> evidenceNumbers = ClaimNumericNormalizer.tokens(source);
		if (hasQualifiedNumericToken(requiredClaimNumbers)
			&& LABELED_SUBJECT_ROLE.matcher(source).find()
			&& hasQualifiedNumericToken(evidenceNumbers)
			&& !STRUCTURAL_CLASSIFICATION_HEADING.matcher(source).find()) {
			return true;
		}
		boolean hasExplicitRole = SUBJECT_ROLE.matcher(source).find()
			|| PARENTHETICAL_SUBJECT_ROLE.matcher(source).find()
			|| LABELED_SUBJECT_ROLE.matcher(source).find()
			|| OBJECT_ROLE.matcher(source).find()
			|| RECIPIENT_ROLE.matcher(source).find();
		return hasExplicitRole && FINITE_ASSERTIVE_ENDING.matcher(source).find();
	}

	private boolean hasQualifiedNumericToken(Set<String> tokens) {
		return tokens != null
			&& tokens.stream().anyMatch(token -> !token.startsWith("number:"));
	}

	private boolean hasDenseStructuralLabels(String text) {
		Matcher labels = STRUCTURAL_LABEL.matcher(String.valueOf(text == null ? "" : text));
		Set<String> distinctLabels = new LinkedHashSet<>();
		while (labels.find() && distinctLabels.size() < 3) {
			distinctLabels.add(normalize(labels.group()));
		}
		return distinctLabels.size() >= 3;
	}

	private List<EvidenceSentence> evidenceSentences(List<LawAiAnswerGround> grounds) {
		if (grounds == null || grounds.isEmpty()) {
			return List.of();
		}
		Map<String, EvidenceSentence> unique = new LinkedHashMap<>();
		for (LawAiAnswerGround ground : grounds) {
			if (ground == null) {
				continue;
			}
			String anchorContext = (
				String.valueOf(ground.title()) + " "
					+ String.valueOf(ground.chunkTitle()) + " "
					+ String.valueOf(ground.categoryName())
			).replaceAll("\\s+", " ").trim();
			String structuralContext = String.join(
				"\n",
				String.valueOf(ground.chunkTitle()),
				String.valueOf(ground.matchedChildText()),
				String.valueOf(ground.snippet()),
				String.valueOf(ground.parentContextText())
			);
			boolean denseStructuralContext = (
				isStructuralSourceIntroduction(ground.chunkTitle())
					|| isStructuralSourceIntroduction(ground.matchedChildText())
					|| isStructuralSourceIntroduction(ground.snippet())
					|| isStructuralSourceIntroduction(ground.parentContextText())
			) && hasDenseStructuralLabels(structuralContext);
			addDocumentTitleEvidence(unique, ground.number(), ground.title(), anchorContext);
			addEvidenceFragments(
				unique,
				ground.number(),
				ground.matchedChildText(),
				anchorContext,
				denseStructuralContext
			);
			addEvidenceFragments(
				unique,
				ground.number(),
				ground.snippet(),
				anchorContext,
				denseStructuralContext
			);
			addEvidenceFragments(
				unique,
				ground.number(),
				ground.parentContextText(),
				anchorContext,
				denseStructuralContext
			);
		}
		return List.copyOf(unique.values());
	}

	private void addDocumentTitleEvidence(
		Map<String, EvidenceSentence> unique,
		int groundNumber,
		String title,
		String anchorContext
	) {
		String cleaned = String.valueOf(title == null ? "" : title)
			.replaceAll("\\s+", " ")
			.trim();
		String normalizedTitle = normalize(cleaned);
		if (cleaned.length() < 4 || normalizedTitle.isBlank()) {
			return;
		}
		unique.putIfAbsent(
			groundNumber + "|document-title|" + normalizedTitle,
			new EvidenceSentence(
				groundNumber,
				cleaned,
				tokenize(cleaned),
				ClaimNumericNormalizer.tokens(cleaned),
				ClaimNumericNormalizer.orderedTokens(cleaned),
				anchorContext,
				false,
				true
			)
		);
	}

	private void addEvidenceFragments(
		Map<String, EvidenceSentence> unique,
		int groundNumber,
		String text,
		String anchorContext,
		boolean denseStructuralContext
	) {
		if (text == null || text.isBlank()) {
			return;
		}
		for (String fragment : atomizer.atomize(text)) {
			String cleaned = fragment.replaceAll("\\s+", " ").trim();
			if (cleaned.length() < 4) {
				continue;
			}
			String normalizedText = normalize(cleaned);
			String key = groundNumber + "|" + normalizedText + "|" + denseStructuralContext;
			if (!normalizedText.isBlank()) {
				unique.putIfAbsent(key, new EvidenceSentence(
					groundNumber,
					cleaned,
					tokenize(cleaned),
					ClaimNumericNormalizer.tokens(cleaned),
					ClaimNumericNormalizer.orderedTokens(cleaned),
					anchorContext,
					denseStructuralContext,
					false
				));
			}
		}
	}

	private Relation relation(ClaimSemantics claim, ClaimSemantics evidence, String evidenceAnchorContext) {
		if (hasDoubleNegatedTarget(claim.normalizedText())
			|| hasDoubleNegatedTarget(evidence.normalizedText())) {
			return Relation.NOT_ENTAILED;
		}
		boolean exactProposition = claim.normalizedText().equals(evidence.normalizedText());
		if (exactProposition) {
			return targetScopeAligned(claim, evidence, evidenceAnchorContext)
				? Relation.COMPATIBLE
				: Relation.NOT_ENTAILED;
		}
		boolean equivalentProposition = exactProposition
			|| canonicalAssertiveEndingText(claim.normalizedText())
				.equals(canonicalAssertiveEndingText(evidence.normalizedText()))
			|| canonicalCompletionCondition(canonicalAssertiveEndingText(claim.normalizedText()))
				.equals(canonicalCompletionCondition(canonicalAssertiveEndingText(evidence.normalizedText())));
		boolean namedUniversalDefinition = namedUniversalDefinitionAligned(claim, evidence);
		if (evidence.disjunctiveCoordination() && !exactProposition) {
			return Relation.NOT_ENTAILED;
		}
		if (!claim.exclusiveRoleAnchors().isEmpty()
			&& !evidence.exclusiveRoleAnchors().containsAll(claim.exclusiveRoleAnchors())) {
			return Relation.NOT_ENTAILED;
		}
		if (evidence.ambiguousSubjectAttribution()
			&& !exactProposition
			&& !namedUniversalDefinition) {
			return Relation.NOT_ENTAILED;
		}
		if (!claim.requiredRelationAnchors().isEmpty()
			&& claim.requiredRelationAnchors().stream()
				.anyMatch(anchor ->
					!evidence.normalizedText().contains(anchor)
						&& !isRedundantDefinitionRelationAnchor(
							claim,
							anchor,
							namedUniversalDefinition
						)
				)) {
			return Relation.NOT_ENTAILED;
		}
		if (oppositeClassificationConditions(claim, evidence)) {
			return Relation.NOT_ENTAILED;
		}
		if (!claim.requiredConditionAnchors().isEmpty()) {
			boolean missingFromSentence = !conditionAnchorsCovered(
				claim.requiredConditionAnchors(),
				evidence.normalizedText()
			);
			boolean scopeConditionSupportedByContext = claim.conditional()
				&& !evidence.conditional()
				&& conditionAnchorsCovered(
					claim.requiredConditionAnchors(),
					evidence.normalizedText() + evidenceAnchorContext
				);
			if (missingFromSentence && !scopeConditionSupportedByContext) {
				return Relation.NOT_ENTAILED;
			}
		}
		if (!claim.requiredNumericAnchors().isEmpty()
			&& claim.requiredNumericAnchors().stream()
				.anyMatch(anchor -> !evidence.normalizedText().contains(anchor))) {
			return Relation.NOT_ENTAILED;
		}
		if (!targetScopeAligned(claim, evidence, evidenceAnchorContext)) {
			return Relation.NOT_ENTAILED;
		}
		if (!responsibilityBearersAligned(
			claim.responsibilityBearers(),
			evidence.responsibilityBearers()
		)) {
			return Relation.NOT_ENTAILED;
		}
		if (!scopeAnchorsCovered(
			claim.universalScopeAnchors(),
			evidence.universalScopeAnchors()
		)) {
			return Relation.NOT_ENTAILED;
		}
		if (claim.openEndedEnumeration()
			&& (!evidence.openEndedEnumeration()
				|| !scopeAnchorsCovered(
					claim.enumerationScopeAnchors(),
					evidence.enumerationScopeAnchors()
				))) {
			return Relation.NOT_ENTAILED;
		}
		if (equivalentProposition) {
			return Relation.COMPATIBLE;
		}
		if (!claim.hasExplicitSemantics()) {
			if (!neutralRolesAligned(claim, evidence)) {
				return Relation.NOT_ENTAILED;
			}
			return Relation.NEUTRAL;
		}
		if (!rolesAligned(claim, evidence) && !namedUniversalDefinition) {
			return Relation.NOT_ENTAILED;
		}
		if (!claim.permissionActions().isEmpty()
			&& !evidence.permissionActions().containsAll(claim.permissionActions())) {
			return Relation.NOT_ENTAILED;
		}
		if (!evidence.categories().containsAll(claim.categories())) {
			return Relation.NOT_ENTAILED;
		}
		if (claim.narrowingCondition() != evidence.narrowingCondition()) {
			return Relation.NOT_ENTAILED;
		}
		if (evidence.conditional()
			&& !claim.conditional()
			&& claim.requiredRelationAnchors().isEmpty()) {
			return Relation.NOT_ENTAILED;
		}
		if (claim.conditional() && !evidence.conditional() && claim.requiredConditionAnchors().isEmpty()) {
			return Relation.NOT_ENTAILED;
		}
		Relation predicateRelation = predicateRelation(claim, evidence);
		if (predicateRelation == Relation.CONTRADICTED
			|| predicateRelation == Relation.NOT_ENTAILED) {
			return predicateRelation;
		}
		boolean oppositeTargetMode = opposite(claim.targetMode(), evidence.targetMode());
		if (oppositeTargetMode
			&& !roleSetCovered(claim.roles().subjects(), evidence.roles().subjects())) {
			return Relation.NOT_ENTAILED;
		}
		if (oppositeTargetMode
			|| opposite(claim.obligationMode(), evidence.obligationMode())
			|| opposite(claim.permissionMode(), evidence.permissionMode())) {
			return Relation.CONTRADICTED;
		}
		if (!sameOrUnspecified(claim.targetMode(), evidence.targetMode())
			|| !sameOrUnspecified(claim.obligationMode(), evidence.obligationMode())
			|| !sameOrUnspecified(claim.permissionMode(), evidence.permissionMode())) {
			return Relation.NOT_ENTAILED;
		}
		return Relation.COMPATIBLE;
	}

	private boolean oppositeClassificationConditions(
		ClaimSemantics claim,
		ClaimSemantics evidence
	) {
		return !claim.requiredConditionAnchors().isEmpty()
			&& conditionAnchorsCovered(
				claim.requiredConditionAnchors(),
				evidence.normalizedText()
			)
			&& ClaimSemantics.containsAny(
				claim.normalizedText(),
				"해당하면", "해당하는경우", "해당할경우",
				"포함되면", "포함되는경우", "포함될경우"
			)
			&& ClaimSemantics.containsAny(
				evidence.normalizedText(),
				"볼수없는경우", "해당하지않는경우", "해당하지않을경우",
				"포함되지않는경우", "포함하지않는경우"
			);
	}

	private String canonicalAssertiveEndingText(String text) {
		return String.valueOf(text == null ? "" : text)
			.replaceFirst("\uD569\uB2C8\uB2E4$", "\uD55C\uB2E4")
			.replaceFirst("\uB429\uB2C8\uB2E4$", "\uB41C\uB2E4")
			.replaceFirst("\uC788\uC2B5\uB2C8\uB2E4$", "\uC788\uB2E4")
			.replaceFirst("\uC5C6\uC2B5\uB2C8\uB2E4$", "\uC5C6\uB2E4")
			.replaceFirst("\uC544\uB2D9\uB2C8\uB2E4$", "\uC544\uB2C8\uB2E4");
	}

	private boolean targetScopeAligned(
		ClaimSemantics claim,
		ClaimSemantics evidence,
		String evidenceAnchorContext
	) {
		if (claim.targetMode() == TargetMode.UNSPECIFIED
			&& evidence.targetMode() == TargetMode.UNSPECIFIED) {
			return true;
		}
		Set<String> claimAnchors = claim.targetScopeAnchors();
		Set<String> evidenceAnchors = evidence.targetScopeAnchors();
		if (claim.normalizedText().equals(evidence.normalizedText())
			&& !claim.requiredConditionAnchors().isEmpty()
			&& claimAnchors.isEmpty()
			&& evidenceAnchors.isEmpty()) {
			return true;
		}
		Set<String> contextAnchors = ClaimSemantics.namedTargetScopes(evidenceAnchorContext);
		if (claimAnchors.isEmpty()) {
			if (!evidenceAnchors.isEmpty()) {
				return false;
			}
			if (contextAnchors.isEmpty()) {
				return true;
			}
			return contextAnchors.size() == 1
				&& (
					implicitSingleScopeEnumerationAligned(claim, evidence)
						|| inlineUniversalTargetDefinitionAligned(claim, evidence)
				);
		}
		if (!evidenceAnchors.isEmpty()) {
			return targetScopeSetsAligned(claimAnchors, evidenceAnchors);
		}
		return !contextAnchors.isEmpty() && targetScopeSetsAligned(claimAnchors, contextAnchors);
	}

	private boolean inlineUniversalTargetDefinitionAligned(
		ClaimSemantics claim,
		ClaimSemantics evidence
	) {
		return claim.normalizedText().equals(evidence.normalizedText())
			&& claim.targetMode() == TargetMode.INCLUDED
			&& evidence.targetMode() == TargetMode.INCLUDED
			&& !claim.universalScopeAnchors().isEmpty()
			&& claim.universalScopeAnchors().equals(evidence.universalScopeAnchors());
	}

	private boolean implicitSingleScopeEnumerationAligned(
		ClaimSemantics claim,
		ClaimSemantics evidence
	) {
		return claim.openEndedEnumeration()
			&& evidence.openEndedEnumeration()
			&& !claim.enumerationScopeAnchors().isEmpty()
			&& scopeAnchorsCovered(
				claim.enumerationScopeAnchors(),
				evidence.enumerationScopeAnchors()
			);
	}

	private boolean targetScopeSetsAligned(Set<String> left, Set<String> right) {
		return left.equals(right);
	}

	private boolean conditionAnchorsCovered(Set<String> anchors, String evidenceText) {
		String canonicalEvidence = canonicalConditionText(evidenceText);
		return anchors.stream().allMatch(anchor -> {
			String canonicalAnchor = canonicalConditionAnchor(anchor);
			return canonicalAnchor.length() >= 2 && canonicalEvidence.contains(canonicalAnchor);
		});
	}

	private String canonicalConditionAnchor(String anchor) {
		String normalizedAnchor = normalize(anchor);
		if (normalizedAnchor.endsWith("처럼")
			&& normalizedAnchor.length() > "처럼".length() + 1) {
			normalizedAnchor = normalizedAnchor.substring(
				0,
				normalizedAnchor.length() - "처럼".length()
			);
		}
		String withoutTrailingJosa = KoreanQueryNormalizer.stripTrailingJosa(normalizedAnchor);
		return canonicalCompletionCondition(canonicalSoftwareTerm(withoutTrailingJosa));
	}

	private String canonicalConditionText(String text) {
		return canonicalCompletionCondition(canonicalSoftwareTerm(normalize(text)));
	}

	private String canonicalCompletionCondition(String text) {
		return String.valueOf(text == null ? "" : text)
			.replaceAll("\\s+", "")
			.replaceAll("\uD558(?:\uC600\uC744|\uC600|\uC5C8\uC744|\uC5C8|\uC744)\uB54C(?:\uC5D0\uB294|\uC5D0|\uB294)?", "\uD558")
			.replaceAll("\uD558\uBA74", "\uD558")
			.replaceAll("\uD55C(?:\uD6C4|\uB54C)(?:\uC5D0\uB294|\uC5D0|\uB294)?", "")
			.replaceAll("\uD558(?:\uC600\uC744|\uC600|\uC5C8\uC744|\uC5C8|\uC744)$", "\uD558")
			.replaceAll("\uD55C$", "");
	}

	private static String canonicalSoftwareTerm(String text) {
		return text
			.replace("소프트웨어", "sw")
			.replace("하드웨어", "hw")
			.replace("hardware", "hw");
	}

	private static boolean hasNegatedTargetCopula(String normalizedText) {
		return NEGATED_TARGET_COPULA_STEMS.stream().anyMatch(normalizedText::contains);
	}

	private static boolean hasNegatedTargetClassification(String normalizedText) {
		return NEGATED_TARGET_CLASSIFICATION.matcher(normalizedText).find();
	}

	private static boolean hasNegatedTargetInclusion(String normalizedText) {
		return NEGATED_TARGET_INCLUSION.matcher(normalizedText).find();
	}

	private static boolean hasDoubleNegatedTarget(String normalizedText) {
		return AMBIGUOUS_TARGET_POLARITY.matcher(normalizedText).find();
	}

	private boolean rolesAligned(ClaimSemantics claim, ClaimSemantics evidence) {
		PropositionRoles claimRoles = claim.roles();
		PropositionRoles evidenceRoles = evidence.roles();
		Set<PermissionTargetAlternative> evidenceObjectAlternatives =
			claimRoles.permissionTargets().isEmpty()
				|| evidenceRoles.permissionTargets().isEmpty()
					? evidenceRoles.permissionTargets()
					: Set.of();
		Set<RoleIdentity> claimRecipients = new LinkedHashSet<>(claimRoles.recipients());
		claimRecipients.removeIf(role ->
			claim.responsibilityBearers().contains(role.canonical())
				|| claim.responsibilityBearers().contains(role.head())
		);
		if (!roleSetCoveredWithAlternatives(
			claimRoles.subjects(),
			evidenceRoles.subjects(),
			Set.of()
		) && !conditionalGenericBusinessSubjectAligned(claim, evidence)
			&& !patientTopicAligned(claim, evidence)) {
			return false;
		}
		if (!claimRecipients.isEmpty()
			&& (evidenceRoles.recipients().isEmpty()
				|| !roleSetCovered(claimRecipients, evidenceRoles.recipients()))) {
			return false;
		}
		if (!roleSetCoveredWithAlternatives(
			claimRoles.objects(),
			evidenceRoles.objects(),
			evidenceObjectAlternatives
		)) {
			return false;
		}
		return permissionTargetsCovered(claimRoles.permissionTargets(), evidenceRoles);
	}

	private boolean patientTopicAligned(ClaimSemantics claim, ClaimSemantics evidence) {
		if (claim.obligationMode() != ObligationMode.REQUIRED
			|| evidence.obligationMode() != ObligationMode.REQUIRED
			|| claim.roles().subjects().size() != 1
			|| !claim.roles().objects().isEmpty()
			|| evidence.roles().subjects().isEmpty()
			|| evidence.roles().objects().isEmpty()) {
			return false;
		}
		boolean samePatientAction = claim.affirmedPredicateActions().stream()
			.filter(PATIENT_TOPIC_ACTIONS::contains)
			.anyMatch(evidence.affirmedPredicateActions()::contains);
		if (!samePatientAction) {
			return false;
		}
		RoleIdentity patient = claim.roles().subjects().iterator().next();
		String patientHead = withoutFocusParticle(patient.head());
		return evidence.roles().objects().stream().anyMatch(object ->
			patientHead.equals(withoutFocusParticle(object.head()))
		);
	}

	private String withoutFocusParticle(String role) {
		String normalized = canonicalSoftwareTerm(String.valueOf(role == null ? "" : role));
		if (normalized.endsWith("만") && normalized.length() > 2) {
			return normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	private boolean conditionalGenericBusinessSubjectAligned(
		ClaimSemantics claim,
		ClaimSemantics evidence
	) {
		if (!claim.conditional()
			|| claim.requiredConditionAnchors().isEmpty()
			|| claim.roles().subjects().isEmpty()
			|| evidence.roles().subjects().isEmpty()
			|| claim.roles().subjects().stream().anyMatch(role -> !"사업".equals(role.head()))) {
			return false;
		}
		Set<String> conditionAnchors = claim.requiredConditionAnchors().stream()
			.map(this::canonicalConditionAnchor)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		return evidence.roles().subjects().stream().anyMatch(role ->
			conditionAnchors.contains(canonicalSoftwareTerm(role.canonical()))
				|| conditionAnchors.contains(canonicalSoftwareTerm(role.head()))
		);
	}

	private boolean neutralRolesAligned(ClaimSemantics claim, ClaimSemantics evidence) {
		if (rolesAligned(claim, evidence)) {
			return true;
		}
		PropositionRoles claimRoles = claim.roles();
		PropositionRoles evidenceRoles = evidence.roles();
		Set<RoleIdentity> claimSubjects = claimRoles.subjects();
		if (namedUniversalDefinitionAligned(claim, evidence)) {
			claimSubjects = claimSubjects.stream()
				.filter(role -> !GENERIC_TARGET_DEFINITION_SUBJECTS.contains(role.head()))
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		}
		return neutralRoleSetCovered(
			claimSubjects,
			evidenceRoles.subjects(),
			evidence.normalizedText()
		) && neutralRoleSetCovered(
			claimRoles.objects(),
			evidenceRoles.objects(),
			evidence.normalizedText()
		) && neutralRoleSetCovered(
			claimRoles.recipients(),
			evidenceRoles.recipients(),
			evidence.normalizedText()
		);
	}

	private boolean namedUniversalDefinitionAligned(
		ClaimSemantics claim,
		ClaimSemantics evidence
	) {
		if (claim.targetMode() != TargetMode.UNSPECIFIED
			|| evidence.targetMode() != TargetMode.UNSPECIFIED
			|| claim.permissionMode() != PermissionMode.UNSPECIFIED
			|| evidence.permissionMode() != PermissionMode.UNSPECIFIED
			|| claim.obligationMode() != ObligationMode.UNSPECIFIED
			|| evidence.obligationMode() != ObligationMode.UNSPECIFIED
			|| !definitionTargetScopesAligned(claim, evidence)
			|| claim.universalScopeAnchors().isEmpty()
			|| !claim.universalScopeAnchors().equals(evidence.universalScopeAnchors())) {
			return false;
		}
		return isCopularDefinitionOfUniversalScope(claim)
			&& isCopularDefinitionOfUniversalScope(evidence)
			&& hasPositiveRelationalDefinitionSubject(evidence)
			&& claim.roles().subjects().stream()
				.anyMatch(role -> GENERIC_TARGET_DEFINITION_SUBJECTS.contains(role.head()));
	}

	private boolean isRedundantDefinitionRelationAnchor(
		ClaimSemantics claim,
		String anchor,
		boolean namedUniversalDefinition
	) {
		return namedUniversalDefinition
			&& GENERIC_TARGET_DEFINITION_SUBJECTS.contains(anchor)
			&& claim.roles().subjects().stream().anyMatch(role ->
				anchor.equals(role.canonical()) || anchor.equals(role.head())
			);
	}

	private boolean definitionTargetScopesAligned(
		ClaimSemantics claim,
		ClaimSemantics evidence
	) {
		if (!claim.targetScopeAnchors().isEmpty()) {
			return claim.targetScopeAnchors().equals(evidence.targetScopeAnchors());
		}
		return evidence.targetScopeAnchors().size() == 1
			&& claim.roles().subjects().stream()
				.anyMatch(role -> GENERIC_TARGET_DEFINITION_SUBJECTS.contains(role.head()));
	}

	private boolean hasPositiveRelationalDefinitionSubject(ClaimSemantics semantics) {
		return semantics.roles().subjects().stream().anyMatch(role ->
			semantics.targetScopeAnchors().stream().anyMatch(scope ->
				Set.of(
					scope + "대상",
					scope + "대상사업",
					scope + "범위",
					scope + "의대상",
					scope + "의대상사업",
					scope + "의범위"
				)
					.contains(role.canonical())
			)
		);
	}

	private boolean isCopularDefinitionOfUniversalScope(ClaimSemantics semantics) {
		String normalized = semantics.normalizedText();
		String predicateStem = normalized.replaceFirst("(?:입니다|이다|임)[.!?]*$", "");
		return semantics.universalScopeAnchors().stream().anyMatch(predicateStem::endsWith);
	}

	private boolean neutralRoleSetCovered(
		Set<RoleIdentity> claimRoles,
		Set<RoleIdentity> evidenceRoles,
		String normalizedEvidence
	) {
		if (claimRoles.isEmpty()) {
			return true;
		}
		if (!evidenceRoles.isEmpty()) {
			return roleSetCovered(claimRoles, evidenceRoles);
		}
		return claimRoles.stream().allMatch(role -> normalizedEvidence.contains(role.canonical()));
	}

	private boolean roleSetCoveredWithAlternatives(
		Set<RoleIdentity> claim,
		Set<RoleIdentity> evidence,
		Set<PermissionTargetAlternative> evidenceAlternatives
	) {
		return claim.stream().allMatch(role ->
			evidence.stream().anyMatch(role::coveredBy)
				|| evidenceAlternatives.stream().anyMatch(alternative ->
					alternative.identities().contains(role.canonical())
						|| (role.qualified() && alternative.identities().contains(role.head()))
				)
		);
	}

	private boolean permissionTargetsCovered(
		Set<PermissionTargetAlternative> claimAlternatives,
		PropositionRoles evidenceRoles
	) {
		if (claimAlternatives.isEmpty()) {
			return true;
		}
		Set<String> evidenceIdentities = new LinkedHashSet<>();
		for (PermissionTargetAlternative alternative : evidenceRoles.permissionTargets()) {
			evidenceIdentities.addAll(alternative.alignmentIdentities());
		}
		if (evidenceIdentities.isEmpty()) {
			evidenceRoles.subjects().forEach(role -> {
				evidenceIdentities.add(role.canonical());
				evidenceIdentities.add(role.head());
			});
			evidenceRoles.objects().forEach(role -> {
				evidenceIdentities.add(role.canonical());
				evidenceIdentities.add(role.head());
			});
		}
		return claimAlternatives.stream().allMatch(alternative ->
			alternative.alignmentIdentities().stream().anyMatch(evidenceIdentities::contains)
		);
	}

	private boolean responsibilityBearersAligned(Set<String> claim, Set<String> evidence) {
		return claim.isEmpty() || (!evidence.isEmpty() && evidence.containsAll(claim));
	}

	private boolean scopeAnchorsCovered(Set<String> claim, Set<String> evidence) {
		return claim.isEmpty() || (!evidence.isEmpty() && evidence.containsAll(claim));
	}

	private Relation predicateRelation(ClaimSemantics claim, ClaimSemantics evidence) {
		if (claim.affirmedPredicateActions().isEmpty()
			&& claim.negatedPredicateActions().isEmpty()
			&& claim.plannedPredicateActions().isEmpty()
			&& claim.ongoingPredicateActions().isEmpty()) {
			return Relation.NEUTRAL;
		}
		if (!evidence.plannedPredicateActions().containsAll(claim.plannedPredicateActions())
			|| !evidence.ongoingPredicateActions().containsAll(claim.ongoingPredicateActions())) {
			return Relation.NOT_ENTAILED;
		}
		for (String action : claim.affirmedPredicateActions()) {
			if (evidence.negatedPredicateActions().contains(action)) {
				return Relation.CONTRADICTED;
			}
			if (evidence.permissionMode() == PermissionMode.PROHIBITED
				&& evidence.permissionActions().contains(action)) {
				return Relation.CONTRADICTED;
			}
			if (!evidence.affirmedPredicateActions().contains(action)
				&& !isAuxiliaryPredicateAction(action, claim, evidence)) {
				return Relation.NOT_ENTAILED;
			}
		}
		for (String action : claim.negatedPredicateActions()) {
			boolean same = evidence.negatedPredicateActions().contains(action);
			boolean opposite = evidence.affirmedPredicateActions().contains(action);
			if (same == opposite) {
				return Relation.NOT_ENTAILED;
			}
			if (opposite) {
				return Relation.CONTRADICTED;
			}
		}
		return Relation.COMPATIBLE;
	}

	private boolean isAuxiliaryPredicateAction(
		String action,
		ClaimSemantics claim,
		ClaimSemantics evidence
	) {
		if ("조치".equals(action)) {
			return opposite(claim.permissionMode(), evidence.permissionMode());
		}
		if (!Set.of("진행", "실시").contains(action)) {
			return false;
		}
		if (!evidence.finiteAffirmedPredicateActions().isEmpty()
			|| !evidence.negatedPredicateActions().isEmpty()) {
			return false;
		}
		if (!claim.requiredNumericAnchors().isEmpty()) {
			return true;
		}
		return STRUCTURED_PREDICATE_TERMS.stream().anyMatch(term ->
			claim.lexicalTerms().contains(term) && evidence.lexicalTerms().contains(term)
		);
	}

	private boolean roleSetCovered(Set<RoleIdentity> claim, Set<RoleIdentity> evidence) {
		if (claim.isEmpty()) {
			return true;
		}
		if (evidence.isEmpty()) {
			return false;
		}
		return claim.stream().allMatch(claimRole ->
			evidence.stream().anyMatch(claimRole::coveredBy)
		);
	}

	private boolean opposite(TargetMode claim, TargetMode evidence) {
		return claim != evidence && claim != TargetMode.UNSPECIFIED && evidence != TargetMode.UNSPECIFIED;
	}

	private boolean opposite(ObligationMode claim, ObligationMode evidence) {
		return claim != evidence
			&& claim != ObligationMode.UNSPECIFIED
			&& evidence != ObligationMode.UNSPECIFIED;
	}

	private boolean opposite(PermissionMode claim, PermissionMode evidence) {
		return claim != evidence
			&& claim != PermissionMode.UNSPECIFIED
			&& evidence != PermissionMode.UNSPECIFIED;
	}

	private boolean sameOrUnspecified(TargetMode claim, TargetMode evidence) {
		return claim == TargetMode.UNSPECIFIED || claim == evidence;
	}

	private boolean sameOrUnspecified(ObligationMode claim, ObligationMode evidence) {
		return claim == ObligationMode.UNSPECIFIED || claim == evidence;
	}

	private boolean sameOrUnspecified(PermissionMode claim, PermissionMode evidence) {
		return claim == PermissionMode.UNSPECIFIED || claim == evidence;
	}

	private int overlapCount(List<String> claimTokens, List<String> evidenceTokens) {
		Set<String> matched = new LinkedHashSet<>();
		for (String claimToken : claimTokens) {
			for (String evidenceToken : evidenceTokens) {
				if (termMatches(claimToken, evidenceToken)) {
					matched.add(claimToken);
					break;
				}
			}
		}
		return matched.size();
	}

	private boolean termMatches(String left, String right) {
		if (left.equals(right)) {
			return true;
		}
		if (containsKoreanWordNumericUnit(left) || containsKoreanWordNumericUnit(right)) {
			return false;
		}
		if (sharesShortLegalAnchor(left, right)) {
			return true;
		}
		return left.length() >= 3 && right.length() >= 3
			&& (left.contains(right) || right.contains(left));
	}

	private boolean containsKoreanWordNumericUnit(String token) {
		return KOREAN_WORD_NUMBER_WITH_UNIT.matcher(token).find();
	}

	private boolean sharesShortLegalAnchor(String left, String right) {
		return SHORT_LEGAL_ANCHORS.stream()
			.anyMatch(anchor -> left.contains(anchor) && right.contains(anchor));
	}

	private boolean numbersSupported(Set<String> claimNumbers, Set<String> evidenceNumbers) {
		return claimNumbers.isEmpty() || evidenceNumbers.containsAll(claimNumbers);
	}

	private boolean numbersInOrder(List<String> claimNumbers, List<String> evidenceNumbers) {
		int claimIndex = 0;
		for (String evidenceNumber : evidenceNumbers) {
			if (claimIndex < claimNumbers.size() && claimNumbers.get(claimIndex).equals(evidenceNumber)) {
				claimIndex++;
			}
		}
		return claimIndex == claimNumbers.size();
	}

	private double exactPhraseBonus(String claim, String evidence) {
		String normalizedClaim = normalize(claim);
		String normalizedEvidence = normalize(evidence);
		return normalizedClaim.length() >= 6 && normalizedEvidence.contains(normalizedClaim) ? 2.0d : 0.0d;
	}

	private List<String> tokenize(String text) {
		String normalized = String.valueOf(text == null ? "" : text)
			.replaceAll("[^\\p{IsHangul}\\p{Alnum}]+", " ")
			.toLowerCase()
			.trim();
		if (normalized.isBlank()) {
			return List.of();
		}
		List<String> tokens = new ArrayList<>();
		String[] rawTokens = normalized.split("\\s+");
		for (int index = 0; index < rawTokens.length; index++) {
			String raw = rawTokens[index];
			String token = raw.trim();
			if (index == rawTokens.length - 1) {
				String canonicalPredicate = canonicalTerminalPredicateToken(token);
				token = canonicalPredicate;
			}
			token = canonicalSoftwareTerm(KoreanQueryNormalizer.stripTrailingJosa(token));
			if (token.length() >= 2 && !STOPWORDS.contains(token)) {
				tokens.add(token);
			}
		}
		return tokens;
	}

	private String canonicalTerminalPredicateToken(String token) {
		for (String suffix : List.of(
			"합니다", "됩니다", "입니다", "하지만", "되지만",
			"하고", "하되", "되며", "이며", "이고", "한다", "된다", "이다",
			"함", "됨", "임"
		)) {
			if (token.endsWith(suffix) && token.length() > suffix.length() + 1) {
				return token.substring(0, token.length() - suffix.length());
			}
		}
		return token;
	}

	private String normalize(String text) {
		return KoreanQueryNormalizer.normalizeForMatch(text == null ? "" : text);
	}

	private static boolean isAttributiveVerbRoleToken(String source, Matcher matcher) {
		String matchedToken = source.substring(matcher.start(1), matcher.end());
		return ATTRIBUTIVE_VERB_ROLE_TOKEN.matcher(matchedToken).find();
	}

	private record EvidenceSentence(
		int groundNumber,
		String text,
		List<String> tokens,
		Set<String> numbers,
		List<String> orderedNumbers,
		String anchorContext,
		boolean denseStructuralSource,
		boolean documentTitleMetadata
	) {
	}

	public static final class EvidenceIndex {
		private final List<EvidenceSentence> sentences;

		private EvidenceIndex(List<EvidenceSentence> sentences) {
			this.sentences = sentences == null ? List.of() : List.copyOf(sentences);
		}
	}

	private record Candidate(EvidenceSentence sentence, int overlap, double coverage, double score) {
		Match toMatch(Status status) {
			return new Match(status, sentence.groundNumber(), sentence.text(), overlap, coverage, score);
		}
	}

	private record EnumerationScope(Set<String> anchors, boolean openEnded) {
	}

	private record ClaimSemantics(
		TargetMode targetMode,
		ObligationMode obligationMode,
		PermissionMode permissionMode,
		Set<String> permissionActions,
		Set<String> finiteAffirmedPredicateActions,
		Set<String> affirmedPredicateActions,
		Set<String> negatedPredicateActions,
		Set<String> plannedPredicateActions,
		Set<String> ongoingPredicateActions,
		Set<String> responsibilityBearers,
		Set<String> universalScopeAnchors,
		Set<String> enumerationScopeAnchors,
		boolean openEndedEnumeration,
		Set<String> lexicalTerms,
		PropositionRoles roles,
		Set<String> requiredRelationAnchors,
		Set<String> requiredConditionAnchors,
		Set<String> requiredNumericAnchors,
		Set<String> targetScopeAnchors,
		Set<SemanticCategory> categories,
		boolean conditional,
		boolean narrowingCondition,
		Set<String> exclusiveRoleAnchors,
		boolean disjunctiveCoordination,
		boolean ambiguousSubjectAttribution,
		String normalizedText
	) {
		static ClaimSemantics from(String text) {
			String sourceText = String.valueOf(text == null ? "" : text);
			String normalized = KoreanQueryNormalizer.normalizeForMatch(sourceText);
			String conditionText = LEADING_SUMMARY_DISCOURSE_FRAME.matcher(sourceText)
				.replaceFirst("");
			String conditionNormalized = KoreanQueryNormalizer.normalizeForMatch(conditionText);
			TargetMode targetMode = containsAny(normalized,
				"비대상", "대상에서제외", "제외대상", "면제", "대상에포함되지",
				"대상에포함하지", "대상에포함안", "해당하지")
				|| hasNegatedTargetCopula(normalized)
				|| hasNegatedTargetClassification(normalized)
				|| hasNegatedTargetInclusion(normalized)
				? TargetMode.EXCLUDED
				: containsAny(normalized,
					"대상입니다", "대상이다", "대상이며", "대상이고", "대상으로", "대상임",
					"대상에포함", "적용대상", "해당합니다", "해당한다", "포함됩니다", "포함된다")
					? TargetMode.INCLUDED : TargetMode.UNSPECIFIED;
			if (targetMode == TargetMode.UNSPECIFIED && normalized.endsWith("대상")) {
				targetMode = TargetMode.INCLUDED;
			}
			if (targetMode == TargetMode.INCLUDED
				&& GENERIC_TARGET_DEFINITION_LABEL.matcher(normalized).matches()) {
				targetMode = TargetMode.UNSPECIFIED;
			}
			ObligationMode obligationMode = containsAny(normalized,
				"불필요", "하지않아도", "안해도", "생략할수", "의무가없", "요구되지않", "요구하지않")
				? ObligationMode.NOT_REQUIRED
				: containsAny(normalized,
					"하여야", "해야", "필수", "받아야", "제출해야", "고지해야", "알려야", "의무",
					"요구됩니다", "요구된다", "요구합니다", "요구한다", "요구함")
					? ObligationMode.REQUIRED : ObligationMode.UNSPECIFIED;
			String permissionSemanticsText = normalized
				.replaceAll("금지(?=(?:신청서|요청서))", "")
				.replaceAll("금지(?=를?(?:신청|요청)(?:을|를)?(?:할수|가능|해야|하여야))", "")
				.replaceAll(
					"금지(?=를?(?:바로|직접|즉시|곧바로|우선|먼저|별도로|재차|다시)"
						+ "(?:신청|요청|청구|요구)(?:을|를)?(?:할수|가능|해야|하여야))",
					""
				)
				.replaceAll(
					"금지(?=조치(?:를)?(?:(?:신청|요청|청구|요구)(?:을|를)?)?(?:할수|가능|해야|하여야))",
					""
				)
				.replaceAll(
					"금지(?=[\\p{IsHangul}a-z0-9]{0,40}"
						+ "(?:신청|요청|청구|요구)(?:을|를)?(?:할수|가능|해야|하여야))",
					""
				);
			boolean explicitlyProhibited = containsAny(
				normalized,
				"불가능", "할수없", "허용되지않", "허용하지않", "허용안"
			)
				|| containsAny(permissionSemanticsText, "금지");
			PermissionMode permissionMode = explicitlyProhibited
				? PermissionMode.PROHIBITED
				: containsAny(normalized, "가능", "할수있", "허용")
					? PermissionMode.ALLOWED : PermissionMode.UNSPECIFIED;
			Set<String> permissionActions = permissionActions(text);
			Set<String> negatedPredicateActions = predicateActions(text, NEGATED_PREDICATE_ACTION);
			Set<String> finiteAffirmedPredicateActions = predicateActions(
				text,
				AFFIRMED_PREDICATE_ACTION
			);
			Set<String> plannedPredicateActions = predicateActions(
				text,
				PLANNED_PREDICATE_ACTION
			);
			Set<String> ongoingPredicateActions = predicateActions(
				text,
				ONGOING_PREDICATE_ACTION
			);
			Set<String> affirmedPredicateActions = new LinkedHashSet<>(
				finiteAffirmedPredicateActions
			);
			if (negatedPredicateActions.isEmpty()) {
				affirmedPredicateActions.addAll(
					predicateActions(text, AFFIRMED_NOMINAL_PREDICATE_ACTION)
				);
			}
			affirmedPredicateActions.addAll(
				terminalNominalPredicateActions(text)
			);
			Set<String> responsibilityBearers = responsibilityBearers(text);
			Set<String> universalScopeAnchors = universalScopeAnchors(text);
			EnumerationScope enumerationScope = enumerationScope(text);
			Set<String> lexicalTerms = lexicalTerms(text);
			Set<String> requiredRelationAnchors = requiredRelationAnchors(text);
			Set<String> requiredConditionAnchors = requiredConditionAnchors(conditionText);
			Set<String> requiredNumericAnchors = requiredNumericAnchors(text);
			Set<String> targetScopeAnchors = targetScopeAnchors(text);
			Set<SemanticCategory> categories = new LinkedHashSet<>();
			if (containsAny(normalized, "수의계약", "경쟁입찰", "계약방식", "계약방법")) {
				categories.add(SemanticCategory.CONTRACT_METHOD);
			}
			if (containsAny(normalized, "과태료", "벌칙", "제재", "처분", "불이익")) {
				categories.add(SemanticCategory.SANCTION);
			}
			if (containsAny(normalized, "기한", "기간", "이내", "까지")) {
				categories.add(SemanticCategory.DEADLINE);
			}
			boolean conditional = containsAny(conditionNormalized,
				"경우", "때에는", "일때", "이면", "라면", "조건", "한하여",
				"하면", "한다면", "되면", "된다면", "받으면", "있으면", "없으면",
				"않으면", "지나면", "넘으면")
				|| POST_EVENT_CONDITION.matcher(conditionNormalized).find();
			boolean narrowingCondition = containsAny(conditionNormalized,
				"이상", "이하", "미만", "초과", "한하여", "일정규모", "특정", "요건을충족", "조건을충족")
				|| ADDITIONAL_RESTRICTIVE_REQUIREMENT.matcher(conditionNormalized).find()
				|| ADDITIONAL_APPROVAL_REQUIREMENT.matcher(conditionNormalized).find()
				|| ATTRIBUTIVE_SCOPE_CONDITION.matcher(conditionText).find()
				|| (permissionMode != PermissionMode.UNSPECIFIED
					&& ADDITIONAL_MODAL_REQUIREMENT.matcher(conditionNormalized).find());
			Set<String> exclusiveRoleAnchors = exclusiveRoleAnchors(text);
			boolean disjunctiveCoordination = containsAny(normalized, "또는", "혹은", "거나");
			boolean ambiguousSubjectAttribution = ambiguousSubjectAttribution(text);
			return new ClaimSemantics(
				targetMode,
				obligationMode,
				permissionMode,
				permissionActions,
				finiteAffirmedPredicateActions,
				Set.copyOf(affirmedPredicateActions),
				negatedPredicateActions,
				plannedPredicateActions,
				ongoingPredicateActions,
				responsibilityBearers,
				universalScopeAnchors,
				enumerationScope.anchors(),
				enumerationScope.openEnded(),
				lexicalTerms,
				PropositionRoles.from(text),
				requiredRelationAnchors,
				requiredConditionAnchors,
				requiredNumericAnchors,
				targetScopeAnchors,
				Set.copyOf(categories),
				conditional,
				narrowingCondition,
				exclusiveRoleAnchors,
				disjunctiveCoordination,
				ambiguousSubjectAttribution,
				normalized
			);
		}

		boolean hasExplicitSemantics() {
			return targetMode != TargetMode.UNSPECIFIED
				|| obligationMode != ObligationMode.UNSPECIFIED
				|| permissionMode != PermissionMode.UNSPECIFIED
				|| !affirmedPredicateActions.isEmpty()
				|| !negatedPredicateActions.isEmpty()
				|| !plannedPredicateActions.isEmpty()
				|| !ongoingPredicateActions.isEmpty()
				|| !responsibilityBearers.isEmpty()
				|| !categories.isEmpty();
		}

		private static boolean containsAny(String text, String... terms) {
			for (String term : terms) {
				if (text.contains(KoreanQueryNormalizer.normalizeForMatch(term))) {
					return true;
				}
			}
			return false;
		}

		private static Set<String> exclusiveRoleAnchors(String text) {
			String source = String.valueOf(text == null ? "" : text);
			Matcher matcher = EXCLUSIVE_ROLE_ANCHOR.matcher(source);
			Set<String> anchors = new LinkedHashSet<>();
			while (matcher.find()) {
				String canonical = PropositionRoles.qualifiedRoleIdentity(
					source,
					matcher.start(1),
					matcher.group(1)
				);
				if (canonical.length() >= 2) {
					String roleType = matcher.group(2) == null ? "role:" : "recipient:";
					anchors.add(roleType + canonical);
				}
			}
			return Set.copyOf(anchors);
		}

		private static boolean ambiguousSubjectAttribution(String text) {
			String source = String.valueOf(text == null ? "" : text);
			Matcher attributive = ATTRIBUTIVE_OBJECT_SEQUENCE.matcher(source);
			if (!attributive.find()) {
				return false;
			}
			Matcher subjects = SUBJECT_ROLE.matcher(source);
			int count = 0;
			int firstStart = -1;
			int secondEnd = -1;
			char firstParticle = '\0';
			char secondParticle = '\0';
			while (subjects.find()) {
				if (isAttributiveVerbRoleToken(source, subjects)) {
					continue;
				}
				count++;
				if (count == 1) {
					firstStart = subjects.start();
					firstParticle = source.charAt(subjects.end() - 1);
				}
				else if (count == 2) {
					secondEnd = subjects.end();
					secondParticle = source.charAt(subjects.end() - 1);
				}
				else {
					return true;
				}
			}
			if (count < 2) {
				return false;
			}
			boolean leadingAttributiveScope = (firstParticle == '이' || firstParticle == '가')
				&& (secondParticle == '은' || secondParticle == '는')
				&& attributive.start() <= firstStart
				&& attributive.end() >= secondEnd;
			return !leadingAttributiveScope;
		}

		private static Set<String> permissionActions(String text) {
			String searchable = String.valueOf(text == null ? "" : text)
				.replaceAll("[^\\p{IsHangul}\\p{Alnum}]+", " ")
				.toLowerCase()
				.trim();
			if (searchable.isBlank()) {
				return Set.of();
			}
			Matcher matcher = PERMISSION_ACTION.matcher(searchable);
			Set<String> actions = new LinkedHashSet<>();
			while (matcher.find()) {
				String action = KoreanQueryNormalizer.normalizeQueryTerm(matcher.group(1));
				if (action.length() >= 2) {
					actions.add(action);
				}
			}
			return Set.copyOf(actions);
		}

		private static Set<String> predicateActions(String text, Pattern pattern) {
			Matcher matcher = pattern.matcher(String.valueOf(text == null ? "" : text));
			Set<String> actions = new LinkedHashSet<>();
			while (matcher.find()) {
				String action = canonicalPredicateAction(matcher.group(1));
				if (!action.isBlank()
					&& !STOPWORDS.contains(action)
					&& !PERMISSION_PREDICATE_TERMS.contains(action)) {
					actions.add(action);
				}
			}
			return Set.copyOf(actions);
		}

		private static String canonicalPredicateAction(String rawAction) {
			String action = KoreanQueryNormalizer.normalizeQueryTerm(rawAction);
			for (String suffix : List.of("하여야", "되어야", "해야")) {
				if (action.endsWith(suffix) && action.length() > suffix.length() + 1) {
					return action.substring(0, action.length() - suffix.length());
				}
			}
			return action;
		}

		private static Set<String> responsibilityBearers(String text) {
			String source = String.valueOf(text == null ? "" : text);
			Set<String> bearers = new LinkedHashSet<>();
			for (Pattern pattern : List.of(
				RESPONSIBILITY_RECIPIENT,
				RESPONSIBILITY_ACTOR,
				RESPONSIBILITY_POSSESSOR
			)) {
				Matcher matcher = pattern.matcher(source);
				while (matcher.find()) {
					String bearer = KoreanQueryNormalizer.normalizeQueryTerm(matcher.group(1));
					if (bearer.length() >= 2 && !STOPWORDS.contains(bearer)) {
						bearers.add(bearer);
					}
				}
			}
			return Set.copyOf(bearers);
		}

		private static Set<String> universalScopeAnchors(String text) {
			String searchable = String.valueOf(text == null ? "" : text)
				.replaceAll("[^\\p{IsHangul}\\p{Alnum}]+", " ")
				.toLowerCase()
				.trim();
			if (searchable.isBlank()) {
				return Set.of();
			}
			String[] tokens = searchable.split("\\s+");
			Set<String> anchors = new LinkedHashSet<>();
			for (int index = 0; index < tokens.length; index++) {
				String token = KoreanQueryNormalizer.normalizeForMatch(tokens[index]);
				for (String cue : UNIVERSAL_SCOPE_CUES) {
					if (token.equals(cue) && index + 1 < tokens.length) {
						for (int cursor = index + 1;
							cursor < tokens.length && cursor <= index + 3;
							cursor++) {
							addUniversalScopeAnchor(anchors, tokens[cursor]);
							if (endsUniversalNounPhrase(tokens[cursor])) {
								break;
							}
						}
					}
					else if (token.startsWith(cue) && token.length() > cue.length() + 1) {
						addUniversalScopeAnchor(anchors, token.substring(cue.length()));
					}
				}
			}
			return Set.copyOf(anchors);
		}

		private static void addUniversalScopeAnchor(Set<String> anchors, String candidate) {
			String anchor = canonicalScopeTerm(candidate);
			if (!anchor.isBlank()
				&& !anchor.endsWith("적으로")
				&& !anchor.equals("적")
				&& !TARGET_SCOPE_QUALIFIER_STOPWORDS.contains(anchor)) {
				anchors.add(anchor);
			}
		}

		private static boolean endsUniversalNounPhrase(String token) {
			String surface = KoreanQueryNormalizer.normalizeForMatch(token);
			return surface.matches(
				".*(?:입니다만|입니다|이었다|였으며|이라고|이라는|이다|임"
					+ "|에게서|으로부터|에서는|에게|에서|부터|까지"
					+ "|은|는|이|가|을|를|의|에|로|도|만|와|과)$"
			);
		}

		private static EnumerationScope enumerationScope(String text) {
			Matcher matcher = OPEN_ENDED_ENUMERATION.matcher(
				String.valueOf(text == null ? "" : text)
			);
			Set<String> anchors = new LinkedHashSet<>();
			boolean openEnded = false;
			while (matcher.find()) {
				openEnded = true;
				String list = matcher.group(1)
					.replaceAll("(?i)h\\s*/\\s*w", "hardware")
					.replaceFirst(
						"^\\s*(?:(?:비대상|대상|제외|예외)\\s*)?"
							+ "(?:사례|예시)(?:로는|로|는)?\\s*",
						""
					)
					.replaceAll("\\s+(?:및|또는)\\s+", "·")
					.replaceAll("[ㆍ/,]", "·");
				for (String part : list.split("·")) {
					anchors.addAll(scopeTerms(part));
				}
			}
			return new EnumerationScope(Set.copyOf(anchors), openEnded);
		}

		private static List<String> scopeTerms(String text) {
			String searchable = String.valueOf(text == null ? "" : text)
				.replaceAll("(?i)h\\s*/\\s*w", " hardware ")
				.replaceAll("[^\\p{IsHangul}\\p{Alnum}]+", " ")
				.toLowerCase()
				.trim();
			if (searchable.isBlank()) {
				return List.of();
			}
			List<String> terms = new ArrayList<>();
			for (String token : searchable.split("\\s+")) {
				String term = canonicalScopeTerm(token);
				if (term.length() >= 2 && !STOPWORDS.contains(term)) {
					terms.add(term);
				}
			}
			return List.copyOf(terms);
		}

		private static String canonicalScopeTerm(String raw) {
			String term = KoreanQueryNormalizer.normalizeForMatch(
				String.valueOf(raw == null ? "" : raw)
			);
			for (String suffix : List.of(
				"입니다만", "입니다", "이었다", "였으며", "이라고", "이라는", "이다", "임"
			)) {
				if (term.endsWith(suffix) && term.length() > suffix.length() + 1) {
					term = term.substring(0, term.length() - suffix.length());
					break;
				}
			}
			term = KoreanQueryNormalizer.stripTrailingJosa(term);
			return canonicalSoftwareTerm(term);
		}

		private static Set<String> terminalNominalPredicateActions(String text) {
			String source = String.valueOf(text == null ? "" : text).trim();
			if (source.isBlank()) {
				return Set.of();
			}
			if (TERMINAL_NOMINAL_COORDINATION.matcher(source).find()) {
				return Set.of();
			}
			String terminalToken = source
				.replaceFirst("[.!?]+$", "")
				.replaceAll("^.*\\s+", "");
			String terminalSurface = KoreanQueryNormalizer.normalizeForMatch(terminalToken);
			if (PERMISSION_PREDICATE_TERMS.stream().anyMatch(terminalSurface::contains)) {
				return Set.of();
			}
			if (terminalSurface.matches(
				".*(?:습니다|ㅂ니다|니다|합니다|됩니다|한다|된다|이다|아니다|않는다|못한다|함|됨)$"
			)) {
				return Set.of();
			}
			String normalized = KoreanQueryNormalizer.normalizeQueryTerm(terminalToken);
			if (normalized.length() < 2
				|| STOPWORDS.contains(normalized)) {
				return Set.of();
			}
			return Set.of(normalized);
		}

		private static Set<String> lexicalTerms(String text) {
			String searchable = String.valueOf(text == null ? "" : text)
				.replaceAll("[^\\p{IsHangul}\\p{Alnum}]+", " ")
				.toLowerCase()
				.trim();
			if (searchable.isBlank()) {
				return Set.of();
			}
			Set<String> terms = new LinkedHashSet<>();
			for (String token : searchable.split("\\s+")) {
				String term = KoreanQueryNormalizer.normalizeQueryTerm(token);
				if (term.length() >= 2 && !STOPWORDS.contains(term)) {
					terms.add(term);
				}
			}
			return Set.copyOf(terms);
		}

		private static Set<String> requiredRelationAnchors(String text) {
			String searchable = String.valueOf(text == null ? "" : text)
				.replaceAll("[^\\p{IsHangul}\\p{Alnum}]+", " ")
				.toLowerCase()
				.trim();
			Matcher matcher = ADDITIVE_RELATION_ANCHOR.matcher(searchable);
			Set<String> anchors = new LinkedHashSet<>();
			while (matcher.find()) {
				if (matcher.group(1).endsWith("라")) {
					continue;
				}
				String anchor = KoreanQueryNormalizer.normalizeForMatch(matcher.group(1));
				if (anchor.length() >= 2) {
					anchors.add(anchor);
				}
			}
			matcher = EXPLICIT_RELATION_CUE_ANCHOR.matcher(searchable);
			while (matcher.find()) {
				String anchor = KoreanQueryNormalizer.normalizeForMatch(matcher.group(1));
				if (anchor.length() >= 3) {
					anchors.add(anchor);
				}
			}
			matcher = RELATION_TOPIC_ANCHOR.matcher(searchable);
			while (matcher.find()) {
				String anchor = KoreanQueryNormalizer.normalizeForMatch(matcher.group(1));
				if (anchor.length() >= 2) {
					anchors.add(anchor);
				}
			}
			return Set.copyOf(anchors);
		}

		private static Set<String> requiredConditionAnchors(String text) {
			String searchable = String.valueOf(text == null ? "" : text)
				.replaceAll("[^\\p{IsHangul}\\p{Alnum}%,.]+", " ")
				.toLowerCase()
				.trim();
			Matcher matcher = NUMERIC_NARROWING_ANCHOR.matcher(searchable);
			Set<String> anchors = new LinkedHashSet<>();
			while (matcher.find()) {
				String anchor = KoreanQueryNormalizer.normalizeForMatch(matcher.group(1));
				if (anchor.length() >= 2) {
					anchors.add(anchor);
				}
			}
			matcher = SUFFIX_CONDITION_ANCHOR.matcher(searchable);
			while (matcher.find()) {
				String anchor = KoreanQueryNormalizer.normalizeForMatch(matcher.group(1));
				if (anchor.length() >= 2) {
					anchors.add(anchor);
				}
			}
			matcher = VERB_ENDING_CONDITION_ANCHOR.matcher(searchable);
			while (matcher.find()) {
				String anchor = KoreanQueryNormalizer.normalizeForMatch(matcher.group(1));
				if ("해당".equals(anchor)) {
					String prefix = searchable.substring(0, matcher.start(1)).trim();
					String previousToken = prefix.replaceFirst("^.*\\s+", "");
					String classifiedNoun = KoreanQueryNormalizer.stripTrailingJosa(
						KoreanQueryNormalizer.normalizeForMatch(previousToken)
					);
					if (classifiedNoun.length() >= 2 && !STOPWORDS.contains(classifiedNoun)) {
						anchors.add(classifiedNoun);
					}
					continue;
				}
				if (anchor.length() >= 2 && !STOPWORDS.contains(anchor)) {
					anchors.add(anchor);
				}
			}
			matcher = NAMED_CONDITION_PREFIX.matcher(searchable);
			while (matcher.find()) {
				String[] tokens = matcher.group(1).split("\\s+");
				int added = 0;
				for (int index = tokens.length - 1; index >= 0 && added < 2; index--) {
					String anchor = KoreanQueryNormalizer.normalizeQueryTerm(tokens[index]);
					if (anchor.length() >= 2 && !STOPWORDS.contains(anchor)) {
						anchors.add(anchor);
						added++;
					}
				}
			}
			matcher = CASE_CONDITION_PREFIX.matcher(searchable);
			while (matcher.find()) {
				for (String token : matcher.group(1).split("\\s+")) {
					String anchor = KoreanQueryNormalizer.normalizeForMatch(token);
					if (anchor.length() >= 2 && !STOPWORDS.contains(anchor)) {
						anchors.add(anchor);
					}
				}
			}
			matcher = PURPOSE_CONDITION_PREFIX.matcher(searchable);
			while (matcher.find()) {
				String[] tokens = matcher.group(1).split("\\s+");
				for (int index = tokens.length - 1; index >= 0; index--) {
					String anchor = KoreanQueryNormalizer.normalizeQueryTerm(tokens[index]);
					if (anchor.length() >= 2
						&& !STOPWORDS.contains(anchor)
						&& !Set.of("및", "또는", "위하여", "위해").contains(anchor)) {
						anchors.add(anchor);
					}
				}
			}
			matcher = ATTRIBUTIVE_SCOPE_CONDITION.matcher(
				String.valueOf(text == null ? "" : text)
			);
			while (matcher.find()) {
				String[] tokens = matcher.group(1).trim().split("\\s+");
				int added = 0;
				for (int index = tokens.length - 1; index >= 0 && added < 2; index--) {
					String anchor = KoreanQueryNormalizer.normalizeQueryTerm(tokens[index]);
					if (anchor.length() >= 2
						&& !STOPWORDS.contains(anchor)
						&& !Set.of("등", "및", "또는").contains(anchor)) {
						anchors.add(anchor);
						added++;
					}
				}
			}
			return Set.copyOf(anchors);
		}

		private static Set<String> targetScopeAnchors(String text) {
			String searchable = targetScopeSearchableText(text);
			if (searchable.isBlank()) {
				return Set.of();
			}
			String[] tokens = searchable.split("\\s+");
			Set<String> anchors = new LinkedHashSet<>();
			for (int index = 0; index < tokens.length; index++) {
				String targetToken = KoreanQueryNormalizer.normalizeForMatch(tokens[index]);
				if (!targetToken.contains("대상")) {
					continue;
				}
				String embeddedCandidate = embeddedTargetScopeCandidate(targetToken);
				if (isNamedTargetScope(embeddedCandidate)) {
					anchors.add(qualifiedTargetScopeAnchor(tokens, index, embeddedCandidate));
					continue;
				}
				int inspected = 0;
				for (int candidateIndex = index - 1;
					candidateIndex >= 0 && inspected < 3;
					candidateIndex--) {
					String candidate = targetScopeCandidate(tokens[candidateIndex]);
					if (candidate.isBlank() || TARGET_SCOPE_CONNECTORS.contains(candidate)) {
						continue;
					}
					inspected++;
					if (isNamedTargetScope(candidate)) {
						anchors.add(qualifiedTargetScopeAnchor(tokens, candidateIndex, candidate));
						break;
					}
				}
			}
			return Set.copyOf(anchors);
		}

		private static Set<String> namedTargetScopes(String text) {
			String searchable = targetScopeSearchableText(text);
			if (searchable.isBlank()) {
				return Set.of();
			}
			String[] tokens = searchable.split("\\s+");
			Set<String> anchors = new LinkedHashSet<>();
			for (int index = 0; index < tokens.length; index++) {
				String candidate = targetScopeCandidate(tokens[index]);
				String embeddedCandidate = embeddedTargetScopeCandidate(candidate);
				if (isNamedTargetScope(embeddedCandidate)) {
					anchors.add(qualifiedTargetScopeAnchor(tokens, index, embeddedCandidate));
				}
				else if (isNamedTargetScope(candidate)) {
					anchors.add(qualifiedTargetScopeAnchor(tokens, index, candidate));
				}
			}
			return Set.copyOf(anchors);
		}

		private static String targetScopeSearchableText(String text) {
			return String.valueOf(text == null ? "" : text)
				.replaceAll("[·ㆍ/&,，–—-]", " 및 ")
				.replaceAll("[^\\p{IsHangul}\\p{Alnum}]+", " ")
				.toLowerCase()
				.trim();
		}

		private static String qualifiedTargetScopeAnchor(
			String[] tokens,
			int scopeIndex,
			String scope
		) {
			if (scopeIndex <= 0) {
				return canonicalTargetScopeAnchor(scope);
			}
			String rawQualifier = tokens[scopeIndex - 1];
			if (hasTrailingRoleJosa(rawQualifier)) {
				return canonicalTargetScopeAnchor(scope);
			}
			String qualifier = KoreanQueryNormalizer.normalizeQueryTerm(rawQualifier);
			if (scopeIndex >= 2) {
				String precedingToken = KoreanQueryNormalizer.normalizeForMatch(
					tokens[scopeIndex - 2]
				);
				String attachedConnector = trailingTargetScopeConnector(precedingToken);
				if (!attachedConnector.isBlank()) {
					String firstQualifier = KoreanQueryNormalizer.normalizeQueryTerm(
						precedingToken.substring(
							0,
							precedingToken.length() - attachedConnector.length()
						)
					);
					if (isUsableTargetScopeQualifier(firstQualifier)
						&& isUsableTargetScopeQualifier(qualifier)) {
						return canonicalTargetScopeAnchor(
							firstQualifier + attachedConnector + qualifier + scope
						);
					}
				}
			}
			if (scopeIndex >= 3
				&& Set.of("및", "또는").contains(
					KoreanQueryNormalizer.normalizeForMatch(tokens[scopeIndex - 2])
				)) {
				String firstQualifier = KoreanQueryNormalizer.normalizeQueryTerm(
					tokens[scopeIndex - 3]
				);
				if (isUsableTargetScopeQualifier(firstQualifier)
					&& isUsableTargetScopeQualifier(qualifier)) {
					return canonicalTargetScopeAnchor(
						firstQualifier
							+ KoreanQueryNormalizer.normalizeForMatch(tokens[scopeIndex - 2])
							+ qualifier
							+ scope
					);
				}
			}
			if (!isUsableTargetScopeQualifier(qualifier)) {
				return canonicalTargetScopeAnchor(scope);
			}
			return canonicalTargetScopeAnchor(qualifier + scope);
		}

		private static String trailingTargetScopeConnector(String token) {
			for (String connector : List.of("와", "과")) {
				if (token.endsWith(connector)
					&& token.length() > connector.length() + 1) {
					return connector;
				}
			}
			return "";
		}

		private static boolean isUsableTargetScopeQualifier(String qualifier) {
			return qualifier.length() >= 2
				&& qualifier.chars().noneMatch(Character::isDigit)
				&& !STOPWORDS.contains(qualifier)
				&& !TARGET_SCOPE_QUALIFIER_STOPWORDS.contains(qualifier)
				&& !TARGET_SCOPE_CONNECTORS.contains(qualifier);
		}

		private static boolean hasTrailingRoleJosa(String token) {
			String normalized = KoreanQueryNormalizer.normalizeForMatch(token);
			return List.of(
				"이면", "라면", "일때", "인경우", "일경우",
				"에게", "한테", "에서", "으로", "부터", "까지", "하고",
				"은", "는", "이", "가", "을", "를", "와", "과", "도", "만", "에"
			)
				.stream()
				.anyMatch(josa -> normalized.endsWith(josa) && normalized.length() > josa.length() + 1);
		}

		private static String targetScopeCandidate(String token) {
			String normalized = KoreanQueryNormalizer.normalizeForMatch(token);
			return isNamedTargetScope(normalized)
				? normalized
				: KoreanQueryNormalizer.stripTrailingJosa(normalized);
		}

		private static String canonicalTargetScopeAnchor(String value) {
			String canonical = KoreanQueryNormalizer.normalizeForMatch(value)
				.replace("소프트웨어사업", "sw")
				.replace("소프트웨어", "sw")
				.replace("sw사업", "sw");
			if (canonical.endsWith("과업심의")) {
				String qualifier = canonical.substring(0, canonical.length() - "과업심의".length());
				if (Set.of("sw", "공공sw").contains(qualifier)) {
					return "과업심의";
				}
			}
			return canonical;
		}

		private static String embeddedTargetScopeCandidate(String targetToken) {
			int targetIndex = targetToken.indexOf("대상");
			if (targetIndex <= 0) {
				return "";
			}
			String candidate = targetToken.substring(0, targetIndex);
			boolean stripped;
			do {
				stripped = false;
				for (String connector : TARGET_SCOPE_CONNECTORS) {
					if (candidate.endsWith(connector) && candidate.length() > connector.length()) {
						candidate = candidate.substring(0, candidate.length() - connector.length());
						stripped = true;
						break;
					}
				}
			} while (stripped);
			return isNamedTargetScope(candidate)
				? candidate
				: KoreanQueryNormalizer.stripTrailingJosa(candidate);
		}

		private static boolean isNamedTargetScope(String candidate) {
			return TARGET_SCOPE_SUFFIXES.stream()
				.anyMatch(suffix -> candidate.endsWith(suffix) && candidate.length() > suffix.length());
		}

		private static Set<String> requiredNumericAnchors(String text) {
			String source = String.valueOf(text == null ? "" : text);
			Matcher matcher = NUMERIC_VALUE.matcher(source);
			Set<String> anchors = new LinkedHashSet<>();
			while (matcher.find()) {
				int boundary = lastClauseBoundary(source, matcher.start());
				String prefix = source.substring(boundary + 1, matcher.start()).trim();
				if (prefix.isBlank()) {
					continue;
				}
				String[] tokens = prefix.split("\\s+");
				int added = 0;
				for (int index = tokens.length - 1; index >= 0 && added < 2; index--) {
					String raw = tokens[index];
					if (raw.chars().anyMatch(Character::isDigit)) {
						continue;
					}
					String cleaned = raw.replaceAll("[^\\p{IsHangul}\\p{Alnum}]", "");
					String anchor = KoreanQueryNormalizer.normalizeQueryTerm(cleaned);
					if (anchor.length() >= 2
						&& !STOPWORDS.contains(anchor)
						&& !isNumericContextFiller(cleaned)) {
						anchors.add(anchor);
						added++;
					}
				}
			}
			return Set.copyOf(anchors);
		}

		private static boolean isNumericContextFiller(String token) {
			String normalized = KoreanQueryNormalizer.normalizeForMatch(token);
			return normalized.endsWith("적으로")
				|| Set.of("전체", "대체로", "통상", "통상적으로", "각각").contains(normalized);
		}

		private static int lastClauseBoundary(String source, int endExclusive) {
			int boundary = -1;
			for (char delimiter : new char[] {'.', '!', '?', ';', '；', ',', '\n', '\r'}) {
				boundary = Math.max(boundary, source.lastIndexOf(delimiter, Math.max(0, endExclusive - 1)));
			}
			return boundary;
		}
	}

	private record PropositionRoles(
		Set<RoleIdentity> subjects,
		Set<RoleIdentity> objects,
		Set<RoleIdentity> recipients,
		Set<PermissionTargetAlternative> permissionTargets
	) {
		private static final Set<String> ROLE_MODIFIER_BOUNDARY_WORDS = Set.of(
			"및", "또는", "혹은", "하고", "등",
			"모든", "전체", "각", "각종",
			"위해", "위하여", "위한", "대한", "관한", "따른", "통해",
			"즉시", "바로", "반드시", "직접", "별도로", "우선", "먼저", "다시",
			"금지", "허용", "가능", "불가능", "하는", "하게", "하지", "못하게"
		);
		private static final List<String> ROLE_MODIFIER_BOUNDARY_SUFFIXES = List.of(
			"에게", "한테", "에서", "으로", "부터", "까지"
		);
		private static final Set<String> ROLE_MODIFIER_SINGLE_PARTICLES = Set.of(
			"은", "는", "이", "가", "을", "를", "와", "과"
		);
		private static final List<String> ROLE_MODIFIER_PREDICATE_SUFFIXES = List.of(
			"하는", "하게", "하지", "못하게", "하여", "해서", "되고", "되며", "이며", "이고"
		);
		private static final Set<String> LEXICAL_UI_ENDINGS = Set.of(
			"협의", "동의", "회의", "정의", "논의"
		);
		private static final Pattern EMBEDDED_ATTRIBUTIVE_REMAINDER = Pattern.compile(
			"^\\s+(?:(?:(?:직접|이미|새로|미리|다시)"
				+ "|[\\p{IsHangul}A-Za-z0-9]{1,20}(?:하게|히|도록))\\s+){0,2}"
				+ "[\\p{IsHangul}A-Za-z0-9]{1,40}(?:한|하는|할|된|되는|될|받은|받는|받을|있는|없는|던)\\s+"
				+ "(?:(?:모든|각|해당|관련|주요)\\s+){0,2}"
				+ "[\\p{IsHangul}A-Za-z0-9()·ㆍ/\\-]{2,}?"
				+ "(?:은|는|이|가|을|를|에게|한테|임|입니다|이다)(?=\\s|[.!?]|$)",
			Pattern.CASE_INSENSITIVE
		);

		static PropositionRoles from(String text) {
			Set<PermissionTargetAlternative> permissionTargets =
				ambiguousPermissionTargets(text);
			return new PropositionRoles(
				subjectRoleTokens(text, permissionTargets),
				objectRoleTokens(text, permissionTargets),
				roleIdentities(text, RECIPIENT_ROLE),
				permissionTargets
			);
		}

		private static Set<RoleIdentity> subjectRoleTokens(
			String text,
			Set<PermissionTargetAlternative> permissionTargets
		) {
			Set<RoleIdentity> tokens = new LinkedHashSet<>(
				subjectRoleIdentities(text, SUBJECT_ROLE, PARENTHETICAL_SUBJECT_ROLE)
			);
			Matcher matcher = COORDINATED_SUBJECT_ROLE.matcher(String.valueOf(text == null ? "" : text));
			while (matcher.find()) {
				addRoleIdentity(tokens, text, matcher.start(1), matcher.group(1));
				addRoleIdentity(tokens, text, matcher.start(2), matcher.group(2));
			}
			boolean hadExplicitSubject = !tokens.isEmpty();
			permissionTargets.forEach(alternative ->
				tokens.removeIf(role ->
					role.canonical().equals(alternative.qualifiedParticleStripped())
						|| (alternative.qualifiedParticleStripped().equals(alternative.particleStripped())
							&& role.canonical().equals(alternative.particleStripped()))
				)
			);
			if (!hadExplicitSubject && tokens.isEmpty()) {
				tokens.addAll(roleIdentities(text, LABELED_SUBJECT_ROLE));
			}
			tokens.removeIf(role ->
				role.canonical().endsWith("하")
					|| role.canonical().endsWith("되")
					|| role.canonical().endsWith("있")
					|| role.canonical().endsWith("없")
					|| NON_ENTITY_SUBJECT_ROLES.stream().anyMatch(
						nonEntity -> KoreanQueryNormalizer.normalizeQueryTerm(role.head()).endsWith(nonEntity)
					)
			);
			if (hasNegatedTargetCopula(KoreanQueryNormalizer.normalizeForMatch(text))) {
				tokens.removeIf(role -> "대상".equals(role.head()));
			}
			return Set.copyOf(tokens);
		}

		private static Set<RoleIdentity> objectRoleTokens(
			String text,
			Set<PermissionTargetAlternative> permissionTargets
		) {
			Set<RoleIdentity> tokens = new LinkedHashSet<>(roleIdentities(text, OBJECT_ROLE));
			Matcher matcher = COORDINATED_OBJECT_ROLE.matcher(String.valueOf(text == null ? "" : text));
			while (matcher.find()) {
				addRoleIdentity(tokens, text, matcher.start(1), matcher.group(1));
				addRoleIdentity(tokens, text, matcher.start(2), matcher.group(2));
			}
			Set<RoleIdentity> prohibitionObjects = prohibitionObjectRoleTokens(text, permissionTargets);
			if (!prohibitionObjects.isEmpty()) {
				boolean prohibitionRequest = PROHIBITION_REQUEST_OBJECT.matcher(
					KoreanQueryNormalizer.normalizeForMatch(text)
				).find();
				tokens.removeIf(role ->
					"금지".equals(role.canonical())
						|| (prohibitionRequest && role.canonical().endsWith("금지"))
				);
				tokens.addAll(prohibitionObjects);
			}
			return Set.copyOf(tokens);
		}

		private static Set<RoleIdentity> prohibitionObjectRoleTokens(
			String text,
			Set<PermissionTargetAlternative> permissionTargets
		) {
			Set<RoleIdentity> objects = new LinkedHashSet<>();
			Matcher matcher = PROHIBITION_OBJECT_ROLE.matcher(String.valueOf(text == null ? "" : text));
			while (matcher.find()) {
				String normalized = KoreanQueryNormalizer.normalizeForMatch(matcher.group(1))
					.replace("소프트웨어", "sw");
				boolean ambiguous = permissionTargets.stream().anyMatch(alternative ->
					alternative.surface().equals(normalized)
				);
				if (!ambiguous && normalized.length() >= 2) {
					addRoleIdentity(objects, text, matcher.start(1), matcher.group(1));
				}
			}
			return Set.copyOf(objects);
		}

		private static Set<PermissionTargetAlternative> ambiguousPermissionTargets(String text) {
			Set<RoleIdentity> subjects = subjectRoleIdentities(
				text,
				SUBJECT_ROLE,
				PARENTHETICAL_SUBJECT_ROLE
			);
			Set<PermissionTargetAlternative> alternatives = new LinkedHashSet<>();
			Matcher matcher = PERMISSION_TARGET_ROLE.matcher(String.valueOf(text == null ? "" : text));
			while (matcher.find()) {
				String surface = KoreanQueryNormalizer.normalizeForMatch(matcher.group(1))
					.replace("소프트웨어", "sw");
				for (String particle : List.of("은", "는", "이", "가")) {
					if (!surface.endsWith(particle) || surface.length() < 3) {
						continue;
					}
					String particleStripped = surface.substring(0, surface.length() - 1);
					if (subjects.stream().anyMatch(role ->
						role.canonical().equals(particleStripped)
							|| role.head().equals(particleStripped)
					)) {
						alternatives.add(new PermissionTargetAlternative(
							surface,
							particleStripped,
							qualifiedRoleIdentity(text, matcher.start(1), particleStripped)
						));
						break;
					}
				}
			}
			return Set.copyOf(alternatives);
		}

		private static Set<RoleIdentity> subjectRoleIdentities(String text, Pattern... patterns) {
			Set<RoleIdentity> identities = new LinkedHashSet<>();
			String source = String.valueOf(text == null ? "" : text);
			for (Pattern pattern : patterns) {
				Matcher matcher = pattern.matcher(source);
				while (matcher.find()) {
					if (pattern == SUBJECT_ROLE && isEmbeddedAttributiveSubject(source, matcher)) {
						continue;
					}
					addRoleIdentity(identities, source, matcher.start(1), matcher.group(1));
				}
			}
			return Set.copyOf(identities);
		}

		private static Set<RoleIdentity> roleIdentities(String text, Pattern... patterns) {
			Set<RoleIdentity> identities = new LinkedHashSet<>();
			String source = String.valueOf(text == null ? "" : text);
			for (Pattern pattern : patterns) {
				Matcher matcher = pattern.matcher(source);
				while (matcher.find()) {
					addRoleIdentity(identities, source, matcher.start(1), matcher.group(1));
				}
			}
			return Set.copyOf(identities);
		}

		private static void addRoleIdentity(
			Set<RoleIdentity> identities,
			String text,
			int roleStart,
			String rawRole
		) {
			String source = String.valueOf(rawRole == null ? "" : rawRole).trim();
			String[] parts = source.split("\\s+");
			String head = normalizeRoleToken(parts[parts.length - 1]);
			String canonical = source.matches(".*\\s+.*")
				? normalizeRoleToken(source)
				: qualifiedRoleIdentity(text, roleStart, rawRole);
			if (canonical.length() >= 2 && head.length() >= 2) {
				identities.add(new RoleIdentity(canonical, head));
			}
		}

		private static boolean isEmbeddedAttributiveSubject(String source, Matcher subjectMatcher) {
			if (isAttributiveVerbRoleToken(source, subjectMatcher)) {
				return true;
			}
			if (!looksLikeAttributiveSubject(source, subjectMatcher)) {
				return false;
			}
			Matcher otherSubject = SUBJECT_ROLE.matcher(source);
			while (otherSubject.find()) {
				if (otherSubject.start() == subjectMatcher.start()
					&& otherSubject.end() == subjectMatcher.end()) {
					continue;
				}
				char otherParticle = source.charAt(otherSubject.end() - 1);
				if (otherParticle == '은'
					|| otherParticle == '는'
					|| !looksLikeAttributiveSubject(source, otherSubject)) {
					return true;
				}
			}
			return false;
		}

		private static boolean looksLikeAttributiveSubject(String source, Matcher subjectMatcher) {
			if (subjectMatcher.end() <= 0) {
				return false;
			}
			char particle = source.charAt(subjectMatcher.end() - 1);
			if (particle != '이' && particle != '가') {
				return false;
			}
			return EMBEDDED_ATTRIBUTIVE_REMAINDER.matcher(
				source.substring(subjectMatcher.end())
			).find();
		}

		private static String qualifiedRoleIdentity(String text, int roleStart, String rawHead) {
			String head = normalizeRoleToken(rawHead);
			if (head.isBlank() || String.valueOf(rawHead).chars().anyMatch(Character::isWhitespace)) {
				return head;
			}
			String source = String.valueOf(text == null ? "" : text);
			String prefix = source.substring(0, Math.max(0, Math.min(roleStart, source.length())));
			int boundary = -1;
			for (char delimiter : new char[] {'.', '!', '?', ';', '；', ',', '，', ':', '：', '\n', '\r'}) {
				boundary = Math.max(boundary, prefix.lastIndexOf(delimiter));
			}
			String[] candidates = prefix.substring(boundary + 1).trim().split("\\s+");
			List<String> modifiers = new ArrayList<>();
			for (int index = candidates.length - 1; index >= 0; index--) {
				String cleaned = candidates[index]
					.replaceAll("^[^\\p{IsHangul}A-Za-z0-9()·ㆍ/\\-]+", "")
					.replaceAll("[^\\p{IsHangul}A-Za-z0-9()·ㆍ/\\-]+$", "");
				String normalized = normalizedRoleModifier(cleaned);
				if (normalized.length() < 2 || isRoleModifierBoundary(cleaned, normalized)) {
					break;
				}
				modifiers.add(0, normalized);
			}
			if (modifiers.isEmpty()) {
				return head;
			}
			return normalizeRoleToken(String.join("", modifiers) + head);
		}

		private static String normalizedRoleModifier(String rawToken) {
			String normalized = normalizeRoleToken(rawToken);
			if (rawToken.endsWith("의")
				&& rawToken.length() >= 3
				&& LEXICAL_UI_ENDINGS.stream().noneMatch(normalized::endsWith)) {
				return normalizeRoleToken(rawToken.substring(0, rawToken.length() - 1));
			}
			return normalized;
		}

		private static boolean isRoleModifierBoundary(String rawToken, String normalizedToken) {
			if (ROLE_MODIFIER_BOUNDARY_WORDS.contains(normalizedToken)) {
				return true;
			}
			if (ROLE_MODIFIER_BOUNDARY_SUFFIXES.stream().anyMatch(rawToken::endsWith)) {
				return true;
			}
			if (ROLE_MODIFIER_PREDICATE_SUFFIXES.stream().anyMatch(normalizedToken::endsWith)) {
				return true;
			}
			return ROLE_MODIFIER_SINGLE_PARTICLES.stream().anyMatch(particle ->
				hasSyntacticallyCompatibleTrailingParticle(rawToken, particle)
			);
		}

		private static boolean hasSyntacticallyCompatibleTrailingParticle(
			String rawToken,
			String particle
		) {
			if (rawToken.length() < 3 || !rawToken.endsWith(particle)) {
				return false;
			}
			char stemEnding = rawToken.charAt(rawToken.length() - particle.length() - 1);
			if (stemEnding < 0xAC00 || stemEnding > 0xD7A3) {
				return true;
			}
			boolean hasFinalConsonant = (stemEnding - 0xAC00) % 28 != 0;
			return Set.of("은", "이", "을", "과").contains(particle)
				? hasFinalConsonant
				: !hasFinalConsonant;
		}

		private static String normalizeRoleToken(String rawToken) {
			return KoreanQueryNormalizer.normalizeForMatch(rawToken)
				.replace("소프트웨어", "sw");
		}
	}

	private record RoleIdentity(String canonical, String head) {
		boolean qualified() {
			return !canonical.equals(head);
		}

		boolean coveredBy(RoleIdentity evidence) {
			if (canonical.equals(evidence.canonical())) {
				return true;
			}
			return qualified()
				&& !evidence.qualified()
				&& head.equals(evidence.canonical());
		}
	}

	private record PermissionTargetAlternative(
		String surface,
		String particleStripped,
		String qualifiedParticleStripped
	) {
		Set<String> identities() {
			Set<String> identities = new LinkedHashSet<>();
			identities.add(surface);
			identities.add(particleStripped);
			identities.add(qualifiedParticleStripped);
			return Set.copyOf(identities);
		}

		Set<String> alignmentIdentities() {
			if (!qualifiedParticleStripped.equals(particleStripped)) {
				return Set.of(qualifiedParticleStripped);
			}
			return Set.of(surface, particleStripped);
		}
	}

	private enum Relation {
		COMPATIBLE,
		CONTRADICTED,
		NOT_ENTAILED,
		NEUTRAL
	}

	private enum SemanticCategory {
		CONTRACT_METHOD,
		SANCTION,
		DEADLINE
	}

	private enum TargetMode {
		INCLUDED,
		EXCLUDED,
		UNSPECIFIED
	}

	private enum ObligationMode {
		REQUIRED,
		NOT_REQUIRED,
		UNSPECIFIED
	}

	private enum PermissionMode {
		ALLOWED,
		PROHIBITED,
		UNSPECIFIED
	}

	public enum Status {
		SUPPORTED,
		CONTRADICTED,
		CONFLICTED,
		INSUFFICIENT
	}

	public record Match(
		Status status,
		int groundNumber,
		String evidenceSentence,
		int overlapCount,
		double coverage,
		double score
	) {
		static Match insufficient() {
			return new Match(Status.INSUFFICIENT, 0, "", 0, 0.0d, 0.0d);
		}
	}
}
