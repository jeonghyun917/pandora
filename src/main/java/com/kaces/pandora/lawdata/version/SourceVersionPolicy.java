package com.kaces.pandora.lawdata.version;

import com.kaces.pandora.common.text.LawHashUtils;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/** Immutable source identity only. This class cannot certify storage or external assets. */
public final class SourceVersionPolicy {
    private SourceVersionPolicy() { }
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    public record Baseline(long documentId,long revision,String target,String canonicalKey,String externalId,String effectiveDate,String snapshotHash) { }
    public record Source(String target,String title,String externalId,String effectiveDate,String rawHash) { }
    public record Candidate(Baseline baseline,Source source,String productSha,String inputHash,String state) { }

    public static Candidate stage(Baseline baseline,Source source,String productSha,LocalDate today) {
        if(baseline==null || source==null || today==null || baseline.documentId()<=0
                || baseline.revision()<0 || baseline.revision()==Long.MAX_VALUE
                || !LawVersionUtils.isVersionedTarget(baseline.target()) || !baseline.target().equals(source.target())
                || !text(source.title(),500) || !text(source.externalId(),200) || !text(baseline.externalId(),200)
                || Objects.equals(baseline.externalId(),source.externalId())
                || !Objects.equals(baseline.canonicalKey(),LawVersionUtils.canonicalKey(source.target(),source.title()))
                || !hex(baseline.snapshotHash(),64) || !hex(source.rawHash(),64) || !hex(productSha,40))
            throw new IllegalArgumentException("Exact distinct same-group source identity required");
        LocalDate prior=date(baseline.effectiveDate()), next=date(source.effectiveDate());
        if(!next.isAfter(prior) || next.isAfter(today))throw new IllegalArgumentException("Newer effective nonfuture source required");
        String identity=new ObjectMapper().writeValueAsString(List.of(baseline,source,productSha));
        return new Candidate(baseline,source,productSha,LawHashUtils.sha256(identity),"STAGED");
    }
    private static LocalDate date(String value) {
        if(value==null || !value.matches("[0-9]{8}"))throw new IllegalArgumentException("Exact source date required");
        try { return LocalDate.parse(value,DATE); }
        catch(java.time.DateTimeException invalid) { throw new IllegalArgumentException("Invalid source date",invalid); }
    }
    private static boolean hex(String value,int size) { return value!=null && value.matches("[0-9a-f]{"+size+"}"); }
    private static boolean text(String value,int bound) { return value!=null && !value.isBlank() && value.equals(value.trim()) && value.length()<=bound && value.chars().noneMatch(Character::isISOControl); }
}
