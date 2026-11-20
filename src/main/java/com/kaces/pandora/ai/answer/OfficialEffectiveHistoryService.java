package com.kaces.pandora.ai.answer;
import java.time.Clock;
import java.util.Optional;
import java.net.URI;
import java.util.function.Function;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawCommencementSourceRow;

@org.springframework.stereotype.Component
class OfficialEffectiveHistoryService {
    private final Clock clock;
    private final Function<URI,String> reader;
    public OfficialEffectiveHistoryService() {
        this(Clock.system(java.time.ZoneId.of("Asia/Seoul")),OfficialEffectiveHistoryService::readOfficialHistory);
    }
    OfficialEffectiveHistoryService(Clock clock,Function<URI,String> reader) {
        this.clock=java.util.Objects.requireNonNull(clock);
        this.reader=java.util.Objects.requireNonNull(reader);
    }
    Optional<OfficialEffectivePhaseEvidence> resolve(String question,LawSemanticChunkRow anchor,LawCommencementSourceRow source) {
        var request=ReferenceCommencementRequest.parseQuestion(question);
        if(request.isEmpty() || anchor==null || source==null || request.get().referenceDate().isAfter(java.time.LocalDate.now(clock))
            || !request.get().matchesTitle(com.kaces.pandora.common.text.KoreanQueryNormalizer.normalizeForMatch(anchor.title()))
            || OfficialCommencementEvidence.resolve(anchor,source).or(()->OfficialCommencementEvidence.resolveFromDetail(anchor,source)).isEmpty())
            return Optional.empty();
        try {
            var raw=new tools.jackson.databind.ObjectMapper().readTree(source.rawJson());
            String lawId=raw.path("법령").path("기본정보").path("법령ID").asString("");
            URI uri=OfficialEffectivePhaseEvidence.historyUri(lawId,source.externalId());
            String html=reader.apply(uri);
            return OfficialEffectivePhaseEvidence.resolve(anchor,source,uri,html,request.get().referenceDate(),java.time.LocalDate.now(clock));
        } catch(RuntimeException unavailable) { return Optional.empty(); }
    }
    private static String readOfficialHistory(URI uri) {
        javax.net.ssl.HttpsURLConnection connection=null;
        try {
            // URI is constructed from numeric official identities only. No arbitrary URL, credentials or redirects.
            connection=(javax.net.ssl.HttpsURLConnection)uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(3000);
            connection.setRequestProperty("User-Agent","Pandora/official-effective-history");
            connection.setRequestProperty("Accept","text/html");
            if(connection.getResponseCode()!=200 || connection.getContentType()==null
                || !connection.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("text/html")) return null;
            try(var input=connection.getInputStream()) {
                byte[] bytes=input.readNBytes(200001);
                return bytes.length>200000 ? null : new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch(java.io.IOException unavailable) { return null; }
        finally { if(connection!=null) connection.disconnect(); }
    }
}
