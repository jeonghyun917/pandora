package com.kaces.pandora.lawdata.sync;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/** Internal bounded native-evidence worker. Never run PDFBox in the serving JVM for reference inspection. */
public final class SourcePdfWorker {
    private SourcePdfWorker() { }
    public static void main(String[] args) throws Exception {
        // This entrypoint runs only in the owned child JVM. It must also expire if its parent dies.
        var watchdog=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"pdf-worker-hard-deadline");thread.setDaemon(true);return thread;});
        watchdog.schedule(()->Runtime.getRuntime().halt(124),8,java.util.concurrent.TimeUnit.SECONDS);
        try {inspect(args);}finally {watchdog.shutdownNow();}
    }
    private static void inspect(String[] args) throws Exception {
        SourcePdfInspection.requireReviewedEnvironment(System.getenv());
        var vm=java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        if(vm==null || Runtime.getRuntime().maxMemory()>128L*1024*1024
            || Long.parseLong(vm.getVMOption("MaxMetaspaceSize").getValue())>96L*1024*1024
            || Long.parseLong(vm.getVMOption("MaxDirectMemorySize").getValue())>32L*1024*1024)
            throw new IllegalStateException("Effective PDF worker memory caps unavailable");
        boolean positions=args.length==2 && args[1].equals("positions");
        boolean paragraph=args.length==5 && args[1].equals("paragraph");
        if(args.length!=1 && !positions && !paragraph)throw new IllegalArgumentException("Exact worker input/mode required");
        Path file=Path.of(args[0]);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>8*1024*1024)throw new IOException("Input bound rejected");
        byte[] bytes;
        try(var input=Files.newInputStream(file)) {bytes=input.readNBytes(8*1024*1024+1);}
        if(bytes.length<5 || bytes.length>8*1024*1024)throw new IOException("Input bound rejected");
        try(var document=Loader.loadPDF(bytes)) {
            int pages=document.getNumberOfPages();
            if(document.isEncrypted() || pages<1 || pages>1000)throw new IOException("PDF native bounds rejected");
            if(paragraph) {
                int page=Integer.parseInt(args[2]), first=Integer.parseInt(args[3]), second=Integer.parseInt(args[4]);
                var lines=TaggedPdfParagraph.read(document,page,first,second).orElseThrow(()->new IOException("Unbound tagged spans"));
                var encoder=Base64.getEncoder();
                System.out.println("PDF_PARAGRAPH_V1 "+hash(bytes)+" "+page+" "+first+" "+second+" "
                    +encoder.encodeToString(lines.first().getBytes(StandardCharsets.UTF_8))+" "
                    +encoder.encodeToString(lines.second().getBytes(StandardCharsets.UTF_8)));
                return;
            }
            var output=new BoundedText();
            var positioned=positions?new Positions(document):null;
            (positions?positioned:new PDFTextStripper()).writeText(document,output);
            if(output.text.toString().isBlank())throw new IOException("Native PDF text unavailable");
            if(positions)System.out.println("PDF_POSITIONS_V1 "+hash(bytes)+" "+Base64.getEncoder().encodeToString(SourcePdfPositions.encode(positioned.pages,positioned.glyphs)));
            else System.out.println("PDF_NATIVE_V1 "+hash(bytes)+" "+hash(output.text.toString().getBytes(StandardCharsets.UTF_8))+" "+pages+" "+output.text.length());
        }
    }
    private static String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static final class Positions extends PDFTextStripper {
        private final List<SourcePdfPositions.Page> pages=new ArrayList<>();private final List<SourcePdfPositions.Glyph> glyphs=new ArrayList<>();
        Positions(org.apache.pdfbox.pdmodel.PDDocument document)throws IOException {
            setSuppressDuplicateOverlappingText(false);
            for(var page:document.getPages()) {
                if(page.getRotation()!=0)throw new IOException("Rotated position evidence unsupported");
                var box=page.getCropBox();pages.add(new SourcePdfPositions.Page(box.getWidth(),box.getHeight()));
            }
        }
        @Override protected void processTextPosition(org.apache.pdfbox.text.TextPosition text) {
            if(glyphs.size()>=SourcePdfPositions.GLYPHS || text.getDir()!=0)throw new IllegalStateException("PDF position count/direction unsupported");
            glyphs.add(new SourcePdfPositions.Glyph(getCurrentPageNo(),text.getXDirAdj(),text.getYDirAdj()-text.getHeightDir(),text.getWidthDirAdj(),text.getHeightDir(),text.getUnicode()));
            super.processTextPosition(text);
        }
    }
    private static final class BoundedText extends Writer {
        private final StringBuilder text=new StringBuilder();
        @Override public void write(char[] chars,int offset,int count) throws IOException {
            if(count<0 || text.length()>4_000_000-count)throw new IOException("PDF native character bound exceeded");
            text.append(chars,offset,count);
        }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
