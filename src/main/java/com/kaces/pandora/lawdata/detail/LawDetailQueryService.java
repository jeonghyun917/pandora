package com.kaces.pandora.lawdata.detail;

import com.kaces.pandora.lawdata.persistence.LawDetailMapper;
import com.kaces.pandora.lawdata.persistence.LawDetailRow;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LawDetailQueryService {

	private final LawDetailMapper lawDetailMapper;

	
	// 메소드 설명: LawDetailQueryService 처리 흐름을 수행합니다.
	public LawDetailQueryService(LawDetailMapper lawDetailMapper) {
		this.lawDetailMapper = lawDetailMapper;
	}

	
	// 메소드 설명: findDetail 처리 흐름을 수행합니다.
	public LawDetailRow findDetail(String link) {
		
		long documentId = parseDocumentId(link);
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		LawDetailRow detail = lawDetailMapper.findDetail(documentId);
		if (detail == null) {
			throw new IllegalArgumentException("Detail document was not found.");
		}
		return detail;
	}

	
	// 메소드 설명: parseDocumentId 처리 흐름을 수행합니다.
	private long parseDocumentId(String link) {
		if (!StringUtils.hasText(link) || !link.startsWith("db:")) {
			throw new IllegalArgumentException("DB detail link is required.");
		}
		try {
			return Long.parseLong(link.substring(3));
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Invalid DB detail link.", exception);
		}
	}
}
