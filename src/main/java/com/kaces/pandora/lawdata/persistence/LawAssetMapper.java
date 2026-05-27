package com.kaces.pandora.lawdata.persistence;

import com.kaces.pandora.lawdata.sync.StoredAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawAssetMapper {
	
	void deleteAssets(@Param("documentId") long documentId);
	
	void insertAsset(@Param("asset") StoredAsset asset);
}
