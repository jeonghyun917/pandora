package com.kaces.pandora.lawdata.sync;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Independent numerical tracks only: no rule choosing/combining tracks or legal applicability. */
public final class SourceTableRangeSemantics {
    public record Bound(String value,boolean inclusive) { }
    public record Band(SourceTableColumnEvidence.CellText condition,SourceTableColumnEvidence.CellText score,Bound lower,Bound upper) { }
    public record Track(int row,String unit,List<Band> bands) {public Track {bands=List.copyOf(bands);}}
    public record ContextCell(String sourcePath,int row,int column,int rowSpan,int columnSpan,String text) { }
    public record TableSemantics(String sourcePart,String tablePath,String parentCellPath,String status,List<ContextCell> context,List<Track> tracks) {
        public TableSemantics {context=List.copyOf(context);tracks=List.copyOf(tracks);}
    }
    private record Owned(SourceHwpxTableGraph.Table table,SourceHwpxTableGraph.Cell cell) { }
    private record Parsed(String unit,Bound lower,Bound upper) { }
    private static final Pattern TERM=Pattern.compile("[\\s\\p{Z}]*(-?(?:0|[1-9][0-9]{0,5})(?:[.][0-9]{1,6})?)[\\s\\p{Z}]*(%|년)[\\s\\p{Z}]*(이상|초과|이하|미만)[\\s\\p{Z}]*");
    List<TableSemantics> capture(SourceHwpxTableGraph.Graph graph) {
        var owners=new HashMap<String,Owned>();
        for(var table:graph.tables())for(var cell:table.cells())
            if(owners.put(table.sourcePart()+"#"+cell.sourcePath(),new Owned(table,cell))!=null)throw rejected();
        var result=new ArrayList<TableSemantics>();
        for(var table:graph.tables()) {
            var unsupported=new TableSemantics(table.sourcePart(),table.sourcePath(),table.parentCellPath(),"UNSUPPORTED",List.of(),List.of());
            var columns=new SourceTableColumnEvidence().capture(table);
            if(!columns.status().equals("COLUMN_ALIGNMENT_ONLY")){result.add(unsupported);continue;}
            try {
                var tracks=new ArrayList<Track>();int rows=columns.columns().get(0).conditions().size();
                for(int r=0;r<rows;r++) {
                    String unit=null;var bands=new ArrayList<Band>();
                    for(var column:columns.columns()) {
                        var condition=column.conditions().get(r);var parsed=parse(condition.text());
                        if(unit!=null && !unit.equals(parsed.unit()))throw rejected();unit=parsed.unit();
                        bands.add(new Band(condition,column.score(),parsed.lower(),parsed.upper()));
                    }
                    partition(bands);tracks.add(new Track(r,unit,bands));
                }
                result.add(new TableSemantics(table.sourcePart(),table.sourcePath(),table.parentCellPath(),"INDEPENDENT_RANGE_TRACKS_ONLY",context(table,owners),tracks));
            } catch(IllegalStateException unsupportedShape) {result.add(unsupported);}
        }
        return List.copyOf(result);
    }
    private static Parsed parse(String text) {
        if(text==null || text.length()>4096 || text.isBlank())throw rejected();
        var matcher=TERM.matcher(text);int position=0,count=0;String unit=null;Bound lower=null,upper=null;
        while(position<text.length()) {
            matcher.region(position,text.length());if(!matcher.lookingAt() || ++count>2)throw rejected();
            if(unit!=null && !unit.equals(matcher.group(2)))throw rejected();unit=matcher.group(2);
            var bound=new Bound(matcher.group(1),Set.of("이상","이하").contains(matcher.group(3)));
            if(Set.of("이상","초과").contains(matcher.group(3))) {if(lower!=null)throw rejected();lower=bound;}
            else {if(upper!=null)throw rejected();upper=bound;}
            position=matcher.end();
        }
        if(count==0)throw rejected();
        if(lower!=null && upper!=null) {
            int c=number(lower).compareTo(number(upper));
            if(c>0 || (c==0 && !(lower.inclusive() && upper.inclusive())))throw rejected();
        }
        return new Parsed(unit,lower,upper);
    }
    private static void partition(List<Band> bands) {
        var ordered=new ArrayList<>(bands);
        ordered.sort((a,b)->a.lower()==null?(b.lower()==null?0:-1):b.lower()==null?1:number(a.lower()).compareTo(number(b.lower())));
        if(ordered.get(0).lower()!=null || ordered.get(ordered.size()-1).upper()!=null)throw rejected();
        for(int i=1;i<ordered.size();i++) {
            var upper=ordered.get(i-1).upper();var lower=ordered.get(i).lower();
            if(upper==null || lower==null || number(upper).compareTo(number(lower))!=0 || upper.inclusive()==lower.inclusive())throw rejected();
        }
    }
    private static BigDecimal number(Bound bound) {return new BigDecimal(bound.value());}
    private static List<ContextCell> context(SourceHwpxTableGraph.Table table,Map<String,Owned> owners) {
        if(table.parentCellPath()==null)return List.of();
        var parent=owners.get(table.sourcePart()+"#"+table.parentCellPath());
        if(parent==null || Collections.frequency(parent.cell().childTablePaths(),table.sourcePath())!=1)throw rejected();
        var owner=parent.cell();if(owner.rowSpan()!=1)throw rejected();
        var cells=new ArrayList<SourceHwpxTableGraph.Cell>();
        for(var cell:parent.table().cells())if(cell.column()<owner.column() && cell.columnSpan()<=owner.column()-cell.column()
            && cell.row()<=owner.row() && cell.rowSpan()>owner.row()-cell.row()) {
            if(!cell.childTablePaths().isEmpty())throw rejected();cells.add(cell);
        }
        cells.sort(Comparator.comparingInt(SourceHwpxTableGraph.Cell::column));cells.add(owner);
        return cells.stream().map(c->new ContextCell(c.sourcePath(),c.row(),c.column(),c.rowSpan(),c.columnSpan(),c.directText())).toList();
    }
    private static IllegalStateException rejected() {return new IllegalStateException("Unsupported interval track or source-owned context");}
}
