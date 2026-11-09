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
        Map<String,String> noteLeaves=new LinkedHashMap<>();
        if("admrul".equals(target)) {
            collectAdministrativeText(root.path("AdmRulService").path("조문내용"),owned);
            if(owned.isEmpty())throw new IllegalStateException("Complete official administrative article body required");
            collectAuxiliaryText(root.path("AdmRulService").path("부칙"),Set.of("부칙내용"),owned);
            collectAuxiliaryText(root.path("AdmRulService").path("별표"),Set.of("별표내용"),owned);
        } else {
            JsonNode law=root.path("법령");
            if(!law.isObject())throw new IllegalStateException("Official law body root unavailable");
            collectLawText(law.path("조문"),"$.법령.조문",owned,noteLeaves);
            if(owned.isEmpty())throw new IllegalStateException("Complete official law article body required");
            collectLawText(law.path("부칙"),"$.법령.부칙",owned,noteLeaves);
            collectAuxiliaryText(law.path("별표"),Set.of("별표내용"),owned);
        }
        if(owned.isEmpty())throw new IllegalStateException("Authoritative source text unavailable");
        SyncDetailDocument detail=parser.parseSourceVersionDocument(raw,title);
        if(detail.sections()==null || detail.sections().isEmpty() || detail.sections().size()>10000 || detail.assets()==null || detail.assets().size()>1000)
            throw new IllegalStateException("Bounded parsed official text required");
        var bodySections=detail.sections().stream().filter(s->!isArticleNote(s.type())).toList();
        String parsed=bodySections.stream().map(s->normalize(s.body())).collect(java.util.stream.Collectors.joining());
        if(owned.stream().map(SourceVersionTextPlan::normalize).anyMatch(text->!parsed.contains(text)))
            throw new IllegalStateException("Official source content was lost during parsing");
        Map<String,String> parsedNotes=new LinkedHashMap<>();
        for(var section:detail.sections()) {
            if(isArticleNote(section.type())
                    && parsedNotes.putIfAbsent(section.sourcePath(),normalize(section.body()))!=null)
                throw new IllegalStateException("Duplicate article reference note");
        }
        if(!parsedNotes.equals(noteLeaves))throw new IllegalStateException("Article reference note ownership changed");
        var planned=new LawSemanticChunkPlanner().plan(new ChunkPlanningContext(target,documentId,title),detail.sections());
        if(planned.isEmpty() || planned.size()>10000 || planned.stream().anyMatch(c->c.text()==null || c.text().isBlank()
                || !Set.of("PASS","CONTEXT_ONLY").contains(c.qualityStatus())))throw new IllegalStateException("Source chunk quality gate failed");
        Map<String,String> plannedNotes=new LinkedHashMap<>();
        for(var chunk:planned) {
            if(!isArticleNote(chunk.type()))continue;
            String path=chunk.sourcePath();
            if(path==null || !path.startsWith(chunk.parentSourcePath()+".조문참고자료")
                    || !"CONTEXT_ONLY".equals(chunk.qualityStatus())
                    || plannedNotes.putIfAbsent(path,normalize(chunk.text()))!=null)
                throw new IllegalStateException("Article reference note chunk ownership or role changed");
        }
        if(!plannedNotes.equals(noteLeaves) || !List.copyOf(plannedNotes.keySet()).equals(List.copyOf(noteLeaves.keySet())))
            throw new IllegalStateException("Article reference note chunk coverage changed");
        var bodyChunks=planned.stream().filter(c->!isArticleNote(c.type())).toList();
        var coverage=ChunkPreviewApproval.assess(target,documentId,0,raw,bodySections,bodyChunks);
        if(coverage.unexplainedLossSpanCount()!=0)throw new IllegalStateException("Official source content was lost during chunking");
        String rawHash=LawHashUtils.sha256(raw);
        String manifest=LawHashUtils.sha256Json(List.of(target,documentId,title,rawHash,detail.sections(),planned,detail.assets(),coverage.token()));
        return new Plan(rawHash,manifest,planned,detail.sections(),detail.assets(),0);
    }
    private boolean isArticleNote(String type) {return LawOpenApiPayloadParser.ARTICLE_REFERENCE_NOTE.equals(type);}
    private void collectAdministrativeText(JsonNode node,List<String> texts) {
        if(node.isTextual()) {if(!normalize(node.asText()).isBlank())texts.add(node.asText());return;}
        if(node.isArray()) {for(var item:node)collectAdministrativeText(item,texts);return;}
        throw new IllegalStateException("Unsupported official administrative text structure");
    }
    private void collectAuxiliaryText(JsonNode node,Set<String> contentFields,List<String> texts) {
        if(node.isMissingNode() || node.isNull())return;
        if(node.isTextual())throw new IllegalStateException("Owned auxiliary content field required");
        // Parallel dates, file names and links are metadata, not authoritative body spans.
        // Text is collected only when its owning content field is recognized below.
        if(node.isArray()) {for(var item:node)if(item.isObject() || item.isArray())collectAuxiliaryText(item,contentFields,texts);return;}
        if(!node.isObject())throw new IllegalStateException("Unsupported official auxiliary body structure");
        for(var entry:node.properties()) {
            if(contentFields.contains(entry.getKey()))collectAdministrativeText(entry.getValue(),texts);
            else if(entry.getKey().endsWith("내용"))throw new IllegalStateException("Unknown official auxiliary body field");
            else if(entry.getValue().isObject() || entry.getValue().isArray())collectAuxiliaryText(entry.getValue(),contentFields,texts);
        }
    }
    private void collectLawText(JsonNode node,String path,List<String> texts,Map<String,String> noteLeaves) {
        if(node.isMissingNode() || node.isNull())return;
        if(node.isArray()) {
            for(int i=0;i<node.size();i++)collectLawText(node.get(i),path+"["+i+"]",texts,noteLeaves);
            return;
        }
        if(!node.isObject())throw new IllegalStateException("Unsupported official law structure");
        for(var entry:node.properties()) {
            String next=path+"."+entry.getKey();
            if("조문참고자료".equals(entry.getKey())) {
                if(!path.matches("^\\$\\.법령\\.조문\\.조문단위(?:\\[\\d+\\])*$"))
                    throw new IllegalStateException("Article reference note requires its owning article");
                collectNoteLeaves(entry.getValue(),next,noteLeaves);
            } else if(Set.of("조문내용","항내용","호내용","목내용","부칙내용").contains(entry.getKey())) {
                collectAdministrativeText(entry.getValue(),texts);
            } else if(entry.getKey().endsWith("내용")) {
                throw new IllegalStateException("Unknown official law body field");
            } else if(entry.getValue().isObject() || entry.getValue().isArray()) {
                collectLawText(entry.getValue(),next,texts,noteLeaves);
            }
        }
    }
    private void collectNoteLeaves(JsonNode node,String path,Map<String,String> notes) {
        if(node.isNull())return;
        if(node.isTextual()) {
            String value=normalize(node.asText());
            if(!value.isBlank())notes.put(path,value);
        } else if(node.isArray()) {
            for(int i=0;i<node.size();i++)collectNoteLeaves(node.get(i),path+"["+i+"]",notes);
        } else {
            throw new IllegalStateException("Unsupported article reference note structure");
        }
    }
    private static String normalize(String text) {return LawTextUtils.normalizeText(LawTextUtils.stripHtmlTags(text==null?"":text)).replaceAll("\\s+","");}
}
