package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable immutable prepared embeddings. These receipts alone never authorize activation. */
@Service
public class RagRecoveryReceiptService {
    private final RagDocumentMapper documents;
    private final RagRecoveryMapper recovery;
    private final RagPdfValidatedSourceReader pdfReadback;
    private final TransactionTemplate transaction;
    private final String model;
    private final int dimensions;
    private final ObjectMapper json = new ObjectMapper();

    public RagRecoveryReceiptService(RagDocumentMapper documents, RagRecoveryMapper recovery,
            PlatformTransactionManager manager, LawAiProperties properties,RagPdfValidatedSourceReader pdfReadback) {
        this.documents = documents; this.recovery = recovery;
        this.pdfReadback=Objects.requireNonNull(pdfReadback);
        model = properties.openai().embeddingModel(); dimensions = properties.qdrant().vectorSize();
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void save(String generationId, Map<Long, List<Double>> vectors) {
        saveBound(generationId,vectors,null);
    }
    void saveReviewedPdf(String generationId,Map<Long,List<Double>> vectors,String expectedEvidence) {
        saveBound(generationId,vectors,reviewedEvidence(expectedEvidence));
    }
    private void saveBound(String generationId,Map<Long,List<Double>> vectors,String pdfEvidence) {
        var snapshot = new TreeMap<Long, List<Double>>();
        if (vectors == null || vectors.isEmpty() || vectors.size() > 10000) throw new IllegalArgumentException("Bounded embeddings required");
        vectors.forEach((id, vector) -> {
            if (id == null || id <= 0) throw new IllegalArgumentException("Candidate identity required");
            snapshot.put(id, checkedVector(vector));
        });
        locked(generationId,pdfEvidence,context -> {
            if (!"VALIDATED".equals(context.generation().state())) throw new IllegalStateException("Generation is not preparable");
            if (!snapshot.keySet().equals(context.chunks().stream().map(RagDocumentChunkRow::chunkId).collect(java.util.stream.Collectors.toSet()))) {
                throw new IllegalArgumentException("Embedding candidate identities differ");
            }
            var existing = recovery.findEmbeddingReceipts(generationId);
            var expected = context.chunks().stream().map(chunk -> receipt(context, chunk, snapshot.get(chunk.chunkId()))).toList();
            if (!existing.isEmpty()) {
                if (existing.size() != expected.size() || !new HashSet<>(existing).equals(new HashSet<>(expected))) {
                    throw new IllegalStateException("Prepared embeddings are immutable");
                }
            } else {
                for (var receipt : expected) if (recovery.insertEmbeddingReceipt(receipt) != 1) throw new IllegalStateException("Embedding receipt conflict");
            }
            return null;
        });
    }

    public Map<Long, List<Double>> load(String generationId) {
        return loadBound(generationId,null);
    }
    Map<Long,List<Double>> loadReviewedPdf(String generationId,String expectedEvidence) {
        return loadBound(generationId,reviewedEvidence(expectedEvidence));
    }
    private Map<Long,List<Double>> loadBound(String generationId,String pdfEvidence) {
        return locked(generationId,pdfEvidence,context -> {
            var stored = recovery.findEmbeddingReceipts(generationId);
            if (stored.size() != context.chunks().size()) throw new IllegalStateException("Prepared embeddings incomplete");
            var byId = new HashMap<Long, RecoveryEmbeddingReceipt>();
            for (var row : stored) if (byId.put(row.chunkId(), row) != null) throw new IllegalStateException("Duplicate embedding receipt");
            var vectors = new TreeMap<Long, List<Double>>();
            for (var chunk : context.chunks()) {
                var row = byId.get(chunk.chunkId());
                try {
                    if (row == null) throw new IllegalStateException("Embedding receipt missing");
                    var node = json.readTree(row.vectorJson());
                    if (!node.isArray()) throw new IllegalStateException("Malformed embedding receipt");
                    var values = new ArrayList<Double>();
                    for (var value : node) {
                        if (!value.isNumber()) throw new IllegalStateException("Malformed embedding component");
                        values.add(value.doubleValue());
                    }
                    var vector = checkedVector(values);
                    if (!receipt(context, chunk, vector).equals(row)) throw new IllegalStateException("Embedding receipt changed");
                    vectors.put(chunk.chunkId(), vector);
                } catch (RuntimeException invalid) { throw new IllegalStateException("Prepared embedding integrity failure", invalid); }
            }
            return Collections.unmodifiableMap(vectors);
        });
    }

    private <T> T locked(String id,String pdfEvidence, Function<Context, T> action) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException("Generation UUID required");
        return transaction.execute(status -> {
            var initial = recovery.findGeneration(id, false);
            if (initial == null) throw new IllegalStateException("Generation unavailable");
            var document = documents.findSourceProofDocument(initial.documentId(), true);
            var generation = recovery.findGeneration(id, true);
            if (document == null || generation == null || generation.documentId() != initial.documentId()
                    || !Set.of("VALIDATED", "INDEX_READY").contains(generation.state())) throw new IllegalStateException("Generation is not prepared");
            if(pdfEvidence==null && (!generation.sourceHash().equals(document.fileHash()) || Boolean.TRUE.equals(recovery.isReviewedPdfGeneration(id))))
                throw new IllegalStateException("Preserved-original generation required");
            var chunks = recovery.findCandidateChunks(id);
            if (chunks.isEmpty() || chunks.size() > 10000 || !RecoveryCandidatePlan.manifestFor(generation.documentId(), generation.sourceHash(),
                    generation.productSha(), chunks.stream().map(RagRecoverySourceValidator::unassigned).toList()).equals(generation.manifest())) {
                throw new IllegalStateException("Candidate manifest changed");
            }
            if(pdfEvidence!=null) {
                var proof=pdfReadback.find(generation,recovery.findPointer(generation.documentId(),true),chunks)
                        .orElseThrow(()->new IllegalStateException("Reviewed PDF proof unavailable"));
                var reservation=recovery.findPreparation(id);
                if(!pdfEvidence.equals(proof.evidenceHash()) || reservation==null || !generation.manifest().equals(reservation.manifest()))
                    throw new IllegalStateException("Reviewed PDF embedding preparation changed");
            }
            return action.apply(new Context(generation,chunks,pdfEvidence));
        });
    }

