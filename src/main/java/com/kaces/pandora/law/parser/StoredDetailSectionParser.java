package com.kaces.pandora.law.parser;

import java.util.List;
import com.kaces.pandora.law.detail.LawDetailSectionResponse;
import tools.jackson.databind.JsonNode;

/**
 * ??λ맂 ?곸꽭 ?먮낯 JSON?먯꽌 ?붾㈃ ?쒖떆??議곕Ц ?뱀뀡??異붿텧?섎뒗 ?꾨왂?낅땲??
 */
public interface StoredDetailSectionParser {

	/**
	 * ??parser媛 二쇱뼱吏??곸꽭 ?먮낯 援ъ“瑜?泥섎━?????덈뒗吏 ?먮떒?⑸땲??
	 */
	boolean supports(JsonNode root);

	/**
	 * ?곸꽭 ?먮낯 援ъ“瑜??붾㈃ ?쒖떆???뱀뀡 紐⑸줉?쇰줈 蹂?섑빀?덈떎.
	 */
	List<LawDetailSectionResponse> parse(JsonNode root);
}
