package com.kaces.pandora.law.mapper;

import com.kaces.pandora.law.sync.StoredDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LawDetailMapper {

	/**
	 * ?곸꽭 ?붾㈃ 援ъ꽦???꾩슂???곸꽭 ?먮Ц怨?臾몄꽌 ?뺣낫瑜?議고쉶?⑸땲??
	 */
	LawDetailRow findDetail(@Param("documentId") long documentId);

	/**
	 * ?곸꽭 ?먮Ц怨??뚯떛???뱀뀡 硫뷀??곗씠?곕? ?덈줈 ?ｊ굅??媛깆떊?⑸땲??
	 */
	void upsertDetail(@Param("detail") StoredDetail detail);

	/**
	 * 臾몄꽌 ID濡??곸꽭 ?뚯씠釉붿쓽 ?대? ?곸꽭 ID瑜?李얠뒿?덈떎.
	 */
	long findDetailId(@Param("documentId") long documentId);
}
