import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.*;
import org.springframework.core.env.*;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import tools.jackson.databind.ObjectMapper;

/** Read-only local source audit. No embeddings, answer generation, activation or writes. */
class VersionedSourceAuditProbe {
    private static void require(boolean value) {if(!value)throw new IllegalArgumentException("Source audit precondition failed");}
    public static void main(String[] args) {
        try {run();}
        catch(Exception unavailable) {System.err.println("Source audit unavailable: "+unavailable.getClass().getSimpleName());System.exit(1);}
    }
    private static void run() throws Exception {
        var mapper=new ObjectMapper();byte[] bytes=System.in.readNBytes(65_537);require(bytes.length<=65_536);
        var request=mapper.readTree(bytes);var contracts=request.path("sourceContracts");var runtime=request.path("runtimeInfo");
        String hash=request.path("evaluationContractSha256").asString("");require(hash.matches("[a-f0-9]{64}"));
        var required=List.of(contracts.path("qualified-approval").path("qualification").path("owner"),
            contracts.path("qualified-approval").path("remedy").path("owner"),contracts.path("reference-commencement").path("owner"));
        var expected=new LinkedHashMap<Long,tools.jackson.databind.JsonNode>();
        for(var owner:required)require(owner.path("chunkId").asLong(0)>0 && expected.put(owner.path("chunkId").asLong(),owner)==null);
        var properties=new Properties();
        try(var input=Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) {properties.load(input);}
        var environment=new StandardEnvironment();environment.getPropertySources().addLast(new PropertiesPropertySource("auditApp",properties));
        String url=environment.getRequiredProperty("spring.datasource.url");require(url.startsWith("jdbc:mariadb:") && !url.contains("${"));
        try(var db=DriverManager.getConnection(url,environment.getRequiredProperty("spring.datasource.username"),
            environment.getRequiredProperty("spring.datasource.password"))) {
            db.setReadOnly(true);
            try(var tx=db.createStatement()) {
                tx.setQueryTimeout(6);tx.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
                try {
                    var cfg=new Configuration();
                    for(String name:List.of("RagDocumentMapper","RagRecoveryMapper","SemanticLexicalMapper","LawChunkMapper")) {
                        var path=Path.of("src/main/resources/mapper/law/"+name+".xml");
                        try(var in=Files.newInputStream(path)){new XMLMapperBuilder(in,cfg,path.toString(),cfg.getSqlFragments()).parse();}
                    }
                    try(var session=new SqlSessionFactoryBuilder().build(cfg).openSession(db)) {
                        var found=new HashMap<Long,Map<String,Object>>();
                        try(var query=db.prepareStatement("""
                            SELECT c.chunk_id,c.document_id,d.target,d.external_id,c.chunk_version,c.chunk_text,
                              d.effective_status,c.activation_status,c.use_yn,d.use_yn,c.quality_status
                            FROM law_api_document_chunks c JOIN law_api_documents d ON d.document_id=c.document_id
                            WHERE c.chunk_id IN (?,?,?) AND d.use_yn='Y' AND c.use_yn='Y'
                              AND c.activation_status='ACTIVE' AND d.effective_status='CURRENT'
                            """)) {
                            query.setQueryTimeout(6);int index=1;for(long id:expected.keySet())query.setLong(index++,id);
                            try(var rows=query.executeQuery()) {
                                while(rows.next()) {
                                    long id=rows.getLong(1);var owner=expected.get(id);String text=rows.getString(6);
                                    require(owner!=null && rows.getLong(2)==owner.path("documentId").asLong()
                                        && rows.getString(3).equals(owner.path("target").asString()) && rows.getInt(5)>0
                                        && text!=null && !text.isBlank() && "PASS".equals(rows.getString(11)));
                                    String bodyHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
                                    found.put(id,Map.of("target",rows.getString(3),"documentId",rows.getLong(2),"chunkId",id,
                                        "edition",rows.getString(4),"chunkVersion",rows.getInt(5),"chunkTextSha256",bodyHash,
                                        "effectiveStatus",rows.getString(7),"activationStatus",rows.getString(8),"useYn",rows.getString(9),"qualityStatus",rows.getString(11)));
                                }
                            }
                        }
                        require(found.size()==3);
                        var law=session.getMapper(LawChunkMapper.class).findCurrentIndexedSnapshot(runtime.path("embeddingModel").asString(),runtime.path("lawCollection").asString());
                        var rag=session.getMapper(RagDocumentMapper.class).findCurrentIndexedSnapshot(runtime.path("embeddingModel").asString(),runtime.path("ragCollection").asString());
                        var result=new LinkedHashMap<String,Object>();result.put("schemaVersion",1);result.put("evaluationContractSha256",hash);
                        result.put("auditMode","read-only-db-consistent-snapshot");result.put("capturedAt",java.time.Instant.now().toString());
                        result.put("sourceOwners",expected.keySet().stream().map(found::get).toList());
                        result.put("lawDatabaseIndexedCount",law.currentIndexedCount());result.put("ragDatabaseIndexedCount",rag.currentIndexedCount());
                        result.put("lawDatabaseContentFingerprint",law.contentFingerprint());result.put("ragDatabaseContentFingerprint",rag.contentFingerprint());
                        System.out.println(mapper.writeValueAsString(result));
                    }
                } finally {if(!db.isClosed())tx.execute("ROLLBACK");}
            }
        }
    }
}
