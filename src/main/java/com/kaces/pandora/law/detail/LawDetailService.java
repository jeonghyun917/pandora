package com.kaces.pandora.law.detail;

import com.kaces.pandora.law.common.LawDatabaseQueryService;
import org.springframework.stereotype.Service;

@Service
public class LawDetailService {

	private final LawDatabaseQueryService lawDatabaseQueryService;

	/**
	 * 상세 서비스가 사용할 DB 조회 코어를 주입받습니다.
	 */
	public LawDetailService(LawDatabaseQueryService lawDatabaseQueryService) {
		this.lawDatabaseQueryService = lawDatabaseQueryService;
	}

	/**
	 * 상세 링크를 DB 조회 코어에 전달하고 상세 화면 JSON을 반환합니다.
	 */
	public String detail(String link) {
		// 저장된 상세 원문, 메타, 파싱 섹션 조립은 공통 DB 조회 코어에서 수행합니다.
		return lawDatabaseQueryService.detail(link);
	}
}
