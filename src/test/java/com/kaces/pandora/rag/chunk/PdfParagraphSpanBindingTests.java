package com.kaces.pandora.rag.chunk;

import com.kaces.pandora.common.text.LawHashUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfParagraphSpanBindingTests {
    private final String first = "※ 시설을 임시로 설치한";
    private final String second = "경우에만 심사 대상에서 제외한다.";
    private final String raw = "제목\n※ \u0007시설을 임시로 설치한\n" + second + "\n다음 문단";
    private final int start = 3;
    private final int end = raw.indexOf("\n다음");

    @Test
    void bindsOnlyTheExactChunkExcerptWithAnExplicitLegacyControlOffset() {
        assertThat(PdfParagraphSpanBinding.bind(raw, LawHashUtils.sha256(raw), start, end,
                first, second, 2)).contains(first + " " + second);
    }

    @Test
    void rejectsChangedChunkOffsetsReorderedTextAndUnlistedControl() {
        String hash = LawHashUtils.sha256(raw);
        assertThat(PdfParagraphSpanBinding.bind(raw+" ", hash, start, end, first, second, 2)).isEmpty();
        assertThat(PdfParagraphSpanBinding.bind(raw, hash, start, end+1, first, second, 2)).isEmpty();
        assertThat(PdfParagraphSpanBinding.bind(raw, hash, start, end, second, first, 2)).isEmpty();
        assertThat(PdfParagraphSpanBinding.bind(raw, hash, start, end, first, second, -1)).isEmpty();
        assertThat(PdfParagraphSpanBinding.bind(raw, hash, start, end, first, second, 3)).isEmpty();
    }
}
