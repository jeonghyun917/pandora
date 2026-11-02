package com.kaces.pandora.lawdata.sync;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import com.kaces.pandora.common.text.LawHashUtils;

/** One-pass public artifact diagnosis; no DB/Q/model/serving/mutation/activation calls. */
public class SourceReferenceRelationDiagnostic {
    private record Cached(String artifactHash,String format,long byteCount) { }
    public static void main(String[] args) throws Exception {
        Path input=Path.of(args[0]);String raw=Files.readString(input);var json=new ObjectMapper();
        var declarations=new SourceReferenceDeclarations().capture(raw);
        System.out.println(json.writeValueAsString(Map.of("sourceHash",LawHashUtils.sha256(raw),"declarations",declarations,"fidelityReady",false)));
        var parser=new LawOpenApiPayloadParser(json,new com.kaces.pandora.common.json.LawJsonWriter(json));
        var assets=parser.parseSourceVersionDocument(raw,"official source").assets();
        var inspector=new SourceReferenceInspector();
        if(args.length>1) {
            var known=new HashMap<String,Cached>();boolean sameSource=false;
            for(String line:Files.readAllLines(Path.of(args[1])))if(line.startsWith("{")) {
                var node=json.readTree(line);
                if(node.has("sourceHash"))sameSource=LawHashUtils.sha256(raw).equals(node.path("sourceHash").asText());
                if(node.has("artifactHash")){var proof=new Cached(node.path("artifactHash").asText(),node.path("format").asText(),node.path("byteCount").asLong());if(known.put(node.path("canonicalUrl").asText(),proof)!=null)throw new IllegalStateException("Duplicate cached identity");}
            }
            if(!sameSource || known.size()!=assets.size() || known.size()>32)throw new IllegalStateException("Exact diagnostic source/asset set required");
            inspector=new SourceReferenceInspector(uri->{
                var proof=known.get(uri.toString());if(proof==null || !proof.artifactHash().matches("[0-9a-f]{64}") || !Set.of("pdf","hwpx").contains(proof.format()))throw new IllegalStateException("Unknown cached artifact");
                Path file=input.getParent().resolve("reference-"+proof.artifactHash()+"."+proof.format());
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>SourceReferenceInspector.MAX_BYTES || Files.size(file)!=proof.byteCount())throw new IllegalStateException("Cached artifact bound changed");
                byte[] bytes=Files.readAllBytes(file);if(!SourceReferenceInspector.artifactHash(bytes).equals(proof.artifactHash()))throw new IllegalStateException("Cached artifact hash changed");
                return new SourceReferenceInspector.Download(200,proof.format().equals("pdf")?"application/pdf":"application/hwp+zip",bytes.length,new java.io.ByteArrayInputStream(bytes));
            });
        }
        long total=0;
        for(var asset:assets) {
            var acquisition=inspector.acquire(asset.sourceUrl());var proof=acquisition.inspection();
            byte[] bytes=acquisition.bytes();total+=bytes.length;if(total>32L*1024*1024)throw new IllegalStateException("Total bound");
            Path cached=input.getParent().resolve("reference-"+proof.artifactHash()+"."+proof.format());
            if(Files.exists(cached,LinkOption.NOFOLLOW_LINKS)) {
                if(!Files.isRegularFile(cached,LinkOption.NOFOLLOW_LINKS) || Files.size(cached)!=bytes.length
                    || !SourceReferenceInspector.artifactHash(Files.readAllBytes(cached)).equals(proof.artifactHash()))throw new IllegalStateException("Immutable diagnostic artifact conflict");
            } else Files.write(cached,bytes,StandardOpenOption.CREATE_NEW);
            System.out.println(json.writeValueAsString(proof));
            long aligned=proof.columnEvidence().stream().filter(t->t.status().equals("COLUMN_ALIGNMENT_ONLY")).count();
            long columns=proof.columnEvidence().stream().mapToLong(t->t.columns().size()).sum();
            System.out.println("RELATION_DIAG artifact="+proof.artifactHash()+" format="+proof.format()+" tables="+proof.columnEvidence().size()+" aligned="+aligned+" columns="+columns+" fidelityReady=false");
            long tracks=proof.rangeEvidence().stream().mapToLong(t->t.tracks().size()).sum();
            long bands=proof.rangeEvidence().stream().flatMap(t->t.tracks().stream()).mapToLong(t->t.bands().size()).sum();
            System.out.println("RANGE_DIAG artifact="+proof.artifactHash()+" tracks="+tracks+" bands="+bands+" crossTrackRule=false fidelityReady=false");
        }
    }
}
