package com.kaces.pandora.lawdata.sync;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourcePdfInspectionTests {
    @TempDir Path root;
    @Test void actualWorkerReturnsOnlyBoundedNativeHashes() throws Exception {
        Path file=root.resolve("reference.pdf");Files.write(file,SourceReferenceInspectorTests.pdf());
        var result=new SourcePdfInspection().inspect(file);
        assertThat(result.textHash()).matches("[0-9a-f]{64}");
        assertThat(result.structureHash()).matches("[0-9a-f]{64}");
        assertThat(result.characterCount()).isPositive().isLessThanOrEqualTo(4_000_000);
        assertThat(result.pageCount()).isOne();
    }
    @Test void malformedInputAndDeadlineCannotLeaveAQualifyingResult() throws Exception {
        Path file=root.resolve("bad.pdf");Files.writeString(file,"%PDF-malformed");
        assertThatThrownBy(()->new SourcePdfInspection().inspect(file)).isInstanceOf(IllegalStateException.class);
        Files.write(file,SourceReferenceInspectorTests.pdf());
        assertThatThrownBy(()->new SourcePdfInspection(1).inspect(file)).isInstanceOf(IllegalStateException.class);
    }
    @Test void launchUsesOnlyFixedServerOwnedResourceArguments() {
        var command=SourcePdfInspection.command(root.resolve("reference.pdf"));
        assertThat(command).contains("-Xmx128m","-XX:MaxMetaspaceSize=96m","-XX:MaxDirectMemorySize=32m");
        assertThat(command).doesNotContain("-jar","-ExecutionPolicy","Bypass");
        assertThat(command.get(0)).startsWith(System.getProperty("java.home"));
    }
    @Test void inheritedJvmOptionsMustBeRejectedNotSilentlyRemovedOrTrusted() {
        for(String name:java.util.List.of("_JAVA_OPTIONS","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS"))
            assertThatThrownBy(()->SourcePdfInspection.requireReviewedEnvironment(java.util.Map.of(name,"-Xmx2g"))).isInstanceOf(IllegalStateException.class);
        assertThatCode(()->SourcePdfInspection.requireReviewedEnvironment(java.util.Map.of("JAVA_HOME","trusted"))).doesNotThrowAnyException();
    }
}
