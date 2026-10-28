package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.common.text.LawTextUtils;
import java.util.*;
import tools.jackson.databind.*;

/** New-source fidelity, independent of differences from the preserved older source version. */
final class SourceVersionTextPlan {
    record Plan(String rawHash,String manifest,List<PlannedLawChunk> chunks,List<SyncDetailSection> sections,
                List<SyncAsset> assets,int unexplainedLossSpanCount) {
        Plan {chunks=List.copyOf(chunks);sections=List.copyOf(sections);assets=List.copyOf(assets);}
    }
    private final LawOpenApiPayloadParser parser;
    private final ObjectMapper json=new ObjectMapper();
    SourceVersionTextPlan(LawOpenApiPayloadParser parser) {this.parser=Objects.requireNonNull(parser);}
    Plan plan(String target,long documentId,String title,String raw) {
        if(documentId<=0 || title==null || title.isBlank() || title.length()>500 || raw==null || raw.isBlank() || raw.length()>4_000_000
                || !Set.of("law","admrul").contains(target))throw new IllegalArgumentException("Bounded official source identity required");
        JsonNode root=json.readTree(raw);var owned=new ArrayList<String>();
        if("admrul".equals(target)) {
            collectAdministrativeText(root.path("AdmRulService").path("조문내용"),owned);
        } else {
            JsonNode law=root.path("법령");
            if(!law.isObject())throw new IllegalStateException("Official law body root unavailable");
            collectLawText(law.path("조문"),owned);
            if(owned.isEmpty())throw new IllegalStateException("Complete official law article body required");
            collectLawText(law.path("부칙"),owned);
        }
        if(owned.isEmpty())throw new IllegalStateException("Authoritative source text unavailable");
        SyncDetailDocument detail=parser.parseDetailDocument(raw,title);
        if(detail.sections()==null || detail.sections().isEmpty() || detail.sections().size()>10000 || detail.assets()==null || detail.assets().size()>1000)
            throw new IllegalStateException("Bounded parsed official text required");
        String parsed=detail.sections().stream().map(s->normalize(s.body())).collect(java.util.stream.Collectors.joining());
        if(owned.stream().map(SourceVersionTextPlan::normalize).anyMatch(text->!parsed.contains(text)))
            throw new IllegalStateException("Official source content was lost during parsing");
        var planned=new LawSemanticChunkPlanner().plan(new ChunkPlanningContext(target,documentId,title),detail.sections());
        if(planned.isEmpty() || planned.size()>10000 || planned.stream().anyMatch(c->c.text()==null || c.text().isBlank()
                || !Set.of("PASS","CONTEXT_ONLY").contains(c.qualityStatus())))throw new IllegalStateException("Source chunk quality gate failed");
        var coverage=ChunkPreviewApproval.assess(target,documentId,0,raw,detail.sections(),planned);
        if(coverage.unexplainedLossSpanCount()!=0)throw new IllegalStateException("Official source content was lost during chunking");
        String rawHash=LawHashUtils.sha256(raw);
        String manifest=LawHashUtils.sha256Json(List.of(target,documentId,title,rawHash,detail.sections(),planned,detail.assets(),coverage.token()));
        return new Plan(rawHash,manifest,planned,detail.sections(),detail.assets(),0);
    }
    private void collectAdministrativeText(JsonNode node,List<String> texts) {
        if(node.isTextual()) {if(!normalize(node.asText()).isBlank())texts.add(node.asText());return;}
        if(node.isArray()) {for(var item:node)collectAdministrativeText(item,texts);return;}
        throw new IllegalStateException("Unsupported official administrative text structure");
    }
    private void collectLawText(JsonNode node,List<String> texts) {
        if(node.isMissingNode() || node.isNull())return;
        if(node.isArray()) {for(var item:node)collectLawText(item,texts);return;}
        if(!node.isObject())throw new IllegalStateException("Unsupported official law structure");
        for(var entry:node.properties()) {
            if(Set.of("조문내용","항내용","호내용","목내용","부칙내용").contains(entry.getKey())) {
                collectAdministrativeText(entry.getValue(),texts);
            } else if(entry.getKey().endsWith("내용")) {
                throw new IllegalStateException("Unknown official law body field");
            } else if(entry.getValue().isObject() || entry.getValue().isArray()) {
                collectLawText(entry.getValue(),texts);
            }
        }
    }
    private static String normalize(String text) {return LawTextUtils.normalizeText(LawTextUtils.stripHtmlTags(text==null?"":text)).replaceAll("\\s+","");}
}
