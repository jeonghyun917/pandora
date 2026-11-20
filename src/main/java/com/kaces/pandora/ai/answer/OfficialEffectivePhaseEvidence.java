package com.kaces.pandora.ai.answer;

import java.time.LocalDate;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.time.format.DateTimeFormatter;
import java.net.URI;
import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawCommencementSourceRow;

/** General effective phase only; it never proves that every provision is effective. */
record OfficialEffectivePhaseEvidence(String lawId, String edition, long documentId,long chunkId,int chunkVersion,LocalDate effectiveFrom,
    LocalDate effectiveUntilExclusive, LocalDate referenceDate, LocalDate observedAt,
    String sourceSha256, OfficialCommencementEvidence commencement) {
    private static final Pattern ROW=Pattern.compile("lsViewLsHst2\\('([0-9]+)',\\s*'([0-9]{8})',\\s*'([0-9]+)',\\s*'([0-9]{8})',\\s*'[YN]',\\s*'0'\\)");
    private static final Pattern CALL=Pattern.compile("lsViewLsHst2\\(");
    private static final tools.jackson.databind.ObjectMapper JSON=new tools.jackson.databind.ObjectMapper();
    static Optional<OfficialEffectivePhaseEvidence> resolve(LawSemanticChunkRow anchor,
        LawCommencementSourceRow source, URI sourceUri, String html, LocalDate reference, LocalDate observed) {
        if(anchor==null || source==null || sourceUri==null || reference==null || observed==null
            || reference.isAfter(observed) || html==null || html.length()>200000 || !html.stripTrailing().endsWith("</html>"))
            return Optional.empty();
        var commencement=OfficialCommencementEvidence.resolve(anchor,source)
            .or(()->OfficialCommencementEvidence.resolveFromDetail(anchor,source));
        if(commencement.isEmpty()) return Optional.empty();
        try {
            var basic=JSON.readTree(source.rawJson()).path("법령").path("기본정보");
            String lawId=basic.path("법령ID").asString("");
            String number=basic.path("공포번호").asString("");
            String published=basic.path("공포일자").asString("");
            if(!lawId.matches("[0-9]+") || source.externalId()==null || !source.externalId().matches("[0-9]+")
                || !sourceUri.equals(historyUri(lawId,source.externalId()))) return Optional.empty();
            var rows=new ArrayList<HistoryRow>();
            var dates=new HashSet<LocalDate>();
            var matches=ROW.matcher(html);
            while(matches.find()) {
                LocalDate promulgation=date(matches.group(2)),effective=date(matches.group(4));
                if(effective.isBefore(promulgation) || !dates.add(effective)) return Optional.empty();
                rows.add(new HistoryRow(matches.group(1),matches.group(2),matches.group(3),effective));
            }
            if(rows.size()<2 || rows.size()>500 || CALL.matcher(html).results().count()!=rows.size()) return Optional.empty();
            rows.sort(Comparator.comparing(HistoryRow::effective));
            for(int index=0;index<rows.size()-1;index++) {
                var row=rows.get(index);
                var next=rows.get(index+1);
                if(row.edition().equals(source.externalId()) && row.number().equals(number) && row.published().equals(published)
                    && row.effective().equals(commencement.get().effectiveDate()) && !reference.isBefore(row.effective())
                    && reference.isBefore(next.effective())) {
                    return Optional.of(new OfficialEffectivePhaseEvidence(lawId,row.edition(),source.documentId(),source.chunkId(),source.chunkVersion(),row.effective(),next.effective(),
                        reference,observed,LawHashUtils.sha256(html),commencement.get()));
                }
            }
        } catch(tools.jackson.core.JacksonException | java.time.DateTimeException invalid) {
            return Optional.empty();
        }
        return Optional.empty();
    }
    static URI historyUri(String lawId,String edition) {
        if(lawId==null || edition==null || !lawId.matches("[0-9]{1,20}") || !edition.matches("[0-9]{1,20}"))
            throw new IllegalArgumentException("Numeric official law identity required");
        return URI.create("https://www.law.go.kr/LSW/lsHstListR.do?lsiSeq="+edition+"&lsId="+lawId+"&ancYnChk=0");
    }
    boolean matchesOwner(LawSemanticChunkRow row) {
        return row!=null && "law".equals(row.target()) && documentId==row.documentId() && chunkId==row.chunkId()
            && row.chunkVersion()!=null && chunkVersion==row.chunkVersion() && edition.equals(row.externalId());
    }
    boolean matchesGround(LawAiAnswerGround ground) {
        return ground!=null && "law".equals(ground.target()) && documentId==ground.documentId() && chunkId==ground.chunkId()
            && effectiveFrom.format(DateTimeFormatter.BASIC_ISO_DATE).equals(ground.sourceDate());
    }
    private static LocalDate date(String text) { return LocalDate.parse(text,DateTimeFormatter.BASIC_ISO_DATE); }
    private record HistoryRow(String edition,String published,String number,LocalDate effective) {}
}
