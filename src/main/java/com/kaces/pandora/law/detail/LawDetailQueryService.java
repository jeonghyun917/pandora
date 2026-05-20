package com.kaces.pandora.law.detail;

import com.kaces.pandora.law.mapper.LawDetailMapper;
import com.kaces.pandora.law.mapper.LawDetailRow;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LawDetailQueryService {

	private final LawDetailMapper lawDetailMapper;

	public LawDetailQueryService(LawDetailMapper lawDetailMapper) {
		this.lawDetailMapper = lawDetailMapper;
	}

	public LawDetailRow findDetail(String link) {
		long documentId = parseDocumentId(link);
		LawDetailRow detail = lawDetailMapper.findDetail(documentId);
		if (detail == null) {
			throw new IllegalArgumentException("Detail document was not found.");
		}
		return detail;
	}

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
