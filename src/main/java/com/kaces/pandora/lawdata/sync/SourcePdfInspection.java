package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.common.text.LawHashUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Native PDF parsing is confined to an owned, memory-capped JVM with a hard parent deadline. */
final class SourcePdfInspection {
    record Result(String artifactHash,String textHash,String structureHash,int pageCount,int characterCount) { }
    private static final Semaphore WORKERS=new Semaphore(2);
    private final long timeoutMillis;
    SourcePdfInspection() {this(10_000);}
    SourcePdfInspection(long timeoutMillis) {
        if(timeoutMillis<1 || timeoutMillis>10_000)throw new IllegalArgumentException("Bounded PDF worker deadline required");
        this.timeoutMillis=timeoutMillis;
    }
    Result inspect(Path file) {
        requireReviewedEnvironment(System.getenv());
        if(!WORKERS.tryAcquire())throw new IllegalStateException("PDF inspection capacity unavailable");
        Process worker=null;
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)<5 || Files.size(file)>SourceReferenceInspector.MAX_BYTES)
                throw new IllegalStateException("Bounded PDF worker input required");
            worker=new ProcessBuilder(command(file)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!worker.waitFor(timeoutMillis,TimeUnit.MILLISECONDS))throw new IllegalStateException("PDF inspection deadline exceeded");
            byte[] output=worker.getInputStream().readNBytes(65_537);
            if(worker.exitValue()!=0 || output.length>65_536)throw new IllegalStateException("PDF inspection worker rejected input");
            var records=new String(output,StandardCharsets.UTF_8).lines().filter(line->line.startsWith("PDF_NATIVE_V1 ")).toList();
            if(records.size()!=1 || !records.get(0).matches("PDF_NATIVE_V1 [0-9a-f]{64} [0-9a-f]{64} [0-9]{1,4} [0-9]{1,7}"))
                throw new IllegalStateException("PDF worker response unavailable");
            String[] fields=records.get(0).split(" ");int pages=Integer.parseInt(fields[3]),characters=Integer.parseInt(fields[4]);
            if(pages<1 || pages>1000 || characters<1 || characters>4_000_000)throw new IllegalStateException("PDF native output bounds rejected");
            return new Result(fields[1],fields[2],LawHashUtils.sha256Json(List.of("pdf-native-no-table-proof-v1",pages,fields[2])),pages,characters);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("PDF inspection interrupted");
        } catch(java.io.IOException invalid) {throw new IllegalStateException("PDF inspection worker unavailable",invalid);}
        finally {
            if(worker!=null) {
                if(worker.isAlive()) {
                    worker.destroyForcibly();
                    try {worker.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
                }
                try {worker.getInputStream().close();worker.getErrorStream().close();worker.getOutputStream().close();}catch(java.io.IOException ignored) { }
            }
            if(worker!=null && worker.isAlive())worker.onExit().whenComplete((ended,failure)->WORKERS.release());
            else WORKERS.release();
        }
    }
    static void requireReviewedEnvironment(Map<String,?> environment) {
        if(!Collections.disjoint(environment.keySet(),Set.of("_JAVA_OPTIONS","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS")))
            throw new IllegalStateException("Inherited JVM options require explicit resource-policy review");
    }
    static List<String> command(Path file) {
        var command=new ArrayList<String>();
        String executable=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java";
        command.add(Path.of(System.getProperty("java.home"),"bin",executable).toString());
        command.addAll(List.of("-Xmx128m","-XX:MaxMetaspaceSize=96m","-XX:MaxDirectMemorySize=32m","-Dfile.encoding=UTF-8"));
        boolean packaged=ClassLoader.getSystemResource("org/springframework/boot/loader/launch/PropertiesLauncher.class")!=null;
        if(packaged)command.add("-Dloader.main="+SourcePdfWorker.class.getName());
        command.addAll(List.of("-cp",System.getProperty("java.class.path"),packaged?"org.springframework.boot.loader.launch.PropertiesLauncher":SourcePdfWorker.class.getName(),file.toAbsolutePath().toString()));
        return List.copyOf(command);
    }
}
