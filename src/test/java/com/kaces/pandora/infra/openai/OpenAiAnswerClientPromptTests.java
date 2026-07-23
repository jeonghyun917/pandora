package com.kaces.pandora.infra.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.semantic.config.LawAiProperties;
import java.lang.reflect.Method;
import java.util.List;
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

	@Test
	void repairPromptContainsOnlyTheQuestionAndNumberedSupportedAtomsAsUserInput() throws Exception {
		OpenAiAnswerClient client = new OpenAiAnswerClient(
			new LawAiProperties(null, null, null, null),
			new ObjectMapper()
		);

		String instructions = invoke(client, "repairInstructions");
		String userInput = invokeRepairUserInput(
			client,
			"누가 연차 유급휴가를 받아야 하나?",
			List.of(
				"1년간 80퍼센트 이상 출근한 근로자에게 15일의 유급휴가를 주어야 한다.",
				"계속 근로기간이 1년 미만인 근로자에게는 1개월 개근 시 1일의 유급휴가를 주어야 한다."
			)
		);

		assertThat(instructions)
			.contains("첫 문장에 질문에 대한 직접적인 한국어 결론")
			.contains("지원 근거 원자에 명시된")
			.contains("인용", "문서 번호", "추측", "법률 자문", "이전 초안", "외부 지식")
			.contains("짧고 원자적인 문장");
		assertThat(userInput)
			.contains("질문:\n누가 연차 유급휴가를 받아야 하나?")
			.contains("지원 근거:\n1. 1년간 80퍼센트 이상 출근한 근로자에게 15일의 유급휴가를 주어야 한다.")
			.contains("2. 계속 근로기간이 1년 미만인 근로자에게는 1개월 개근 시 1일의 유급휴가를 주어야 한다.")
			.doesNotContain("거부된 답변", "초안", "문서 제목", "상위 문맥");
	}

	private String invoke(OpenAiAnswerClient client, String methodName, String... arguments) throws Exception {
		Class<?>[] parameterTypes = java.util.Arrays.stream(arguments)
			.map(ignored -> String.class)
			.toArray(Class<?>[]::new);
		Method method = OpenAiAnswerClient.class.getDeclaredMethod(methodName, parameterTypes);
		method.setAccessible(true);
		return (String) method.invoke(client, (Object[]) arguments);
	}

	private String invokeRepairUserInput(
		OpenAiAnswerClient client,
		String question,
		List<String> atoms
	) throws Exception {
		Method method = OpenAiAnswerClient.class.getDeclaredMethod(
			"repairUserInput",
			String.class,
			List.class
		);
		method.setAccessible(true);
		return (String) method.invoke(client, question, atoms);
	}
}
