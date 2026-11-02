package com.kaces.pandora.lawdata.sync;
import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class SourcePdfPositionsTests {
    @Test void boundedCodecRetainsNativeCoordinatesAndSourceText() throws Exception {
        var pages=List.of(new SourcePdfPositions.Page(595f,842f));
        var glyphs=List.of(new SourcePdfPositions.Glyph(1,20f,30f,10f,8f,"90% 이상"));
        var result=SourcePdfPositions.decode("a".repeat(64),SourcePdfPositions.encode(pages,glyphs));
        assertThat(result.glyphs().get(0).text()).isEqualTo("90% 이상");assertThat(result.glyphs().get(0).x()).isEqualTo(20f);
        assertThat(result.pages().get(0).height()).isEqualTo(842f);
        assertThatThrownBy(()->result.glyphs().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void forgedNonFiniteOutOfPageAndTrailingDataCannotQualify() throws Exception {
        for(float x:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1f,99f}) {
            var body=new ByteArrayOutputStream();try(var data=new DataOutputStream(body)) {
                data.writeInt(0x50504731);data.writeInt(1);data.writeFloat(100);data.writeFloat(100);data.writeInt(1);
                data.writeInt(1);data.writeFloat(x);data.writeFloat(10);data.writeFloat(5);data.writeFloat(5);data.writeUTF("x");
            }
            byte[] encoded=compressed(body.toByteArray());
            assertThatThrownBy(()->SourcePdfPositions.decode("a".repeat(64),encoded)).isInstanceOf(IllegalStateException.class);
        }
        byte[] valid=SourcePdfPositions.encode(List.of(new SourcePdfPositions.Page(100,100)),List.of(new SourcePdfPositions.Glyph(1,10,10,5,5,"x")));
        assertThatThrownBy(()->SourcePdfPositions.decode("bad",valid)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SourcePdfPositions.decode("a".repeat(64),compressed(new byte[512*1024+1]))).isInstanceOf(IllegalStateException.class);
        byte[] raw;try(var gzip=new java.util.zip.GZIPInputStream(new ByteArrayInputStream(valid))){raw=gzip.readAllBytes();}
        byte[] trailing=Arrays.copyOf(raw,raw.length+1);
        assertThatThrownBy(()->SourcePdfPositions.decode("a".repeat(64),compressed(trailing))).isInstanceOf(IllegalStateException.class);
        byte[] extra=Arrays.copyOf(valid,valid.length+1);extra[extra.length-1]=1;
        assertThatThrownBy(()->SourcePdfPositions.decode("a".repeat(64),extra)).isInstanceOf(IllegalStateException.class);
    }
    private static byte[] compressed(byte[] raw) throws Exception {
        var out=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(out)){gzip.write(raw);}return out.toByteArray();
    }
}
