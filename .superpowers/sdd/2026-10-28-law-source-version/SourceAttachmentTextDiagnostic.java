package com.kaces.pandora.lawdata.sync;
import java.nio.file.*;
/** Local exact HWPX bytes only. Text diagnosis, never legal-role certification. */
public class SourceAttachmentTextDiagnostic {
    public static void main(String[] args) throws Exception {
        Path file=Path.of(args[0]);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>SourceReferenceInspector.MAX_BYTES)throw new IllegalStateException("Bounded local artifact required");
        byte[] bytes=Files.readAllBytes(file);if(!SourceReferenceInspector.artifactHash(bytes).equals(args[1]))throw new IllegalStateException("Artifact changed");
        new SourceHwpxTableGraph().capture(file);
        String text=new com.kaces.pandora.rag.importing.RagTextExtractor().extract(file).text();
        if(text.length()>4_000_000)throw new IllegalStateException("Text bound");
        System.out.println("ARTIFACT_TEXT chars="+text.length()+" sha256="+args[1]+" legalRoleCertified=false");
        System.out.println(text.substring(0,Math.min(text.length(),7000)));
        if(args.length>2) {
            var json=new tools.jackson.databind.ObjectMapper();
            var parser=new LawOpenApiPayloadParser(json,new com.kaces.pandora.common.json.LawJsonWriter(json));
            String normalized=text.replaceAll("[\\s\\p{Z}]","");int matched=0,missing=0,previous=-1;boolean ordered=true;
            for(var section:parser.parseSourceVersionDocument(Files.readString(Path.of(args[2])),"official source").sections()) {
                if(!java.util.Set.of("article","admin-rule-article").contains(section.type()))continue;
                String expected=section.body().replaceAll("[\\s\\p{Z}]","");int position=normalized.indexOf(expected);
                if(position<0){missing++;System.out.println("BODY_NOT_EXACT section="+section.no()+" path="+section.sourcePath());}
                else {matched++;if(position<previous)ordered=false;previous=position;}
            }
            if(matched+missing==0)throw new IllegalStateException("No source article selected; cannot diagnose correspondence");
            System.out.println("BODY_LITERAL_ONLY matched="+matched+" missing="+missing+" ordered="+ordered+" legalRoleCertified=false");
            if(args.length>3 && args[3].equals("coverage")) {
                var sections=parser.parseSourceVersionDocument(Files.readString(Path.of(args[2])),"official source").sections();
                if(sections.size()>512)throw new IllegalStateException("Diagnostic section bound");
                boolean[] covered=new boolean[normalized.length()];int found=0,absent=0,ambiguous=0,end=0;
                for(var section:sections) {
                    if(section.type().equals("source-table"))continue;
                    String expected=section.body().replaceAll("[\\s\\p{Z}]","");int start=normalized.indexOf(expected);
                    if(start<0){absent++;System.out.println("SOURCE_NOT_EXACT type="+section.type()+" path="+section.sourcePath());continue;}
                    if(start!=normalized.lastIndexOf(expected) || start<end){ambiguous++;continue;}
                    java.util.Arrays.fill(covered,start,start+expected.length(),true);end=start+expected.length();found++;
                }
                int gaps=0,uncovered=0;
                for(int i=0;i<covered.length;) {
                    if(covered[i]){i++;continue;}int start=i;while(i<covered.length && !covered[i])i++;gaps++;uncovered+=i-start;
                    System.out.println("UNCLASSIFIED_NATIVE_TEXT start="+start+" length="+(i-start)+" text="+normalized.substring(start,Math.min(i,start+1000)));
                }
                System.out.println("COMPANION_COVERAGE_ONLY found="+found+" missing="+absent+" ambiguous="+ambiguous+" gaps="+gaps+" uncoveredChars="+uncovered+" legalRoleCertified=false");
            }
        }
    }
}
