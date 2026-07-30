package com.kaces.pandora.ai.answer;

import java.util.List;

public abstract class GroundedAnswerRewriter {

	public abstract String rewrite(String question, List<String> supportedEvidenceAtoms);
}
