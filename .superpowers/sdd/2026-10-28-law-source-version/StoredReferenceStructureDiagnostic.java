package com.kaces.pandora.lawdata.sync;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Read-only isolated artifact diagnostic: counts raw markup, never grants semantic fidelity. */
public class StoredReferenceStructureDiagnostic {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("PANDORA_SOURCE_VERSION_TEST_URL");
        if(!"jdbc:mariadb://127.0.0.1:23306/pandora_source_version_test".equals(url))throw new IllegalStateException("Isolated test DB required");
        var ds=new org.mariadb.jdbc.MariaDbDataSource();ds.setUrl(url);ds.setUser("root");ds.setPassword(System.getenv("PANDORA_SOURCE_VERSION_TEST_PASSWORD"));
        var db=new JdbcTemplate(ds);
        if(db.queryForObject("SELECT @@port",Integer.class)!=23306 || !Path.of(db.queryForObject("SELECT @@datadir",String.class)).toRealPath().equals(Path.of(System.getenv("PANDORA_SOURCE_VERSION_TEST_DATADIR")).toRealPath()))throw new IllegalStateException("Isolated datadir mismatch");
        var reports=db.queryForList("SELECT i.report_json FROM law_source_version_reference_inspections i JOIN law_source_version_candidates c ON c.candidate_id=i.candidate_id WHERE c.external_id='2100000287088' AND c.state='VALIDATED'",String.class);
        if(reports.size()!=1)throw new IllegalStateException("One observed official artifact report required");
        var report=new ObjectMapper().readValue(reports.get(0),SourceVersionReferenceReceipts.Report.class);
        for(int asset=0;asset<report.references().size();asset++) {
            var proof=report.references().get(asset);if(!proof.format().equals("hwpx"))continue;
            if(proof.byteCount()>SourceReferenceInspector.MAX_BYTES)throw new IllegalStateException("Artifact bound rejected");
            var out=new java.io.ByteArrayOutputStream();
            for(byte[] part:db.query("SELECT part_bytes FROM law_source_version_reference_parts WHERE candidate_id=? AND asset_index=? ORDER BY part_index LIMIT 9",(rs,row)->rs.getBytes(1),report.candidateId(),asset)) {
                if(out.size()+part.length>SourceReferenceInspector.MAX_BYTES)throw new IllegalStateException("Artifact bound exceeded");out.write(part);
            }
            byte[] bytes=out.toByteArray();
            if(bytes.length!=proof.byteCount() || !SourceReferenceInspector.artifactHash(bytes).equals(proof.artifactHash()))throw new IllegalStateException("Stored artifact changed");
            Path file=Files.createTempFile("pandora-stored-reference-",".hwpx");
            try {
                Files.write(file,bytes);var counts=new LinkedHashMap<String,Long>();for(String name:List.of("tbl","tr","tc","pic","fieldBegin"))counts.put(name,0L);
                try(var zip=new ZipFile(file.toFile())) {
                    long expanded=0;
                    for(var entry:zip.stream().filter(e->e.getName().matches("Contents/section[0-9]+[.]xml")).toList())try(var input=zip.getInputStream(entry)) {
                        byte[] xml=input.readNBytes(8*1024*1024+1);expanded+=xml.length;
                        if(xml.length>8*1024*1024 || expanded>32*1024*1024)throw new IllegalStateException("Native bound exceeded");
                        String text=new String(xml,java.nio.charset.StandardCharsets.UTF_8);
                        for(String name:counts.keySet())counts.put(name,counts.get(name)+Pattern.compile("<(?:[A-Za-z_][\\w.-]*:)?"+name+"(?=[\\s>])").matcher(text).results().count());
                        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                        var nodes=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml)).getElementsByTagName("*");int ordinal=0;
                        for(int n=0;n<nodes.getLength();n++)if(name(nodes.item(n)).equals("tbl")) {
                            var table=(org.w3c.dom.Element)nodes.item(n);int depth=0,nested=0;
                            for(var parent=table.getParentNode();parent!=null;parent=parent.getParentNode())if(name(parent).equals("tbl"))depth++;
                            var descendants=table.getElementsByTagName("*");for(int d=0;d<descendants.getLength();d++)if(name(descendants.item(d)).equals("tbl"))nested++;
                            System.out.println("NATIVE_TABLE asset="+asset+" ordinal="+(ordinal++)+" depth="+depth+" nestedTables="+nested+" rows="+table.getAttribute("rowCnt")+" columns="+table.getAttribute("colCnt"));
                        }
                    }
                }
                var nativeDocument=new com.kaces.pandora.rag.importing.RagTextExtractor().extract(file);
                long structuralRows=nativeDocument.pages().stream().mapToLong(page->page.tableRows().size()).sum();
                System.out.println("STORED_REFERENCE asset="+asset+" rawMarkup="+counts+" retainedStructuralRows="+structuralRows+"; counts are not fidelity proof");
            } finally {Files.deleteIfExists(file);}
        }
    }
    private static String name(org.w3c.dom.Node node) {String name=node.getNodeName();int colon=name.indexOf(':');return colon<0?name:name.substring(colon+1);}
}
