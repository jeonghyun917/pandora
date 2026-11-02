package com.kaces.pandora.lawdata.sync;
import java.nio.file.*;
import tools.jackson.databind.ObjectMapper;

/** Public official literal coverage diagnosis; never a semantic/readiness receipt or DB write. */
public class SourceReferenceCorrespondenceDiagnostic {
    static String literal(String value) {return value.replaceAll("[\\s\\p{Z}\\u2500-\\u257F]","");}
    static void textOnly(tools.jackson.databind.JsonNode node,StringBuilder out) {
        if(node.isTextual())out.append(node.asText()).append('\n');
        else if(node.isArray())for(var child:node)textOnly(child,out);
        else throw new IllegalStateException("Annex body must be text or arrays, not metadata");
    }
    public static void main(String[] args) throws Exception {
        var mapper=new ObjectMapper();
        String input=Files.readString(Path.of(args[0]));var raw=mapper.readTree(input);
        var body=new StringBuilder();textOnly(raw.path("AdmRulService").path("별표").path("별표단위").path("별표내용"),body);
        String annex=literal(body.toString());
        var parser=new LawOpenApiPayloadParser(mapper,new com.kaces.pandora.common.json.LawJsonWriter(mapper));
        var assets=parser.parseSourceVersionDocument(input,"official source").assets();
        var inspector=new SourceReferenceInspector();
        for(int index=0;index<assets.size();index++) {
            // Exactly one acquisition per declared URL, no retry after rejection.
            var acquisition=inspector.acquire(assets.get(index).sourceUrl());var proof=acquisition.inspection();
            System.out.println("ARTIFACT asset="+index+" sha256="+proof.artifactHash()+" format="+proof.format());
            if(!proof.format().equals("hwpx"))continue;
            byte[] bytes=acquisition.bytes();
            Path file=Files.createTempFile("source-correspondence-",".hwpx");
            try {
                Files.write(file,bytes);var graph=new SourceHwpxTableGraph().capture(file);int present=0,missing=0;
                for(var table:graph.tables())for(var cell:table.cells()) {
                    String text=literal(cell.directText());if(text.isEmpty())continue;
                    if(annex.contains(text))present++;else {missing++;System.out.println("UNMATCHED_CELL asset="+index+" row="+cell.row()+" col="+cell.column()+" text="+cell.directText().replace('\n',' '));}
                }
                System.out.println("LITERAL_ONLY asset="+index+" tables="+graph.tables().size()+" present="+present+" missing="+missing+" semanticProof=false relationProof=false fidelityReady=false");
            } finally {Files.deleteIfExists(file);}
        }
    }
}
