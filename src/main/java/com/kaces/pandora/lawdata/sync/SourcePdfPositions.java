package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.common.text.LawHashUtils;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Bounded native positions, not rendering/table/semantic equivalence. */
final class SourcePdfPositions {
    static final int EXPANDED=512*1024,COMPRESSED=36*1024,GLYPHS=8192;
    record Page(float width,float height) { }
    record Glyph(int page,float x,float y,float width,float height,String text) { }
    record Result(String artifactHash,String geometryHash,List<Page> pages,List<Glyph> glyphs) {
        Result {pages=List.copyOf(pages);glyphs=List.copyOf(glyphs);}
    }
    static byte[] encode(List<Page> pages,List<Glyph> glyphs) {
        validate(pages,glyphs);
        try {
            var raw=new LimitedOutput(EXPANDED);
            try(var data=new DataOutputStream(raw)) {
                data.writeInt(0x50504731);data.writeInt(pages.size());
                for(var page:pages){data.writeFloat(page.width());data.writeFloat(page.height());}
                data.writeInt(glyphs.size());
                for(var glyph:glyphs){data.writeInt(glyph.page());data.writeFloat(glyph.x());data.writeFloat(glyph.y());data.writeFloat(glyph.width());data.writeFloat(glyph.height());data.writeUTF(glyph.text());}
            }
            var compressed=new LimitedOutput(COMPRESSED);
            try(var gzip=new GZIPOutputStream(compressed)){gzip.write(raw.bytes());}
            return compressed.bytes();
        } catch(IOException invalid){throw new IllegalStateException("Bounded PDF position encoding unavailable",invalid);}
    }
    static Result decode(String artifact,byte[] encoded) {
        if(artifact==null || !artifact.matches("[0-9a-f]{64}") || encoded==null || encoded.length<1 || encoded.length>COMPRESSED)throw rejected();
        try {
            byte[] raw;
            try(var gzip=new GZIPInputStream(new ByteArrayInputStream(encoded))){raw=gzip.readNBytes(EXPANDED+1);}
            if(raw.length>EXPANDED)throw rejected();
            var pages=new ArrayList<Page>();var glyphs=new ArrayList<Glyph>();
            try(var data=new DataInputStream(new ByteArrayInputStream(raw))) {
                if(data.readInt()!=0x50504731)throw rejected();int count=data.readInt();if(count<1 || count>1000)throw rejected();
                for(int i=0;i<count;i++)pages.add(new Page(data.readFloat(),data.readFloat()));
                int characters=data.readInt();if(characters<1 || characters>GLYPHS)throw rejected();
                for(int i=0;i<characters;i++)glyphs.add(new Glyph(data.readInt(),data.readFloat(),data.readFloat(),data.readFloat(),data.readFloat(),data.readUTF()));
                if(data.read()!=-1)throw rejected();
            }
            validate(pages,glyphs);
            // This private worker protocol uses one canonical gzip stream, never
            // arbitrary concatenated members, headers or trailing compressed bytes.
            if(!Arrays.equals(encoded,encode(pages,glyphs)))throw rejected();
            return new Result(artifact,LawHashUtils.sha256Json(List.of("pdf-native-positions-v1",pages,glyphs)),pages,glyphs);
        } catch(IOException invalid){throw new IllegalStateException("PDF position response malformed",invalid);}
    }
    private static void validate(List<Page> pages,List<Glyph> glyphs) {
        if(pages==null || glyphs==null || pages.isEmpty() || pages.size()>1000 || glyphs.isEmpty() || glyphs.size()>GLYPHS)throw rejected();
        for(var page:pages)if(page==null || !positive(page.width()) || !positive(page.height()) || page.width()>100000 || page.height()>100000)throw rejected();
        for(var glyph:glyphs) {
            if(glyph==null || glyph.page()<1 || glyph.page()>pages.size() || glyph.text()==null || glyph.text().isEmpty() || glyph.text().length()>256
                || !Float.isFinite(glyph.x()) || !Float.isFinite(glyph.y()) || glyph.x()<0 || glyph.y()<0 || !positive(glyph.width()) || !positive(glyph.height()))throw rejected();
            var page=pages.get(glyph.page()-1);
            if(glyph.x()+glyph.width()>page.width() || glyph.y()+glyph.height()>page.height())throw rejected();
        }
    }
    private static boolean positive(float value){return Float.isFinite(value) && value>0;}
    private static IllegalStateException rejected(){return new IllegalStateException("Unsupported or over-bound PDF native positions");}
    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream out=new ByteArrayOutputStream();private final int limit;
        LimitedOutput(int limit){this.limit=limit;}
        @Override public void write(int value)throws IOException{if(out.size()>=limit)throw new IOException("Position byte bound");out.write(value);}
        @Override public void write(byte[] bytes,int offset,int length)throws IOException{if(length<0 || length>limit-out.size())throw new IOException("Position byte bound");out.write(bytes,offset,length);}
        byte[] bytes(){return out.toByteArray();}
    }
}
