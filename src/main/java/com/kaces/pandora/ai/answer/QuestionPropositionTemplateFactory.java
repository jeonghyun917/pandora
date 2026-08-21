package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.QuestionIntentProfile;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class QuestionPropositionTemplateFactory {

	private final KoreanEvidenceAtomParser parser;

	public QuestionPropositionTemplateFactory(KoreanEvidenceAtomParser parser) {
		this.parser = parser;
	}

	public PropositionTemplate from(String question, QuestionIntentProfile profile) {
		QuestionIntentProfile effective = profile == null ? QuestionIntentProfile.from(question) : profile;
		if (effective.documentDiscoveryQuestion()) {
			return PropositionTemplate.empty();
		}
		EvidenceAtom atom = parser.parse(question);
		Set<PropositionTemplate.RequiredSlot> required = new LinkedHashSet<>();
		if (!atom.subjects().isEmpty()) {
			required.add(PropositionTemplate.RequiredSlot.SUBJECT);
		}
		if (!atom.actions().isEmpty()) {
			required.add(PropositionTemplate.RequiredSlot.ACTION);
		}
		if (!atom.relations().isEmpty()) {
			required.add(PropositionTemplate.RequiredSlot.RELATION);
		}
		if (!atom.targetScopes().isEmpty()) {
			required.add(PropositionTemplate.RequiredSlot.TARGET_SCOPE);
		}
		if (!atom.conditions().isEmpty()) {
			required.add(PropositionTemplate.RequiredSlot.CONDITION);
		}
		if (atom.modality() != EvidenceAtom.Modality.UNSPECIFIED) {
			required.add(PropositionTemplate.RequiredSlot.MODALITY);
		}
		if (atom.polarity() != EvidenceAtom.Polarity.UNSPECIFIED) {
			required.add(PropositionTemplate.RequiredSlot.POLARITY);
		}
		if (!atom.numericAnchors().isEmpty()) {
			required.add(PropositionTemplate.RequiredSlot.NUMERIC_ANCHOR);
		}
		if (required.isEmpty() && !effective.intentTypes().isEmpty() && !atom.actions().isEmpty()) {
			required.add(PropositionTemplate.RequiredSlot.ACTION);
		}
		return new PropositionTemplate(
			atom.subjects(), atom.actions(), atom.relations(), atom.targetScopes(), atom.conditions(), required
		);
	}
}
