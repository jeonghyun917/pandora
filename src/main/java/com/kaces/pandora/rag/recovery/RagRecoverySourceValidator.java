package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import com.kaces.pandora.rag.importing.RagTextExtractor;
import com.kaces.pandora.rag.chunk.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;

/** Regenerates from the actual original. Client-supplied manifests cannot bless different text. */
public final class RagRecoverySourceValidator {
    public RecoveryValidation validate(Path original,RecoveryGeneration generation,String currentProductSha,
            String title,String sourceUrl,List<RagDocumentChunkRow> stored) {
        var failures=new ArrayList<String>();
        try {
            if(!Set.of("PENDING","VALIDATED").contains(generation.state()))failures.add("state");
            if(!generation.productSha().equals(currentProductSha))failures.add("productSha");
            if(!sha(original).equals(generation.sourceHash()))failures.add("sourceHash");
            if(!failures.isEmpty())return result(generation,failures);
            var rows=stored.stream().map(RagRecoverySourceValidator::unassigned).toList();
            String actual=RecoveryCandidatePlan.manifestFor(generation.documentId(),generation.sourceHash(),currentProductSha,rows);
            if(!actual.equals(generation.manifest()))failures.add("storedManifest");
            var extracted=new RagTextExtractor().extract(original);
            var regenerated=new RagChunkQualityGate().evaluate(title,new RagChunker().chunkV4(generation.documentId(),extracted,
                    sourceUrl,title,generation.sourceHash())).retainedChunks();
            if(!RecoveryCandidatePlan.manifestFor(generation.documentId(),generation.sourceHash(),currentProductSha,regenerated).equals(generation.manifest()))failures.add("originalManifest");
            var proof=rows.stream().filter(c->c.sourceProvenance()!=null).map(c->new SourceTableBindings.Input(c.documentId(),c.chunkVersion(),c.pageNo(),c.chunkText(),generation.sourceHash(),c.sourceProvenance())).toList();
            if(proof.size()>64 || (!proof.isEmpty() && new SourceTableBindings().validate(original,proof).isEmpty()))failures.add("sourceProof");
            // This first recovery path accepts HWPX originals only; other formats fail closed.
            if(!original.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".hwpx"))failures.add("unsupportedOriginal");
            else if(!literalCoverage(original,rows))failures.add("literalCoverage");
            if(!sha(original).equals(generation.sourceHash()))failures.add("sourceChanged");
        } catch(java.io.IOException | RuntimeException invalid) { failures.add("invalidOriginalOrCandidate"); }
        return result(generation,failures);
    }
    private static RecoveryValidation result(RecoveryGeneration g,List<String> failures) {
        return new RecoveryValidation(g.id(),g.manifest(),g.sourceHash(),g.productSha(),failures.isEmpty(),failures);
    }
    static RagDocumentChunkRow unassigned(RagDocumentChunkRow c) {
        return new RagDocumentChunkRow(0,c.documentId(),c.chunkVersion(),c.chunkNo(),c.parentSectionTitle(),c.chunkTitle(),c.sectionType(),c.chunkText(),c.embeddingText(),c.pageNo(),c.sourcePath(),c.sourceUrl(),c.sortOrder(),c.contentHash(),c.qualityStatus(),c.qualityReason(),c.sourceProvenance());
    }
    private static String sha(Path original)throws java.io.IOException {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static boolean literalCoverage(Path original,List<RagDocumentChunkRow> rows)throws java.io.IOException {
        String text=rows.stream().map(RagDocumentChunkRow::chunkText).reduce("",(a,b)->a+"\n"+b).replaceAll("\\s+","");
        try(var zip=new ZipFile(original.toFile())) {
            var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            int count=0;
            for(var entry:zip.stream().filter(e->e.getName().matches("Contents/section\\d+\\.xml")).toList()) {
                try(var input=zip.getInputStream(entry)) {
                    var nodes=factory.newDocumentBuilder().parse(input).getElementsByTagNameNS("*","t");
                    for(int n=0;n<nodes.getLength();n++) {
                        String value=nodes.item(n).getTextContent().replaceAll("\\s+","");
                        if(!value.isEmpty()){count++;if(!text.contains(value))return false;}
                    }
                }
            }
            return count>0;
        } catch(javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException invalid) { return false; }
    }
}
