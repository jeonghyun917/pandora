package com.kaces.pandora.lawdata.sync;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
/** Synthetic, offline packaged-classloader smoke; no source, DB, Q, network or serving authority. */
public class SourceHierarchyPackagedSmoke {
    public static void main(String[] args) throws Exception {
        String inner="<hp:tbl rowCnt='1' colCnt='2'><hp:tr>"+cell(0,"90% 미만","")+cell(1,"2점","")+"</hp:tr></hp:tbl>";
        String xml="<hs:sec xmlns:hs='urn:hs' xmlns:hp='urn:hp'><hp:tbl rowCnt='1' colCnt='1'><hp:tr>"+cell(0,"완료 전",inner)+"</hp:tr></hp:tbl></hs:sec>";
        Path file=Files.createTempFile("pandora-packaged-hierarchy-",".hwpx");
        try {
            try(var zip=new ZipOutputStream(Files.newOutputStream(file))) {
                for(var entry:Map.of("mimetype","application/hwp+zip","Contents/section0.xml",xml).entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
                }
            }
            byte[] bytes=Files.readAllBytes(file);
            var graph=new SourceHwpxTableGraph().capture(file);
            long cells=graph.tables().stream().mapToLong(table->table.cells().size()).sum();
            if(graph.tables().size()!=2 || cells!=3 || !Objects.equals(graph.tables().get(1).parentCellPath(),graph.tables().get(0).cells().get(0).sourcePath()))throw new IllegalStateException("Packaged ownership smoke failed");
            var pages=new com.kaces.pandora.rag.importing.RagTextExtractor().extract(file).pages();
            if(pages.stream().anyMatch(page->!page.tableRows().isEmpty()))throw new IllegalStateException("Legacy nested guard changed");
            var inspector=new SourceReferenceInspector(uri->new SourceReferenceInspector.Download(200,"application/hwp+zip",bytes.length,new java.io.ByteArrayInputStream(bytes)));
            var proof=inspector.inspect("/flDownload.do?flSeq=123");
            if(proof.fidelityReady() || !proof.status().equals("INTEGRITY_ONLY") || !proof.structureHash().equals(com.kaces.pandora.common.text.LawHashUtils.sha256Json(List.of("source-hwpx-hierarchy-v1",pages,graph))))throw new IllegalStateException("Packaged inspection binding failed");
            System.out.println("PACKAGED_HIERARCHY tables=2 cells=3 nested=1 legacyFlatRows=0 fidelityReady=false");
        } finally {Files.deleteIfExists(file);}
    }
    private static String cell(int column,String text,String nested) {
        return "<hp:tc header='0'><hp:cellAddr rowAddr='0' colAddr='"+column+"'/><hp:cellSpan rowSpan='1' colSpan='1'/><hp:p><hp:run><hp:t>"+text+"</hp:t>"+nested+"</hp:run></hp:p></hp:tc>";
    }
}
