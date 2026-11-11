package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import com.kaces.pandora.common.text.LawHashUtils;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagPdfTrustedInputTests {
    @TempDir Path root;
    @Test void onlyExactServerManifestAndProductCanSupplySourcePath() throws Exception {
        var source=root.resolve("original.pdf");Files.write(source,new byte[]{1,2,3,4,5});
        var draft=new ReviewedPdfDraft(42,"a".repeat(64),"b".repeat(64),"c".repeat(40),"Official rule","https://agency.example/rule.pdf",
                List.of(new ReviewedPdfDraft.Unit("scope","Scope",new ReviewedPdfUnit(List.of(new ReviewedPdfUnit.Line(1,1,"Preserve the original."))))));
        var input=new RagPdfTrustedInput.Input(draft,source.toString());
        String encoded=new tools.jackson.databind.ObjectMapper().writeValueAsString(input);
        var manifest=root.resolve("approved.json");Files.writeString(manifest,encoded);
        String digest=LawHashUtils.sha256(encoded);
        var reader=new RagPdfTrustedInput(manifest.toString(),digest,draft.productSha());
        assertThat(reader.read()).isEqualTo(input);
        assertThatThrownBy(()->new RagPdfTrustedInput(manifest.toString(),digest,"d".repeat(40)).read()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new RagPdfTrustedInput("","",draft.productSha()).read()).isInstanceOf(IllegalStateException.class);
        Files.writeString(manifest,encoded.replace("Official rule","Unreviewed title"));
        assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class);
        Files.writeString(manifest,encoded);
        assertThat(reader.read()).isEqualTo(input);
    }
}
