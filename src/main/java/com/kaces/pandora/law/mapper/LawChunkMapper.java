package com.kaces.pandora.law.mapper;

import com.kaces.pandora.law.sync.StoredChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawChunkMapper {

	/**
	 * ?뱀젙 臾몄꽌??湲곗〈 寃??泥?겕瑜?紐⑤몢 ?쒓굅?⑸땲??
	 */
	void deleteChunks(@Param("documentId") long documentId);

	/**
	 * 寃???됱씤???ъ슜??蹂몃Ц 泥?겕瑜???ν빀?덈떎.
	 */
	void insertChunk(@Param("chunk") StoredChunk chunk);
}
