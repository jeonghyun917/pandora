package com.kaces.pandora.lawdata.detail;

import org.springframework.stereotype.Service;

@Service
public class LawDetailService {

	private final LawDetailQueryService queryService;
	private final LawDetailResponseAssembler responseAssembler;

	
	// 메소드 설명: LawDetailService 처리 흐름을 수행합니다.
	public LawDetailService(LawDetailQueryService queryService, LawDetailResponseAssembler responseAssembler) {
		this.queryService = queryService;
		this.responseAssembler = responseAssembler;
	}

	
	// 메소드 설명: detail 처리 흐름을 수행합니다.
	public LawDetailResponse detail(String link) {
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return responseAssembler.assemble(queryService.findDetail(link));
	}
}
