package com.kaces.pandora.semantic.integrity;

import com.kaces.pandora.ai.answer.LawAiAnswerService;
import org.springframework.stereotype.Component;

@Component
public class LawAiRuntimeInfoProvider implements LawIndexIntegrityRuntimeInfoProvider {
	private final LawAiAnswerService answerService;

	public LawAiRuntimeInfoProvider(LawAiAnswerService answerService) {
		this.answerService = answerService;
	}

	@Override
	public LawIndexIntegrityRuntimeInfo current() {
		var runtimeInfo = answerService.runtimeInfo();
		return new LawIndexIntegrityRuntimeInfo(runtimeInfo.runtimeInstanceId(), runtimeInfo.indexRevision());
	}
}
