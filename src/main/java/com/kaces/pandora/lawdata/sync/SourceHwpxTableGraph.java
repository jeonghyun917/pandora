package com.kaces.pandora.lawdata.sync;

import java.io.*;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.stream.*;

/** Source-owned hierarchy metadata only. It is deliberately not a flat-row or semantic proof. */
final class SourceHwpxTableGraph {
    record Graph(List<Table> tables) {Graph {tables=List.copyOf(tables);}}
    record Table(String sourcePart,String sourcePath,String namespaceURI,String parentCellPath,int rowCount,int columnCount,List<Cell> cells) {
        Table {cells=List.copyOf(cells);}
    }
    record Cell(String sourcePath,int row,int column,int rowSpan,int columnSpan,String header,String directText,List<String> childTablePaths) {
        Cell {childTablePaths=List.copyOf(childTablePaths);}
    }
    private static final int SECTION_BYTES=2*1024*1024;
    Graph capture(Path file) {
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>SourceReferenceInspector.MAX_BYTES)throw rejected();
            var budget=new Budget();
            try(var zip=new ZipFile(file.toFile())) {
                var names=new HashSet<String>();var identities=new HashSet<BigInteger>();var sections=new ArrayList<ZipEntry>();int entries=0;
                var all=zip.entries();
                while(all.hasMoreElements()) {
                    var entry=all.nextElement();String name=entry.getName();
                    if(++entries>2048 || name.length()>512 || !names.add(name) || name.startsWith("/") || name.contains("\\")
                        || name.contains("//") || name.startsWith("./") || name.contains("/./") || Arrays.asList(name.split("/")).contains(".."))throw rejected();
                    if(name.matches("Contents/section[0-9]+[.]xml")) {
                        BigInteger identity=sectionNumber(name);if(!identities.add(identity))throw rejected();sections.add(entry);
                    }
                }
                var mime=zip.getEntry("mimetype");if(mime==null || sections.isEmpty())throw rejected();
                try(var input=zip.getInputStream(mime)) {if(!new String(input.readNBytes(100),StandardCharsets.US_ASCII).trim().equals("application/hwp+zip"))throw rejected();}
                sections.sort(Comparator.comparing(entry->sectionNumber(entry.getName())));
                var factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
                factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
                factory.setXMLResolver((publicId,systemId,base,namespace)->{throw new XMLStreamException("External source XML rejected");});
                long expanded=0;
                for(var entry:sections)try(var input=zip.getInputStream(entry)) {
                    byte[] bytes=input.readNBytes(SECTION_BYTES+1);expanded+=bytes.length;
                    if(bytes.length>SECTION_BYTES || expanded>8L*1024*1024)throw rejected();
                    parse(factory,bytes,entry.getName(),budget);
                }
            }
            return new Graph(budget.tables);
        } catch(IOException|XMLStreamException invalid) {throw new IllegalStateException("Source HWPX hierarchy unavailable",invalid);}
    }
    private static BigInteger sectionNumber(String name) {return new BigInteger(name.substring("Contents/section".length(),name.length()-4));}
    private static IllegalStateException rejected() {return new IllegalStateException("Unsupported or over-bound source HWPX hierarchy");}
    private static int number(XMLStreamReader xml,String key,boolean positive) {
        String value=xml.getAttributeValue(null,key);
        if(value==null || !value.matches("[0-9]{1,6}"))throw rejected();
        int result=Integer.parseInt(value);if(positive && result<1)throw rejected();return result;
    }
    private void parse(XMLInputFactory factory,byte[] bytes,String part,Budget budget) throws XMLStreamException {
        var stack=new ArrayDeque<Frame>();var tables=new ArrayDeque<TableBuilder>();var cells=new ArrayDeque<CellBuilder>();
        CellBuilder textOwner=null;
        var xml=factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
        try {
            while(xml.hasNext()) {
                int event=xml.next();
                if(event==XMLStreamConstants.DTD || event==XMLStreamConstants.ENTITY_REFERENCE)throw rejected();
                if(event==XMLStreamConstants.START_ELEMENT) {
                    if(stack.size()>=64 || ++budget.nodes>100000)throw rejected();
                    String local=xml.getLocalName(),namespace=Objects.toString(xml.getNamespaceURI(),"");
                    if(local.length()>128 || namespace.length()>256)throw rejected();
                    Frame parent=stack.peek();String key=namespace+"|"+local;
                    int position=parent==null?1:parent.children.merge(key,1,Integer::sum);
                    String path=(parent==null?"":parent.path)+"/{"+URLEncoder.encode(namespace,StandardCharsets.UTF_8)+"}"+local+"["+position+"]";
                    if(path.length()>4096)throw rejected();
                    var frame=new Frame(local,namespace,path);stack.push(frame);
                    if(local.equals("tbl")) {
                        if(textOwner!=null || budget.tables.size()>=256)throw rejected();
                        var parentCell=cells.peek();
                        if(!tables.isEmpty() && (parentCell==null || parentCell.table!=tables.peek()))throw rejected();
                        int rows=number(xml,"rowCnt",true),columns=number(xml,"colCnt",true);
                        if(rows>10000 || columns>200 || (budget.grid+=(long)rows*columns)>262144)throw rejected();
                        budget.path(part,path);
                        var table=new TableBuilder(part,path,namespace,parentCell==null?null:parentCell.path,rows,columns,budget.tables.size());
                        if(parentCell!=null)parentCell.children.add(path);
                        budget.tables.add(null);frame.table=table;tables.push(table);
                    } else if(local.equals("tr") && !tables.isEmpty()) {
                        if(parent==null || parent.table!=tables.peek() || !namespace.equals(tables.peek().namespace))throw rejected();
                        frame.rowTable=tables.peek();frame.row=frame.rowTable.rowsSeen++;
                        if(frame.row>=frame.rowTable.rows)throw rejected();
                    } else if(local.equals("tc") && !tables.isEmpty()) {
                        if(parent==null || parent.rowTable!=tables.peek() || !namespace.equals(tables.peek().namespace) || ++budget.cells>10000)throw rejected();
                        budget.path(part,path);String header=xml.getAttributeValue(null,"header");
                        if(header!=null && !Set.of("0","1").contains(header))throw rejected();
                        frame.cell=new CellBuilder(tables.peek(),path,parent.row,header);cells.push(frame.cell);
                    } else if(local.equals("cellAddr") && parent!=null && parent.cell!=null) {
                        var cell=parent.cell;if(!namespace.equals(cell.table.namespace) || cell.address)throw rejected();
                        cell.address=true;cell.row=number(xml,"rowAddr",false);cell.column=number(xml,"colAddr",false);
                    } else if(local.equals("cellSpan") && parent!=null && parent.cell!=null) {
                        var cell=parent.cell;if(!namespace.equals(cell.table.namespace) || cell.span)throw rejected();
                        cell.span=true;cell.rowSpan=number(xml,"rowSpan",true);cell.columnSpan=number(xml,"colSpan",true);
                    } else if(local.equals("t") && !cells.isEmpty() && cells.peek().table==tables.peek()) {
                        if(textOwner!=null || !namespace.equals(tables.peek().namespace))throw rejected();
                        textOwner=cells.peek();frame.textOwner=textOwner;
                    }
                } else if(event==XMLStreamConstants.CHARACTERS || event==XMLStreamConstants.CDATA || event==XMLStreamConstants.SPACE) {
                    if(textOwner!=null)budget.text(textOwner,xml.getText());
                } else if(event==XMLStreamConstants.END_ELEMENT) {
                    if(stack.isEmpty())throw rejected();var frame=stack.pop();
                    if(frame.textOwner!=null)textOwner=null;
                    if(frame.local.equals("p") && textOwner==null && !cells.isEmpty() && cells.peek().table==tables.peek()) {
                        var cell=cells.peek();if(cell.text.length()>0 && cell.text.charAt(cell.text.length()-1)!='\n')budget.text(cell,"\n");
                    }
                    if(frame.cell!=null) {
                        if(cells.pop()!=frame.cell)throw rejected();var cell=frame.cell;
                        if(!cell.address || !cell.span || cell.row!=cell.expectedRow)throw rejected();
                        cell.table.cells.add(new Cell(cell.path,cell.row,cell.column,cell.rowSpan,cell.columnSpan,cell.header,cell.text.toString(),cell.children));
                    }
                    if(frame.table!=null) {
                        if(tables.pop()!=frame.table)throw rejected();var table=frame.table;table.validate();
                        budget.tables.set(table.index,new Table(table.part,table.path,table.namespace,table.parentCell,table.rows,table.columns,table.cells));
                    }
                }
            }
            if(!stack.isEmpty() || !tables.isEmpty() || !cells.isEmpty())throw rejected();
        } finally {xml.close();}
    }
    private static final class Budget {
        int nodes,cells,text;long grid,pathCharacters;
        final List<Table> tables=new ArrayList<>();final Set<String> paths=new HashSet<>();
        void path(String part,String path) {if((pathCharacters+=part.length()+path.length())>4_000_000 || !paths.add(part+"#"+path))throw rejected();}
        void text(CellBuilder cell,String value) {if(value.length()>4_000_000-text)throw rejected();text+=value.length();cell.text.append(value);}
    }
    private static final class Frame {
        final String local,namespace,path;final Map<String,Integer> children=new HashMap<>();
        TableBuilder table,rowTable;CellBuilder cell,textOwner;int row;
        Frame(String local,String namespace,String path) {this.local=local;this.namespace=namespace;this.path=path;}
    }
    private static final class TableBuilder {
        final String part,path,namespace,parentCell;final int rows,columns,index;int rowsSeen;
        final List<Cell> cells=new ArrayList<>();
        TableBuilder(String part,String path,String namespace,String parentCell,int rows,int columns,int index) {this.part=part;this.path=path;this.namespace=namespace;this.parentCell=parentCell;this.rows=rows;this.columns=columns;this.index=index;}
        void validate() {
            if(rowsSeen!=rows)throw rejected();boolean[] occupied=new boolean[rows*columns];
            for(var cell:cells) {
                if(cell.row()<0 || cell.row()>=rows || cell.column()<0 || cell.column()>=columns || cell.rowSpan()<1 || cell.columnSpan()<1
                    || cell.rowSpan()>rows-cell.row() || cell.columnSpan()>columns-cell.column())throw rejected();
                for(int row=cell.row();row<cell.row()+cell.rowSpan();row++)for(int col=cell.column();col<cell.column()+cell.columnSpan();col++) {
                    int position=row*columns+col;if(occupied[position])throw rejected();occupied[position]=true;
                }
            }
            for(boolean present:occupied)if(!present)throw rejected();
        }
    }
    private static final class CellBuilder {
        final TableBuilder table;final String path,header;final int expectedRow;
        final StringBuilder text=new StringBuilder();final List<String> children=new ArrayList<>();
        boolean address,span;int row,column,rowSpan,columnSpan;
        CellBuilder(TableBuilder table,String path,int expectedRow,String header) {this.table=table;this.path=path;this.expectedRow=expectedRow;this.header=header;}
    }
}
