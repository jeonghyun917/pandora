package com.kaces.pandora.lawdata.sync;

import java.nio.file.*;
import tools.jackson.databind.ObjectMapper;
import com.kaces.pandora.common.json.LawJsonWriter;

/** Read-only source parsing diagnostic; no Spring context, DB, embeddings or Q client. */
public class SourceFidelityDiagnostic {
    public static void main(String[] args) throws Exception {
        String raw=Files.readString(Path.of(args[0]));
        var json=new ObjectMapper();
        var parser=new LawOpenApiPayloadParser(json,new LawJsonWriter(json));
        var parsed=parser.parseDetailDocument(raw,args[2]);
        System.out.println("parsedSections="+parsed.sections().size()+" requiredAssets="+parsed.assets().size());
        String kept=parsed.sections().stream().map(s->normalize(s.body())).collect(java.util.stream.Collectors.joining());
        var root=json.readTree(raw).path("AdmRulService");
        inspect(root.path("조문내용"),"articles",kept);
        inspect(root.path("별표").path("별표단위").path("별표내용"),"annex",kept);
        try {
            var plan=new SourceVersionTextPlan(parser).plan(args[1],1,args[2],raw);
            System.out.println("localTextPlan=ACCEPTED chunks="+plan.chunks().size()+"; reference readiness remains separate");
        } catch(IllegalStateException rejected) {
            System.out.println("localTextPlan=REJECTED reason="+rejected.getMessage());
        }
    }
    private static String normalize(String s) {
        return com.kaces.pandora.common.text.LawTextUtils.normalizeText(com.kaces.pandora.common.text.LawTextUtils.stripHtmlTags(s==null?"":s)).replaceAll("\\s+","");
    }
    private static void inspect(tools.jackson.databind.JsonNode node,String region,String kept) {
        var rows=new java.util.ArrayList<String>();collect(node,rows);
        var lost=rows.stream().filter(s->!normalize(s).isBlank()&&!kept.contains(normalize(s))).toList();
        System.out.println(region+" sourceSpans="+rows.size()+" missingSpans="+lost.size());
        for(String s:lost.stream().limit(3).toList())System.out.println(region+" missingExample="+s.substring(0,Math.min(100,s.length())));
    }
    private static void collect(tools.jackson.databind.JsonNode node,java.util.List<String> rows) {
        if(node.isTextual())rows.add(node.asText());
        else if(node.isArray())for(var item:node)collect(item,rows);
    }
}
