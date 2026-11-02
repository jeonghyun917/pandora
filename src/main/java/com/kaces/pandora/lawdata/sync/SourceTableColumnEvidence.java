package com.kaces.pandora.lawdata.sync;

import java.util.*;

/** Explicit native column alignment only; never evaluates conditions or infers AND/OR. */
public final class SourceTableColumnEvidence {
    public record CellText(String sourcePath,int row,int column,String text) { }
    public record Column(int column,List<CellText> conditions,CellText score) {
        public Column {conditions=List.copyOf(conditions);}
    }
    public record TableEvidence(String sourcePart,String tablePath,String parentCellPath,String status,List<Column> columns) {
        public TableEvidence {columns=List.copyOf(columns);}
    }
    TableEvidence capture(SourceHwpxTableGraph.Table table) {
        var unsupported=new TableEvidence(table.sourcePart(),table.sourcePath(),table.parentCellPath(),"UNSUPPORTED",List.of());
        int rows=table.rowCount(),cols=table.columnCount();
        if(rows<2 || rows>64 || cols<2 || cols>200)return unsupported;
        var grid=new SourceHwpxTableGraph.Cell[rows][cols];var paths=new HashSet<String>();
        for(var cell:table.cells()) {
            if(cell.sourcePath()==null || cell.directText()==null || !paths.add(cell.sourcePath()) || cell.row()<0 || cell.column()<0
                || cell.row()>=rows || cell.column()>=cols || cell.rowSpan()<1 || cell.columnSpan()<1
                || cell.rowSpan()>rows-cell.row() || cell.columnSpan()>cols-cell.column() || !cell.childTablePaths().isEmpty())return unsupported;
            for(int r=cell.row();r<cell.row()+cell.rowSpan();r++)for(int c=cell.column();c<cell.column()+cell.columnSpan();c++) {
                if(grid[r][c]!=null)return unsupported;grid[r][c]=cell;
            }
        }
        for(var row:grid)for(var cell:row)if(cell==null)return unsupported;
        var label=grid[0][0];var scoreLabel=grid[rows-1][0];
        if(!label(label.directText()).equals("구간") || label.columnSpan()!=1 || label.rowSpan()!=rows-1
            || !label(scoreLabel.directText()).equals("점수") || scoreLabel.rowSpan()!=1 || scoreLabel.columnSpan()!=1)return unsupported;
        var columns=new ArrayList<Column>();
        for(int c=1;c<cols;c++) {
            var conditions=new ArrayList<CellText>();
            for(int r=0;r<rows-1;r++) {
                var cell=grid[r][c];String value=label(cell.directText());
                if(cell.rowSpan()!=1 || cell.columnSpan()!=1 || value.isEmpty() || Set.of("구간","점수").contains(value))return unsupported;
                conditions.add(text(cell));
            }
            var score=grid[rows-1][c];
            String scoreLiteral=score.directText().replaceAll("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$","");
            if(score.rowSpan()!=1 || score.columnSpan()!=1 || !scoreLiteral.matches("-?[0-9]{1,6}(?:[.][0-9]{1,6})?(?:점)?"))return unsupported;
            columns.add(new Column(c,conditions,text(score)));
        }
        return new TableEvidence(table.sourcePart(),table.sourcePath(),table.parentCellPath(),"COLUMN_ALIGNMENT_ONLY",columns);
    }
    private static CellText text(SourceHwpxTableGraph.Cell cell) {return new CellText(cell.sourcePath(),cell.row(),cell.column(),cell.directText());}
    private static String label(String text) {return text.replaceAll("[\\s\\p{Z}]","");}
}