    private List<Double> checkedVector(List<Double> vector) {
        if (vector == null || vector.size() != dimensions || vector.stream().anyMatch(v -> v == null || !Double.isFinite(v) || !Float.isFinite(v.floatValue()))) {
            throw new IllegalArgumentException("Embedding dimension or component mismatch");
        }
        double norm = vector.stream().mapToDouble(v -> v * v).sum();
        if (norm <= 0 || !Float.isFinite((float) norm)) throw new IllegalArgumentException("Finite nonzero embedding required");
        return List.copyOf(vector);
    }

    private RecoveryEmbeddingReceipt receipt(Context context,RagDocumentChunkRow chunk,List<Double> vector) {
        var generation=context.generation();
        String value = json.writeValueAsString(vector);
        var identity=new ArrayList<Object>(List.of(generation.id(),generation.sourceHash(),generation.productSha(),generation.manifest(),chunk,model,vector));
        if(context.pdfEvidence()!=null)identity.add(context.pdfEvidence());
        String hash=LawHashUtils.sha256(json.writeValueAsString(identity));
        return new RecoveryEmbeddingReceipt(generation.id(), chunk.chunkId(), generation.manifest(), model, value, hash);
    }
    private static String reviewedEvidence(String value) {
        if(value==null || !value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Exact reviewed source evidence required");
        return value;
    }
    private record Context(RecoveryGeneration generation,List<RagDocumentChunkRow> chunks,String pdfEvidence) { }
}
