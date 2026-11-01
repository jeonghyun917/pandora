package com.kaces.pandora.lawdata.sync;
import java.nio.file.*;
import tools.jackson.databind.ObjectMapper;
import com.kaces.pandora.common.json.LawJsonWriter;
/** Public official downloads only. No DB, Q, credentials, embeddings, evaluator or promotion. */
public class SourceReferenceDiagnostic {
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();
        var parser=new LawOpenApiPayloadParser(json,new LawJsonWriter(json));
        var detail=parser.parseSourceVersionDocument(Files.readString(Path.of(args[0])),"official source");
        var inspector=new SourceReferenceInspector();
        for(var asset:detail.assets()) {
            try {System.out.println(json.writeValueAsString(inspector.inspect(asset.sourceUrl())));}
            catch(IllegalStateException rejected) {
                System.out.println("inspection=REJECTED source="+SourceReferenceInspector.officialUri(asset.sourceUrl())+"; no retry or alternate acquisition");
                for(Throwable cause=rejected;cause!=null;cause=cause.getCause())System.out.println("failureType="+cause.getClass().getSimpleName()+" reason="+cause.getMessage());
                break;
            }
        }
    }
}
