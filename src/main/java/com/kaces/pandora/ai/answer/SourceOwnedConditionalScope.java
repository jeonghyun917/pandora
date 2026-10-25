package com.kaces.pandora.ai.answer;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Literal proof of an assumed business satisfying an explicitly referenced source condition. */
public final class SourceOwnedConditionalScope {
    private SourceOwnedConditionalScope() {}
    private static final Pattern PARAGRAPH=Pattern.compile("(?m)^\\s*([①②③])\\s*");
    private static final Pattern FIRST=Pattern.compile("^([가-힣ㆍ· ]{2,60}?(?:은|는))\\s+(국가기관(?:등)?의\\s+장이\\s+소프트웨어사업을\\s+[가-힣]{2,20}는\\s+경우)\\s+(.+)\\.$");
    private static final Pattern HYPOTHESIS=Pattern.compile("^([^,;.!?\\r\\n]{1,80}?)(?:이|가|도)\\s+([^,;.!?\\r\\n]{1,240}?)에\\s*해당하면\\s+([^;!?\\r\\n]{1,800})$");
    private static final Pattern ARTICLE=Pattern.compile("제\\s*\\d+\\s*조");
    private static final Pattern CHECKING_CLAUSE=Pattern.compile("^(?:이법(?:또는관련법령)?|관련법령|법령|법률)을준수(?:하였는지|하는지)를확인하고,?그결과를공개할수있다$");
    // Closed noun vocabulary: a hypothetical label must not carry an unverified proposition.
    private static final Pattern BUSINESS_LABEL=Pattern.compile("^(?:정보화|디지털|서비스|전산|소프트웨어|정보|시스템|SW|IT){1,4}(?:사업|시스템)$");
    private static final List<String> OUTER_RESTRICTIONS=List.of("다만","불구하고","한정","한하여","제외","예외","에만","만적용","만대상","만해당");
    private static final Pattern NON_APPLICABILITY=Pattern.compile("적용[^.。]{0,24}(?:않|아니|면제)|비대상");
    private static final Set<String> LEGAL_NORMS=Set.of("법제도","법령","관련법령","법률");
    private static final Pattern RECOGNIZED_LEGAL_BREACH=Pattern.compile("(?:이법(?:또는관련법령)?|관련법령|법령|법률)을위반하였다고(?:인정|판단)하는경우");
    private static final Pattern NEGATIVE_COMPLIANCE=Pattern.compile("^(?:준수(?:안하면|하지않으면|하지아니하면)|미준수(?:시|하면|인경우)|위반(?:하면|시))$");

    public record Rule(int owner,String scopeLiteral,String operativeLiteral,String originalContext,String premiseLiteral) {}
    public record BoundClaim(int owner,String operativeLiteral) {}

    public static List<Rule> index(List<LawAiAnswerGround> grounds) {
        if(grounds==null || grounds.size()>64)return List.of();
        var numbers=new HashSet<Integer>();var result=new ArrayList<Rule>();
        for(var ground:grounds){
            if(ground==null)continue;
            if(!numbers.add(ground.number()))return List.of();
            if(ground.number()<=0 || ground.chunkId()<=0 || ground.documentId()<=0
                || !("law".equals(ground.target()) || "admrul".equals(ground.target()))
                || !"direct".equals(ground.evidenceRole()))continue;
            String child=ground.matchedChildText(),parent=ground.parentContextText();
            if(child==null || (parent!=null && !parent.isBlank() && !canonical(parent).equals(canonical(child))))continue;
            original(ground.number(),ground.matchedChildText()).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    public static Optional<Rule> original(int owner,String source) {
        if(source==null || source.length()>4000 || source.indexOf('*')>=0 || source.contains("※")
            || source.contains("<") || source.contains(">") || OUTER_RESTRICTIONS.stream().anyMatch(canonical(source)::contains)
            || NON_APPLICABILITY.matcher(canonical(source)).find())return Optional.empty();
        var marker=PARAGRAPH.matcher(source);var starts=new ArrayList<Integer>();var ends=new ArrayList<Integer>();
        var labels=new ArrayList<String>();
        while(marker.find()){starts.add(marker.start());ends.add(marker.end());labels.add(marker.group(1));}
        if(!labels.equals(List.of("①","②","③")))return Optional.empty();
        if(!source.substring(0,starts.get(0)).isBlank())return Optional.empty();
        String first=source.substring(ends.get(0),starts.get(1)).trim();
        String second=source.substring(ends.get(1),starts.get(2)).trim();
        String owned=source.substring(starts.get(0),ends.get(2)).trim();
        if(ARTICLE.matcher(owned).find() || first.indexOf('.')!=first.length()-1
            || second.indexOf('.')!=second.length()-1)return Optional.empty();
        var condition=FIRST.matcher(first);if(!condition.matches())return Optional.empty();
        String tail=canonical(condition.group(3));
        if(!CHECKING_CLAUSE.matcher(tail).matches())return Optional.empty();
        String action=canonical(second);
        if(!action.startsWith(canonical(condition.group(1))) || !action.contains("제1항에따른확인결과"))return Optional.empty();
        var references=Pattern.compile("제\\d+항").matcher(action);int count=0;
        while(references.find()){if(!"제1항".equals(references.group()))return Optional.empty();count++;}
        if(count!=1 || action.contains("다만") || action.contains("예외"))return Optional.empty();
        return Optional.of(new Rule(owner,condition.group(2),second,owned,first));
    }

    public static List<BoundClaim> bind(String claim,List<Rule> rules) {
        if(claim==null || claim.length()>1200 || rules==null)return List.of();
        var hypothesis=HYPOTHESIS.matcher(claim.trim());if(!hypothesis.matches())return List.of();
        String variable=canonical(hypothesis.group(1));
        if(!BUSINESS_LABEL.matcher(variable).matches())return List.of();
        String scope=canonical(hypothesis.group(2)),conclusion=canonical(hypothesis.group(3));
        return rules.stream().filter(rule->scope.equals(canonical(rule.scopeLiteral()))
            && conclusion.equals(canonical(rule.operativeLiteral())))
            .map(rule->new BoundClaim(rule.owner(),hypothesis.group(3))).toList();
    }
    /** Each unmatched anchor still needs proof; this is not an alignment bypass. */
    public static boolean provesLegalCondition(String claim,List<String> anchors,List<Rule> rules) {
        var bound=bind(claim,rules);
        if(bound.isEmpty() || bound.stream().noneMatch(item->RECOGNIZED_LEGAL_BREACH.matcher(canonical(item.operativeLiteral())).find()))return false;
        boolean legalNorm=false;var remainder=new StringBuilder();String actual=canonical(claim);
        for(String anchor:anchors){
            String term=canonical(anchor);
            String noun=com.kaces.pandora.common.text.KoreanQueryNormalizer.stripTrailingJosa(term);
            if(LEGAL_NORMS.contains(term) || LEGAL_NORMS.contains(noun)){legalNorm=true;continue;}
            if(!actual.contains(term))remainder.append(term);
        }
        return legalNorm && NEGATIVE_COMPLIANCE.matcher(remainder).matches();
    }
    private static String canonical(String text){return Normalizer.normalize(text,Normalizer.Form.NFC).replaceAll("\\s+","");}
}
