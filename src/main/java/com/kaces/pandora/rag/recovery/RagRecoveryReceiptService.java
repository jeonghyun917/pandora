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
    private final TransactionTemplate transaction;
    private final String model;
    private final int dimensions;
    private final ObjectMapper json = new ObjectMapper();

    public RagRecoveryReceiptService(RagDocumentMapper documents, RagRecoveryMapper recovery,
            PlatformTransactionManager manager, LawAiProperties properties) {
        this.documents = documents; this.recovery = recovery;
        model = properties.openai().embeddingModel(); dimensions = properties.qdrant().vectorSize();
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void save(String generationId, Map<Long, List<Double>> vectors) {
        var snapshot = new TreeMap<Long, List<Double>>();
        if (vectors == null || vectors.isEmpty() || vectors.size() > 10000) throw new IllegalArgumentException("Bounded embeddings required");
        vectors.forEach((id, vector) -> {
            if (id == null || id <= 0) throw new IllegalArgumentException("Candidate identity required");
            snapshot.put(id, checkedVector(vector));
        });
        locked(generationId, context -> {
            if (!"VALIDATED".equals(context.generation().state())) throw new IllegalStateException("Generation is not preparable");
            if (!snapshot.keySet().equals(context.chunks().stream().map(RagDocumentChunkRow::chunkId).collect(java.util.stream.Collectors.toSet()))) {
                throw new IllegalArgumentException("Embedding candidate identities differ");
            }
            var existing = recovery.findEmbeddingReceipts(generationId);
            var expected = context.chunks().stream().map(chunk -> receipt(context.generation(), chunk, snapshot.get(chunk.chunkId()))).toList();
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
        return locked(generationId, context -> {
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
                    if (!receipt(context.generation(), chunk, vector).equals(row)) throw new IllegalStateException("Embedding receipt changed");
                    vectors.put(chunk.chunkId(), vector);
                } catch (RuntimeException invalid) { throw new IllegalStateException("Prepared embedding integrity failure", invalid); }
            }
            return Collections.unmodifiableMap(vectors);
        });
    }

    private <T> T locked(String id, Function<Context, T> action) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException("Generation UUID required");
        return transaction.execute(status -> {
            var initial = recovery.findGeneration(id, false);
            if (initial == null) throw new IllegalStateException("Generation unavailable");
            var document = documents.findSourceProofDocument(initial.documentId(), true);
            var generation = recovery.findGeneration(id, true);
            if (document == null || generation == null || generation.documentId() != initial.documentId()
                    || !generation.sourceHash().equals(document.fileHash())
                    || !Set.of("VALIDATED", "INDEX_READY").contains(generation.state())) throw new IllegalStateException("Generation is not prepared");
            var chunks = recovery.findCandidateChunks(id);
            if (chunks.isEmpty() || chunks.size() > 10000 || !RecoveryCandidatePlan.manifestFor(generation.documentId(), generation.sourceHash(),
                    generation.productSha(), chunks.stream().map(RagRecoverySourceValidator::unassigned).toList()).equals(generation.manifest())) {
                throw new IllegalStateException("Candidate manifest changed");
            }
            return action.apply(new Context(generation, chunks));
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

    private RecoveryEmbeddingReceipt receipt(RecoveryGeneration generation, RagDocumentChunkRow chunk, List<Double> vector) {
        String value = json.writeValueAsString(vector);
        String hash = LawHashUtils.sha256(json.writeValueAsString(List.of(generation.id(), generation.sourceHash(), generation.productSha(),
                generation.manifest(), chunk, model, vector)));
        return new RecoveryEmbeddingReceipt(generation.id(), chunk.chunkId(), generation.manifest(), model, value, hash);
    }
    private record Context(RecoveryGeneration generation, List<RagDocumentChunkRow> chunks) { }
}
