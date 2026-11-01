package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.rag.importing.RagTextExtractor;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipFile;

/** Bounded native-content evidence only. It deliberately cannot authorize reference fidelity. */
public final class SourceReferenceInspector {
    static final int MAX_BYTES=8*1024*1024;
    record Download(int status,String mediaType,long declaredLength,InputStream body) { }
    @FunctionalInterface interface Transport {Download get(URI uri) throws Exception;}
    public record Inspection(String sourceUrl,String canonicalUrl,String artifactHash,long byteCount,
            String format,String nativeTextHash,String structureHash,String status,boolean fidelityReady) { }
    private final Transport transport;
    private final long bodyTimeoutMillis;
    private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES=deadlines();
    private static java.util.concurrent.ScheduledThreadPoolExecutor deadlines() {
        var executor=new java.util.concurrent.ScheduledThreadPoolExecutor(1,task->{var thread=new Thread(task,"source-reference-deadline");thread.setDaemon(true);return thread;});
        executor.setRemoveOnCancelPolicy(true);return executor;
    }
    public SourceReferenceInspector() {
        bodyTimeoutMillis=30_000;
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        transport=uri->{
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Accept-Encoding","identity").GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            return new Download(response.statusCode(),response.headers().firstValue("Content-Type").orElse(""),
                response.headers().firstValueAsLong("Content-Length").orElse(-1),response.body());
        };
    }
    SourceReferenceInspector(Transport transport) {this(transport,30_000);}
    SourceReferenceInspector(Transport transport,long bodyTimeoutMillis) {
        if(bodyTimeoutMillis<1 || bodyTimeoutMillis>30_000)throw new IllegalArgumentException("Bounded reference deadline required");
        this.transport=Objects.requireNonNull(transport);this.bodyTimeoutMillis=bodyTimeoutMillis;
    }
    static URI officialUri(String source) {
        try {
            if(source==null || source.length()>1000 || source.isBlank())throw new IllegalArgumentException();
            URI uri=URI.create(source);
            if(uri.getRawAuthority()!=null && uri.getScheme()==null)throw new IllegalArgumentException();
            if(uri.getScheme()!=null && !Set.of("http","https").contains(uri.getScheme()))throw new IllegalArgumentException();
            if(uri.isAbsolute() && (!Set.of("law.go.kr","www.law.go.kr").contains(Objects.toString(uri.getHost(),""))
                || uri.getRawUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=("https".equals(uri.getScheme())?443:80))))throw new IllegalArgumentException();
            if(uri.getRawFragment()!=null || !Set.of("/LSW/flDownload.do","/flDownload.do").contains(uri.getRawPath())
                || uri.getRawQuery()==null || !uri.getRawQuery().matches("flSeq=[1-9][0-9]{0,18}"))throw new IllegalArgumentException();
            return URI.create("https://www.law.go.kr/LSW/flDownload.do?"+uri.getRawQuery());
        } catch(RuntimeException invalid) {throw new IllegalArgumentException("Exact official reference download address required");}
    }
    public Inspection inspect(String source) {
        URI uri=officialUri(source);
        try {
            Download response=transport.get(uri);
            try(InputStream input=response.body()) {
                String media=Objects.toString(response.mediaType(),"").split(";",2)[0].trim().toLowerCase(Locale.ROOT);
                if(response.status()!=200 || !Set.of("application/pdf","application/octet-stream","application/zip","application/hwp+zip","application/haansofthwpx").contains(media)
                    || response.declaredLength()>MAX_BYTES || response.declaredLength()==0)throw new IllegalStateException("Reference response rejected");
                byte[] bytes=readBody(input);
                if(bytes.length==0 || bytes.length>MAX_BYTES || (response.declaredLength()>=0 && response.declaredLength()!=bytes.length))
                    throw new IllegalStateException("Reference size or completeness rejected");
                String format;
                if(bytes.length>=5 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))format="pdf";
                else if(bytes.length>=4 && bytes[0]=='P' && bytes[1]=='K' && bytes[2]==3 && bytes[3]==4)format="hwpx";
                else throw new IllegalStateException("Unsupported native reference format");
                if(media.equals("application/pdf") && !format.equals("pdf"))throw new IllegalStateException("Reference media signature mismatch");
                Path file=Files.createTempFile("pandora-source-reference-","."+format);
                try {
                    Files.write(file,bytes);
                    String artifact=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    if(format.equals("pdf")) {
                        var nativePdf=new SourcePdfInspection().inspect(file);
                        if(!artifact.equals(nativePdf.artifactHash()))throw new IllegalStateException("PDF worker input identity changed");
                        return new Inspection(source,uri.toString(),artifact,bytes.length,format,nativePdf.textHash(),nativePdf.structureHash(),"INTEGRITY_ONLY",false);
                    }
                    preflightHwpx(file);
                    var extracted=new RagTextExtractor().extract(file);
                    String text=extracted.text();
                    if(text.isBlank() || text.length()>4_000_000 || extracted.pages().size()>1000)throw new IllegalStateException("Native reference text unavailable or oversized");
                    return new Inspection(source,uri.toString(),artifact,bytes.length,
                        format,LawHashUtils.sha256(text),LawHashUtils.sha256Json(extracted.pages()),"INTEGRITY_ONLY",false);
                } finally {Files.deleteIfExists(file);}
            }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("Reference inspection interrupted");
        } catch(Exception invalid) {throw new IllegalStateException("Official reference inspection failed",invalid);}
    }
    private byte[] readBody(InputStream input) throws IOException {
        var expired=new java.util.concurrent.atomic.AtomicBoolean();
        var deadline=DEADLINES.schedule(()->{expired.set(true);try {input.close();}catch(IOException ignored) { }},bodyTimeoutMillis,java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            byte[] bytes=input.readNBytes(MAX_BYTES+1);
            if(expired.get())throw new IOException("Reference body deadline exceeded");
            return bytes;
        } finally {deadline.cancel(false);}
    }
    private static void preflightHwpx(Path file) throws IOException {
        try(var zip=new ZipFile(file.toFile())) {
            var entries=zip.stream().toList();
            if(entries.size()>2048)throw new IllegalStateException("Reference archive entry bound exceeded");
            var names=new HashSet<String>();long total=0;
            for(var entry:entries) {
                String name=entry.getName();
                if(!names.add(name) || name.startsWith("/") || name.contains("\\") || Arrays.asList(name.split("/")).contains(".."))
                    throw new IllegalStateException("Reference archive path rejected");
                if(entry.isDirectory())continue;
                try(var input=zip.getInputStream(entry)) {
                    byte[] expanded=input.readNBytes(8*1024*1024+1);
                    total+=expanded.length;
                    if(expanded.length>8*1024*1024 || total>32*1024*1024 || (entry.getSize()>=0 && expanded.length!=entry.getSize()))
                        throw new IllegalStateException("Reference archive expansion bound exceeded");
                }
            }
            var mime=zip.getEntry("mimetype");
            if(mime==null)throw new IllegalStateException("HWPX native identity missing");
            try(var input=zip.getInputStream(mime)) {
                if(!new String(input.readNBytes(100),StandardCharsets.US_ASCII).trim().equals("application/hwp+zip"))throw new IllegalStateException("HWPX native identity rejected");
            }
            if(names.stream().noneMatch(name->name.matches("Contents/section[0-9]+[.]xml")))throw new IllegalStateException("HWPX native sections missing");
        }
    }
}
