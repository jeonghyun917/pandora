package com.kaces.pandora.lawdata.sync;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SourceReferenceDeclarationsTests {
    private final ObjectMapper json=new ObjectMapper();
    @Test void exactDeclarationOwnersNotFilenamesDetermineProvenance() {
        var root=new LinkedHashMap<String,Object>();
        root.put("별표",Map.of("별표단위",Map.of("별표제목","배점 기준","별표내용",List.of("기준 본문"),"별표서식파일링크","/flDownload.do?flSeq=1")));
        root.put("첨부파일",Map.of("첨부파일명","무조건 안전한 원문.hwpx","첨부파일링크",List.of("/flDownload.do?flSeq=2","/flDownload.do?flSeq=2")));
        root.put("검토",Map.of("별표서식파일링크","/flDownload.do?flSeq=3"));
        var result=new SourceReferenceDeclarations().capture(json.writeValueAsString(Map.of("AdmRulService",root)));
        assertThat(result).extracting(SourceReferenceDeclarations.Declaration::kind).containsExactly("ANNEX_DECLARATION","GENERAL_ATTACHMENT_DECLARATION","GENERAL_ATTACHMENT_DECLARATION","UNCLASSIFIED_DECLARATION");
        assertThat(result.get(0).sourcePath()).isEqualTo("/AdmRulService/별표/별표단위/별표서식파일링크");
        assertThat(result.get(1).sourcePath()).endsWith("/첨부파일링크/0");
        assertThat(result.get(2).sourcePath()).endsWith("/첨부파일링크/1");
        assertThat(result.get(0).ownerHash()).matches("[0-9a-f]{64}");
        var changed=new LinkedHashMap<>(root);changed.put("별표",Map.of("별표단위",Map.of("별표내용","변경 본문","별표서식파일링크","/flDownload.do?flSeq=1")));
        assertThat(new SourceReferenceDeclarations().capture(json.writeValueAsString(Map.of("AdmRulService",changed))).get(0).ownerHash()).isNotEqualTo(result.get(0).ownerHash());
    }
    @Test void malformedAmbiguousAndUnboundedDeclarationsReject() {
        for(Object value:List.of(3,Map.of("link","/flDownload.do?flSeq=1"),List.of(3),Collections.nCopies(65,"/flDownload.do?flSeq=1"))) {
            String raw=json.writeValueAsString(Map.of("AdmRulService",Map.of("첨부파일링크",value)));
            assertThatThrownBy(()->new SourceReferenceDeclarations().capture(raw)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(()->new SourceReferenceDeclarations().capture("{\"AdmRulService\":{},\"법령\":{}}" )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new SourceReferenceDeclarations().capture("{\"AdmRulService\":[]}" )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new SourceReferenceDeclarations().capture("{\"AdmRulService\":{\"원문파일\":\"/flDownload.do?flSeq=1\",\"원문파일\":\"/flDownload.do?flSeq=2\"}}" )).isInstanceOf(IllegalStateException.class);
    }
}
