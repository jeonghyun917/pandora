package com.kaces.pandora.law.mapper;

import com.kaces.pandora.law.sync.StoredAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawAssetMapper {
	void deleteAssets(@Param("documentId") long documentId);
	void insertAsset(@Param("asset") StoredAsset asset);
}
