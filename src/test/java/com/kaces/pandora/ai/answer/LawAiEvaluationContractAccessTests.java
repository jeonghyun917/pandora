package com.kaces.pandora.ai.answer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LawAiEvaluationContractAccessTests {
	private static final String HEADER = "X-Pandora-Evaluation-Contract-SHA256";
	private static final String ROOT = "/api/law-data/ai/debug/evaluate";

	@Test
	void capabilitiesAreReadOnlyAndDoNotAdvertiseAnUnregisteredContract() throws Exception {
		var service=mock(LawAiAnswerService.class);
		var mvc=MockMvcBuilders.standaloneSetup(new LawAiAnswerController(service)).build();
		mvc.perform(get("/api/law-data/ai/debug/evaluation-contracts"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value(1))
			.andExpect(jsonPath("$.contractHeader").value(HEADER))
			.andExpect(jsonPath("$.registeredContracts").isEmpty());
		verifyNoInteractions(service);
	}

	@Test
	void explicitUnknownContractsNeverEnterLegacyEvaluation() throws Exception {
		var service = mock(LawAiAnswerService.class);
		when(service.evaluate(any())).thenReturn(new LawAiEvalResponse(0, 0, 0, 0, true, 0, List.of(), 0, 0, List.of()));
		var mvc = MockMvcBuilders.standaloneSetup(new LawAiAnswerController(service)).build();
		for (String endpoint : List.of(ROOT, ROOT + "/gate")) {
			mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{}")
				.header(HEADER, "a".repeat(64))).andExpect(status().is(422));
			mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{}")
				.header(HEADER, "draft")).andExpect(status().isBadRequest());
			mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{}")
				.header(HEADER, "a".repeat(64), "b".repeat(64))).andExpect(status().isBadRequest());
		}
		verifyNoInteractions(service);
	}

	@Test
	void legacyRequestsKeepExistingGateStatus() throws Exception {
		var service = mock(LawAiAnswerService.class);
		when(service.evaluate(any())).thenReturn(new LawAiEvalResponse(1, 0, 1, 0, false, 1, List.of("fixture"), 0, 0, List.of()));
		var mvc = MockMvcBuilders.standaloneSetup(new LawAiAnswerController(service)).build();
		mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
		mvc.perform(post(ROOT + "/gate").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
	}
}
