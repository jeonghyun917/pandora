package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.QuestionIntentProfile;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class QuestionPropositionTemplateFactory {
	private static final String OPEN_DUTY_END =
		"[ \\t]+(?:무엇을|뭘)[ \\t]+(?:해야|하여야)[ \\t]*(?:하나요|합니까|하는가|하죠|해요|해|하나)\\??$";
	private static final String DUTY_TOPIC = "([가-힣A-Za-z]{2,25}(?:[ \\t]+[가-힣A-Za-z]{2,25}){1,5}?)";
	private static final Pattern TOPIC_DUTY = Pattern.compile("^" + DUTY_TOPIC + "(?:은|는|(?:을|를)[ \\t]+위해)" + OPEN_DUTY_END);
	private static final Pattern ACTOR_TOPIC_DUTY = Pattern.compile(
		"^([가-힣]{2,24})(?:은|는)[ \\t]+" + DUTY_TOPIC + "에[ \\t]+관해" + OPEN_DUTY_END);

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
		if (atom.modality() == EvidenceAtom.Modality.REQUIRED && atom.conditions().isEmpty()
			&& atom.exceptions().isEmpty() && atom.targetScopes().isEmpty()
			&& atom.numericAnchors().isEmpty() && atom.relations().isEmpty()) {
			var scoped = ACTOR_TOPIC_DUTY.matcher(question.strip());
			if (scoped.matches() && atom.subjects().equals(Set.of(scoped.group(1)))) {
				return openDuty(scoped.group(2), atom.subjects());
			}
			var topical = TOPIC_DUTY.matcher(question.strip());
			if (topical.matches()) {
				return openDuty(topical.group(1), Set.of());
			}
		}
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

	private PropositionTemplate openDuty(String topic, Set<String> subjects) {
		var slots = new LinkedHashSet<>(Set.of(PropositionTemplate.RequiredSlot.MODALITY));
		if (!subjects.isEmpty()) slots.add(PropositionTemplate.RequiredSlot.SUBJECT);
		return new PropositionTemplate(subjects, Set.of(), Set.of(), Set.of(), Set.of(), slots, topic);
	}
}
