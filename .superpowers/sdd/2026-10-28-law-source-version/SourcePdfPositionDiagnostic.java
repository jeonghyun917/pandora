package com.kaces.pandora.lawdata.sync;
import java.nio.file.*;
/** Exact local PDF positions, no download/model/DB/Q/activation. */
public class SourcePdfPositionDiagnostic {
    public static void main(String[] args)throws Exception {
        Path file=Path.of(args[0]);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>SourceReferenceInspector.MAX_BYTES)throw new IllegalStateException("Local bounded artifact required");
        byte[] bytes=Files.readAllBytes(file);if(!SourceReferenceInspector.artifactHash(bytes).equals(args[1]))throw new IllegalStateException("Artifact changed");
        var result=new SourcePdfInspection().positions(file);
        System.out.println(new tools.jackson.databind.ObjectMapper().writeValueAsString(result));
        System.out.println("PDF_POSITION_DIAG artifact="+result.artifactHash()+" pages="+result.pages().size()+" glyphs="+result.glyphs().size()+" geometry="+result.geometryHash()+" equivalence=false fidelityReady=false");
    }
}
