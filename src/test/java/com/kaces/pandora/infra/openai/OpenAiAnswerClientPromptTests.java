package com.kaces.pandora.infra.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.semantic.config.LawAiProperties;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiAnswerClientPromptTests {

	@Test
	void asksForOneIndependentlyVerifiableClaimPerSentenceOrBullet() throws Exception {
		OpenAiAnswerClient client = new OpenAiAnswerClient(
			new LawAiProperties(null, null, null, null),
			new ObjectMapper()
		);

		String instructions = invoke(client, "instructions");
		String userInput = invoke(client, "userInput", "질문", "근거");

		assertThat(instructions)
			.contains("Keep each independently verifiable claim in its own sentence or bullet");
		assertThat(userInput)
			.contains("서로 다른 권리, 의무, 예외, 절차는 각각 별도 문장이나 불릿으로 나누세요")
			.doesNotContain("함께 묶어 설명하세요");
	}

	private String invoke(OpenAiAnswerClient client, String methodName, String... arguments) throws Exception {
		Class<?>[] parameterTypes = java.util.Arrays.stream(arguments)
			.map(ignored -> String.class)
			.toArray(Class<?>[]::new);
		Method method = OpenAiAnswerClient.class.getDeclaredMethod(methodName, parameterTypes);
		method.setAccessible(true);
		return (String) method.invoke(client, (Object[]) arguments);
	}
}
