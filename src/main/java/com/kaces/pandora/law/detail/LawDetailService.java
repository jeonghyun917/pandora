package com.kaces.pandora.law.detail;

import org.springframework.stereotype.Service;

@Service
public class LawDetailService {

	private final LawDetailQueryService queryService;
	private final LawDetailResponseAssembler responseAssembler;

	public LawDetailService(LawDetailQueryService queryService, LawDetailResponseAssembler responseAssembler) {
		this.queryService = queryService;
		this.responseAssembler = responseAssembler;
	}

	public LawDetailResponse detail(String link) {
		return responseAssembler.assemble(queryService.findDetail(link));
	}
}
