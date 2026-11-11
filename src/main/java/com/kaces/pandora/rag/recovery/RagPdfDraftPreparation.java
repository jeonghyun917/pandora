package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.lawdata.sync.SourcePdfInspection;
import com.kaces.pandora.rag.chunk.*;
import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import java.nio.file.*;
import java.util.*;

/** Deterministic in-memory preparation only. Never inserts chunks or creates serving/index receipts. */
final class RagPdfDraftPreparation {
    record UnitBinding(String key,int sortOrder,List<ReviewedPdfUnit.Line> positions,String textHash) {
        UnitBinding {positions=List.copyOf(positions);}
    }
    record Prepared(String reviewHash,String sourceHash,String baselineHash,String productSha,
            String extractionHash,String manifest,List<RagDocumentChunkRow> chunks,List<UnitBinding> units) {
        Prepared {chunks=List.copyOf(chunks);units=List.copyOf(units);}
    }
    private final RagPdfDraftStore store;
    RagPdfDraftPreparation(RagPdfDraftStore store){this.store=Objects.requireNonNull(store);}

    Prepared prepare(String id) {
        var input=store.load(id);
        var draft=input.draft();
        Path original=null;
        try {
            original=Files.createTempFile("pandora-reviewed-source-",".pdf");
            Files.write(original,input.sourceBytes(),StandardOpenOption.TRUNCATE_EXISTING);
            var extracted=SourcePdfInspection.normalizedDocument(original,draft.sourceHash());
            String extractionHash=LawHashUtils.sha256Json(extracted.pages());
            var gate=new RagChunkQualityGate();
            var whole=gate.evaluate(draft.title(),new RagChunker().chunkV4(draft.documentId(),extracted,
                    draft.sourceUrl(),draft.title(),draft.sourceHash()));
            var chunks=new ArrayList<>(whole.retainedChunks());
            var bindings=new ArrayList<UnitBinding>();
            int next=chunks.stream().mapToInt(RagDocumentChunkRow::sortOrder).max().orElse(-1)+1;
            for(int index=0;index<draft.units().size();index++) {
                var unit=draft.units().get(index);
                String text=unit.review().replay(extracted.pages());
                var pages=unit.review().lines().stream().map(ReviewedPdfUnit.Line::page).distinct().toList();
                Integer page=pages.size()==1?pages.get(0):null;
                // Identical body text does not establish identical ownership or line coordinates.
                String embedding=draft.title()+"\n"+unit.heading()+"\n"+text;
                var row=new RagDocumentChunkRow(0,draft.documentId(),4,"reviewed:"+unit.key(),
                        draft.title(),unit.heading(),"body",text,embedding,page,
                        "$.reviewedPdf.units["+index+"]",draft.sourceUrl(),next++,LawHashUtils.sha256(embedding));
                var reviewed=gate.evaluate(draft.title(),List.of(row)).retainedChunks();
                if(reviewed.size()!=1)throw new IllegalStateException("Reviewed unit failed existing chunk quality gate");
                chunks.add(reviewed.get(0));
                bindings.add(new UnitBinding(unit.key(),row.sortOrder(),unit.review().lines(),LawHashUtils.sha256(text)));
            }
            if(chunks.isEmpty() || chunks.size()>10000)throw new IllegalStateException("Prepared source chunk bounds rejected");
            var current=store.load(id);
            if(!draft.equals(current.draft()) || !Arrays.equals(input.sourceBytes(),current.sourceBytes()))
                throw new IllegalStateException("Quarantined source changed during preparation");
            String manifest=LawHashUtils.sha256Json(List.of("reviewed-pdf-preparation-v1",draft.reviewHash(),
                    extractionHash,chunks,bindings,whole.rejectedChunks()));
            return new Prepared(draft.reviewHash(),draft.sourceHash(),draft.baselineHash(),draft.productSha(),
                    extractionHash,manifest,chunks,bindings);
        } catch(java.io.IOException unavailable) {
            throw new IllegalStateException("Retained reviewed source unavailable",unavailable);
        } finally {
            if(original!=null)try {Files.deleteIfExists(original);}
                catch(java.io.IOException cleanup){throw new IllegalStateException("Reviewed source temporary cleanup failed",cleanup);}
        }
    }
}
