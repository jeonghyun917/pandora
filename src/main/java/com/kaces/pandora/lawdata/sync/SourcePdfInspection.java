package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.common.text.LawHashUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Native PDF parsing is confined to an owned, memory-capped JVM with a hard parent deadline. */
public final class SourcePdfInspection {
    record Result(String artifactHash,String textHash,String structureHash,int pageCount,int characterCount) { }
    private record WorkerOutput(String expectedHash,byte[] bytes) { }
    private static final Semaphore WORKERS=new Semaphore(2);
    private static final ExecutorService READERS=Executors.newFixedThreadPool(2,task->{var thread=new Thread(task,"source-pdf-bounded-output");thread.setDaemon(true);return thread;});
    private final long timeoutMillis;
    SourcePdfInspection() {this(10_000);}
    SourcePdfInspection(long timeoutMillis) {
        if(timeoutMillis<1 || timeoutMillis>10_000)throw new IllegalArgumentException("Bounded PDF worker deadline required");
        this.timeoutMillis=timeoutMillis;
    }
    Result inspect(Path file) {
        byte[] output=run(file).bytes();
        var records=new String(output,StandardCharsets.UTF_8).lines().filter(line->line.startsWith("PDF_NATIVE_V1 ")).toList();
        if(records.size()!=1 || !records.get(0).matches("PDF_NATIVE_V1 [0-9a-f]{64} [0-9a-f]{64} [0-9]{1,4} [0-9]{1,7}"))
            throw new IllegalStateException("PDF worker response unavailable");
        String[] fields=records.get(0).split(" ");int pages=Integer.parseInt(fields[3]),characters=Integer.parseInt(fields[4]);
        if(pages<1 || pages>1000 || characters<1 || characters>4_000_000)throw new IllegalStateException("PDF native output bounds rejected");
        return new Result(fields[1],fields[2],LawHashUtils.sha256Json(List.of("pdf-native-no-table-proof-v1",pages,fields[2])),pages,characters);
    }
    SourcePdfPositions.Result positions(Path file) {
            var response=run(file,"positions");
            String output=new String(response.bytes(),StandardCharsets.UTF_8).strip();
            if(!output.matches("PDF_POSITIONS_V1 [0-9a-f]{64} [A-Za-z0-9+/=]{1,49152}"))throw new IllegalStateException("PDF position protocol rejected");
            String[] fields=output.split(" ");if(!response.expectedHash().equals(fields[1]))throw new IllegalStateException("PDF position input identity changed");
            return SourcePdfPositions.decode(fields[1],Base64.getDecoder().decode(fields[2]));
    }
    TaggedPdfParagraph.Lines paragraph(Path file,String originalHash,int page,int first,int second) {
        if(originalHash==null || !originalHash.matches("[0-9a-f]{64}") || page<1 || page>1000
                || first<0 || second<0 || first==second) throw new IllegalArgumentException("Invalid paragraph identity");
        String[] coordinates={Integer.toString(page),Integer.toString(first),Integer.toString(second)};
        var response=run(file,"paragraph",coordinates[0],coordinates[1],coordinates[2]);
        String prefix="PDF_PARAGRAPH_V1 "+originalHash+" "+String.join(" ",coordinates)+" ";
        String output=new String(response.bytes(),StandardCharsets.US_ASCII).strip();
        if(!output.startsWith(prefix)) throw new IllegalStateException("PDF paragraph identity rejected");
        String[] lines=output.substring(prefix.length()).split(" ",-1);
        if(lines.length!=2) throw new IllegalStateException("PDF paragraph protocol rejected");
        return new TaggedPdfParagraph.Lines(decodeLine(lines[0]),decodeLine(lines[1]));
    }
    private static String decodeLine(String encoded) {
        if(encoded.length()>21_848 || !encoded.matches("[A-Za-z0-9+/]+={0,2}"))
            throw new IllegalStateException("PDF paragraph line encoding rejected");
        try {
            String line=StandardCharsets.UTF_8.newDecoder().decode(
                java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(encoded))).toString();
            if(line.isBlank() || line.length()>4096 || line.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalStateException("PDF paragraph line rejected");
            return line;
        } catch(IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalStateException("PDF paragraph line encoding rejected",invalid);
        }
    }
    List<String> reviewedLines(Path file,String hash,int page,int first,int last) {
        if(hash==null || !hash.matches("[0-9a-f]{64}") || page<1 || page>1000
                || first<1 || last<first || last-first>=256)
            throw new IllegalArgumentException("Invalid reviewed coordinates");
        var response=run(file,"reviewed-lines",hash,Integer.toString(page),Integer.toString(first),Integer.toString(last));
        String prefix="PDF_REVIEWED_LINES_V1 "+hash+" "+page+" "+first+" "+last+" ";
        String output=new String(response.bytes(),StandardCharsets.US_ASCII).strip();
        if(!output.startsWith(prefix))throw new IllegalStateException("Reviewed line identity rejected");
        String[] fields=output.substring(prefix.length()).split(" ",-1);
        if(fields.length!=last-first+1)throw new IllegalStateException("Incomplete reviewed lines");
        return Arrays.stream(fields).map(SourcePdfInspection::decodeLine).toList();
    }
    public static List<String> readReviewedLines(Path file,String hash,int page,int first,int last) {
        return new SourcePdfInspection().reviewedLines(file,hash,page,first,last);
    }
    /** Offline complete-source replay; every batch is tied to the same original bytes. */
    public static com.kaces.pandora.rag.importing.ExtractedDocument normalizedDocument(Path file,String hash) {
        if(hash==null || !hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Exact original hash required");
        var inspection=new SourcePdfInspection();
        var identity=inspection.inspect(file);
        if(!hash.equals(identity.artifactHash()))throw new IllegalStateException("Normalized source identity changed");
        var pages=new ArrayList<com.kaces.pandora.rag.importing.ExtractedPage>();
        int characters=0;
        for(int first=1;first<=identity.pageCount();first+=8) {
            int last=Math.min(identity.pageCount(),first+7);
            var response=inspection.run(file,"normalized-pages",hash,Integer.toString(first),Integer.toString(last));
            String prefix="PDF_NORMALIZED_PAGES_V1 "+hash+" "+first+" "+last+" ";
            String output=new String(response.bytes(),StandardCharsets.US_ASCII).strip();
            if(!output.startsWith(prefix))throw new IllegalStateException("Normalized page identity rejected");
            String[] fields=output.substring(prefix.length()).split(" ",-1);
            if(fields.length!=last-first+1)throw new IllegalStateException("Normalized page count rejected");
            for(int offset=0;offset<fields.length;offset++) {
                String text=decodePage(fields[offset]);
                characters+=text.length();
                if(characters>4_000_000)throw new IllegalStateException("Normalized document character bound exceeded");
                pages.add(new com.kaces.pandora.rag.importing.ExtractedPage(first+offset,text));
            }
        }
        return new com.kaces.pandora.rag.importing.ExtractedDocument(List.copyOf(pages));
    }
    private static String decodePage(String encoded) {
        if("-".equals(encoded))return "";
        if(encoded.length()>43_692 || !encoded.matches("[A-Za-z0-9+/]+={0,2}"))
            throw new IllegalStateException("Normalized page encoding rejected");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(encoded))).toString();
            if(text.length()>32_768 || text.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n'))
                throw new IllegalStateException("Normalized page content rejected");
            return text;
        } catch(IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalStateException("Normalized page encoding rejected",invalid);
        }
    }
    private WorkerOutput run(Path file,String... mode) {
        requireReviewedEnvironment(System.getenv());
        if(!WORKERS.tryAcquire())throw new IllegalStateException("PDF inspection capacity unavailable");
        Process worker=null;Future<byte[]> reader=null;
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)<5 || Files.size(file)>SourceReferenceInspector.MAX_BYTES)
                throw new IllegalStateException("Bounded PDF worker input required");
            String expectedHash=null;
            if(mode.length==1 && "positions".equals(mode[0]))try(var input=Files.newInputStream(file)) {
                byte[] bytes=input.readNBytes(SourceReferenceInspector.MAX_BYTES+1);
                if(bytes.length<5 || bytes.length>SourceReferenceInspector.MAX_BYTES)throw new IllegalStateException("Position input byte bound rejected");
                expectedHash=SourceReferenceInspector.artifactHash(bytes);
            }
            worker=new ProcessBuilder(command(file,mode)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process active=worker;reader=READERS.submit(()->active.getInputStream().readNBytes(65_537));
            if(!worker.waitFor(timeoutMillis,TimeUnit.MILLISECONDS))throw new IllegalStateException("PDF inspection deadline exceeded");
            byte[] output=reader.get(1,TimeUnit.SECONDS);
            if(worker.exitValue()!=0 || output.length>65_536)throw new IllegalStateException("PDF inspection worker rejected input");
            return new WorkerOutput(expectedHash,output);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("PDF inspection interrupted");
        } catch(java.io.IOException|ExecutionException|TimeoutException invalid) {throw new IllegalStateException("PDF inspection worker unavailable",invalid);}
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
            if(reader!=null)reader.cancel(true);
        }
    }
    static void requireReviewedEnvironment(Map<String,?> environment) {
        if(!Collections.disjoint(environment.keySet(),Set.of("_JAVA_OPTIONS","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS")))
            throw new IllegalStateException("Inherited JVM options require explicit resource-policy review");
    }
    static List<String> command(Path file) {
        return command(file,new String[0]);
    }
    private static List<String> command(Path file,String... mode) {
        var command=new ArrayList<String>();
        String executable=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java";
        command.add(Path.of(System.getProperty("java.home"),"bin",executable).toString());
        command.addAll(List.of("-Xmx128m","-XX:MaxMetaspaceSize=96m","-XX:MaxDirectMemorySize=32m","-Dfile.encoding=UTF-8"));
        boolean packaged=ClassLoader.getSystemResource("org/springframework/boot/loader/launch/PropertiesLauncher.class")!=null;
        if(packaged)command.add("-Dloader.main="+SourcePdfWorker.class.getName());
        command.addAll(List.of("-cp",System.getProperty("java.class.path"),packaged?"org.springframework.boot.loader.launch.PropertiesLauncher":SourcePdfWorker.class.getName(),file.toAbsolutePath().toString()));
        command.addAll(List.of(mode));
        return List.copyOf(command);
    }
}
