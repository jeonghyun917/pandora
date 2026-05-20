package com.kaces.pandora.law.mapper;

import com.kaces.pandora.law.sync.StoredAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawAssetMapper {

	/**
	 * ?뱀젙 臾몄꽌??湲곗〈 泥⑤?/?대?吏 ?먯궛??紐⑤몢 ?쒓굅?⑸땲??
	 */
	void deleteAssets(@Param("documentId") long documentId);

	/**
	 * ?곸꽭 ?먮Ц?먯꽌 諛쒓껄??泥⑤?/?대?吏 ?먯궛????ν빀?덈떎.
	 */
	void insertAsset(@Param("asset") StoredAsset asset);
}
