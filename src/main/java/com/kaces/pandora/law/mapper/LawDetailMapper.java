package com.kaces.pandora.law.mapper;

import com.kaces.pandora.law.sync.StoredDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawDetailMapper {
	LawDetailRow findDetail(@Param("documentId") long documentId);
	void upsertDetail(@Param("detail") StoredDetail detail);
	long findDetailId(@Param("documentId") long documentId);
}
