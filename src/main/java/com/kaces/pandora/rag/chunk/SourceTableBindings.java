package com.kaces.pandora.rag.chunk;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.rag.importing.SourceTableRow;
import tools.jackson.databind.ObjectMapper;

/** Literal source-cell bindings only; never assigns natural-language semantic roles. */
public final class SourceTableBindings {
    public record Input(long documentId,int chunkVersion,Integer pageNo,String text,String sourceFileHash,String provenance) { }
    public record Binding(long documentId,String sourcePart,String tablePath,int rowIndex,int sourceColumn,
        String headerPath,String headerText,String valueText,String sourceFileHash,int chunkVersion,Integer pageNo,String rowText) { }
    private static final ObjectMapper JSON=new ObjectMapper();
    private record Key(long documentId,int version,Integer page,String hash,String part,String table,int row) { }
    private record Verified(Input input,SourceTableRow row,int start,int end) { }

    public List<Binding> validate(java.nio.file.Path original,List<Input> inputs) {
        if(original==null || inputs==null || inputs.size()>64) { return List.of(); }
        try {
            byte[] bytes=java.nio.file.Files.readAllBytes(original);
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            var document=new com.kaces.pandora.rag.importing.RagTextExtractor().extract(original);
            String after=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(original)));
            if(!hash.equals(after)) { return List.of(); }
            var originals=document.pages().stream().flatMap(page->page.tableRows().stream()
                .map(row->new Original(page.pageNo(),row))).toList();
            return validateConsistent(inputs.stream().filter(i->i!=null && hash.equalsIgnoreCase(i.sourceFileHash()))
                .filter(i->{
                    try {
                        var proof=JSON.readValue(i.provenance(),TableSourceSegments.Proof.class);
                        return originals.contains(new Original(i.pageNo(),proof.row()));
                    } catch(RuntimeException invalid) { return false; }
                }).toList());
        } catch(java.io.IOException | java.security.NoSuchAlgorithmException | RuntimeException unavailable) { return List.of(); }
    }

    private record Original(Integer page,SourceTableRow row) { }

    private List<Binding> validateConsistent(List<Input> inputs) {
        if(inputs==null) { return List.of(); }
        var rows=new LinkedHashMap<Key,Verified>();
        var groups=new LinkedHashMap<Key,List<Verified>>();
        for(Input input:inputs) {
            Verified verified=verify(input);
            if(verified==null) { continue; }
            Key key=key(verified,verified.row().rowIndex());
            groups.computeIfAbsent(key,ignored->new ArrayList<>()).add(verified);
        }
        for(var group:groups.entrySet()) {
            var fragments=group.getValue();
            var first=fragments.get(0);
            if(fragments.stream().anyMatch(f->!f.row().equals(first.row()))) { continue; }
            fragments.sort(java.util.Comparator.comparingInt(Verified::start));
            int covered=0;
            for(var fragment:fragments) {
                if(fragment.start()>covered) { break; }
                covered=Math.max(covered,fragment.end());
            }
            if(covered==first.row().renderedText().length()) { rows.put(group.getKey(),first); }
        }
        var result=new ArrayList<Binding>();
        for(Verified value:rows.values()) {
            SourceTableRow row=value.row();
            for(var cell:row.cells()) {
                if(cell.headerPath()==null || cell.headerText()==null) { continue; }
                // Require the separately covered, same-source header cell, not a label in metadata.
                for(Verified header:rows.values()) {
                    if(!key(value,header.row().rowIndex()).equals(key(header,header.row().rowIndex()))
                        || header.row().rowIndex()>=row.rowIndex()) { continue; }
                    var candidates=header.row().cells();
                    for(int i=0;i<candidates.size();i++) {
                        var source=candidates.get(i);
                        String path=row.tablePath()+"/tr["+(header.row().rowIndex()+1)+"]/tc["+(i+1)+"]";
                        if(path.equals(cell.headerPath()) && source.sourceColumn()==cell.sourceColumn()
                            && source.headerPath()==null && source.headerText()==null
                            && source.text().equals(cell.headerText())) {
                            result.add(new Binding(value.input().documentId(),row.sourcePart(),row.tablePath(),row.rowIndex(),
                                cell.sourceColumn(),path,source.text(),cell.text(),value.input().sourceFileHash(),
                                value.input().chunkVersion(),value.input().pageNo(),row.renderedText()));
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private Key key(Verified value,int row) {
        Input i=value.input(); SourceTableRow r=value.row();
        return new Key(i.documentId(),i.chunkVersion(),i.pageNo(),i.sourceFileHash().toLowerCase(java.util.Locale.ROOT),r.sourcePart(),r.tablePath(),row);
    }

    private Verified verify(Input input) {
        if(input==null || input.documentId()<=0 || input.chunkVersion()!=4 || input.provenance()==null
            || input.sourceFileHash()==null || !input.sourceFileHash().matches("[0-9a-fA-F]{64}")) { return null; }
        try {
            var p=JSON.readValue(input.provenance(),TableSourceSegments.Proof.class);
            SourceTableRow r=p.row();
            if(p.schemaVersion()!=1 || !input.sourceFileHash().equalsIgnoreCase(p.sourceFileHash())
                || !Objects.equals(input.pageNo(),p.pageNo()) || r==null || r.rowIndex()<0
                || r.sourcePart()==null || r.sourcePart().isBlank() || r.tablePath()==null || r.tablePath().isBlank()
                || r.renderedText()==null || input.text()==null
                || !LawHashUtils.sha256(r.renderedText()).equals(p.rowHash())
                || p.fragmentStart()<0 || p.fragmentEnd()<=p.fragmentStart() || p.fragmentEnd()>r.renderedText().length()
                || !r.renderedText().substring(p.fragmentStart(),p.fragmentEnd()).equals(input.text())
                || p.completeRow()!=(p.fragmentStart()==0 && p.fragmentEnd()==r.renderedText().length())
                || r.textStart()<0 || (long)r.textEnd()-r.textStart()!=r.renderedText().length() || r.cells().isEmpty()) { return null; }
            var rendered=new ArrayList<String>();
            for(int col=0;col<r.cells().size();col++) {
                var c=r.cells().get(col);
                // Merged layouts require a richer ownership contract; fail closed here.
                if(c.sourceRow()!=r.rowIndex() || c.sourceColumn()!=col || c.rowSpan()!=1 || c.columnSpan()!=1
                    || c.text()==null || (c.headerPath()==null)!=(c.headerText()==null)) { return null; }
                rendered.add(c.headerText()==null?c.text():c.headerText()+": "+c.text());
            }
            if(!String.join(" | ",rendered).equals(r.renderedText())) { return null; }
            return new Verified(input,r,p.fragmentStart(),p.fragmentEnd());
        } catch(RuntimeException invalid) { return null; }
    }
}
