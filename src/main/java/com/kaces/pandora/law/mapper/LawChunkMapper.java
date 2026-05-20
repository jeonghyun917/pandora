package com.kaces.pandora.law.mapper;

import com.kaces.pandora.law.sync.StoredChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawChunkMapper {
	void deleteChunks(@Param("documentId") long documentId);
	void insertChunk(@Param("chunk") StoredChunk chunk);
}
