package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.common.text.LawHashUtils;
import java.util.*;
import tools.jackson.databind.*;

/** Declaration provenance, not legal effect, source-body equivalence or harmlessness. */
public final class SourceReferenceDeclarations {
    public record Declaration(String sourcePath,String ownerPath,String kind,String sourceUrl,String canonicalUrl,String ownerHash) { }
    public List<Declaration> capture(String raw) {
        if(raw==null || raw.length()>4_000_000)throw rejected();
        try {
            var root=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(raw);
            if(!root.isObject() || root.has("AdmRulService")==root.has("법령"))throw rejected();
            String name=root.has("AdmRulService")?"AdmRulService":"법령";
            if(!root.get(name).isObject())throw rejected();
            var out=new ArrayList<Declaration>();walk(root.get(name),"/"+name,0,new int[1],out);
            return List.copyOf(out);
        } catch(RuntimeException invalid) {throw new IllegalStateException("Source reference declaration provenance unavailable",invalid);}
    }
    private void walk(JsonNode node,String path,int depth,int[] nodes,List<Declaration> out) {
        bound(path,depth,nodes);
        if(node==null || node.isNull())return;
        if(node.isArray()) {int i=0;for(var item:node)walk(item,path+"/"+(i++),depth+1,nodes,out);}
        else if(node.isObject()) {String ownerHash=null;for(var entry:node.properties()) {
            String key=entry.getKey();String child=path+"/"+key.replace("~","~0").replace("/","~1");
            boolean declared=key.endsWith("파일링크") || (key.endsWith("파일") && !(key.equals("첨부파일") && entry.getValue().isObject()));
            if(declared) {
                if(ownerHash==null)ownerHash=LawHashUtils.sha256Json(node);
                links(entry.getValue(),child,path,kind(path),ownerHash,depth+1,nodes,out);
            }
            else walk(entry.getValue(),child,depth+1,nodes,out);
        }}
    }
    private void links(JsonNode node,String path,String owner,String kind,String ownerHash,int depth,int[] nodes,List<Declaration> out) {
        bound(path,depth,nodes);if(node==null || node.isNull())return;
        if(node.isArray()) {int i=0;for(var item:node)links(item,path+"/"+(i++),owner,kind,ownerHash,depth+1,nodes,out);return;}
        if(!node.isTextual())throw rejected();String source=node.asText();if(source.isBlank())return;
        if(out.size()>=64)throw rejected();
        out.add(new Declaration(path,owner,kind,source,SourceReferenceInspector.officialUri(source).toString(),ownerHash));
    }
    private static String kind(String path) {
        if(path.matches("/(?:AdmRulService|법령)/별표/별표단위(?:/[0-9]+)?"))return "ANNEX_DECLARATION";
        if(path.matches("/(?:AdmRulService|법령)/첨부파일(?:/[0-9]+)?"))return "GENERAL_ATTACHMENT_DECLARATION";
        return "UNCLASSIFIED_DECLARATION";
    }
    private static void bound(String path,int depth,int[] nodes) {if(path.length()>4096 || depth>64 || ++nodes[0]>100000)throw rejected();}
    private static IllegalStateException rejected() {return new IllegalStateException("Unsupported or over-bound source reference declarations");}
}
