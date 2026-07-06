package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.common.json.LawJsonWriter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LawOpenApiPayloadParserTests {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final LawOpenApiPayloadParser parser = new LawOpenApiPayloadParser(objectMapper, new LawJsonWriter(objectMapper));

	@Test
	void parseDetailDocumentPreservesNestedSourcePathForFallbackTextSections() {
		String detailJson = """
			{
			  "법령": {
			    "조문": {
			      "조문단위": [
			        {
			          "항": {
			            "항단위": [
			              {
			                "항내용": "이 조항은 테스트를 위한 긴 문장입니다. JSON 경로가 마지막 필드명만 남으면 parent-child 청크가 서로 다른 조문을 같은 항내용으로 묶을 수 있습니다. 그러면 검색 근거가 섞이므로 전체 경로가 필요합니다."
			              }
			            ]
			          }
			        }
			      ]
			    }
			  }
			}
			""";

		SyncDetailDocument document = parser.parseDetailDocument(detailJson, "테스트 법령");

		assertThat(document.sections()).hasSize(1);
		SyncDetailSection section = document.sections().get(0);
		assertThat(section.title()).isEmpty();
		assertThat(section.sourcePath()).isEqualTo("$.법령.조문.조문단위[0].항.항단위[0].항내용");
		assertThat(section.lineNo()).isEqualTo(1);
	}

	@Test
	void parseDetailDocumentPropagatesArticleMetadataToParagraphsAndSubparagraphs() {
		String detailJson = """
			{
			  "법령": {
			    "조문": {
			      "조문단위": [
			        {
			          "조문번호": "51",
			          "조문가지번호": "3",
			          "조문내용": "제51조의3(연금보험료공제)",
			          "조문제목": "연금보험료공제",
			          "항": [
			            {
			              "항번호": "①",
			              "항내용": "① 종합소득이 있는 거주자는 연금보험료를 공제한다.",
			              "호": [
			                {
			                  "호번호": "1.",
			                  "호내용": "1. 공적연금 관련법에 따른 기여금"
			                }
			              ]
			            }
			          ]
			        }
			      ]
			    }
			  }
			}
			""";

		SyncDetailDocument document = parser.parseDetailDocument(detailJson, "테스트 법령");

		assertThat(document.sections()).hasSize(2);
		assertThat(document.sections()).allSatisfy(section -> {
			assertThat(section.no()).isEqualTo("제51조의3");
			assertThat(section.title()).isEqualTo("제51조의3(연금보험료공제)");
		});
		assertThat(document.sections().get(0).sourcePath()).isEqualTo("$.법령.조문.조문단위[0].항[0].항내용");
		assertThat(document.sections().get(1).sourcePath()).isEqualTo("$.법령.조문.조문단위[0].항[0].호[0].호내용");
	}

	@Test
	void parseDetailDocumentUsesHeadingOnlyTitleWhenArticleBodyContinues() {
		String detailJson = """
			{
			  "\\uBC95\\uB839": {
			    "\\uC870\\uBB38": {
			      "\\uC870\\uBB38\\uB2E8\\uC704": [
			        {
			          "\\uC870\\uBB38\\uBC88\\uD638": "30",
			          "\\uC870\\uBB38\\uAC00\\uC9C0\\uBC88\\uD638": "2",
			          "\\uC870\\uBB38\\uB0B4\\uC6A9": "\\uC81C30\\uC870\\uC7582(\\uD3D0\\uAE30\\uBB3C\\uCC98\\uB9AC\\uC5C5\\uC790\\uC758 \\uD3D0\\uAE30\\uBB3C \\uBCF4\\uAD00\\uC7A5\\uC18C) \\uD3D0\\uAE30\\uBB3C\\uCC98\\uB9AC\\uC5C5\\uC790\\uB294 \\uB2E4\\uC74C \\uAC01 \\uD638\\uC758 \\uC7A5\\uC18C\\uC5D0 \\uD3D0\\uAE30\\uBB3C\\uC744 \\uBCF4\\uAD00\\uD558\\uC5EC\\uC57C \\uD55C\\uB2E4.",
			          "\\uC870\\uBB38\\uC81C\\uBAA9": "\\uD3D0\\uAE30\\uBB3C\\uCC98\\uB9AC\\uC5C5\\uC790\\uC758 \\uD3D0\\uAE30\\uBB3C \\uBCF4\\uAD00\\uC7A5\\uC18C",
			          "\\uD56D": [
			            {
			              "\\uD56D\\uB0B4\\uC6A9": "1. \\uC784\\uC2DC\\uBCF4\\uAD00\\uC7A5\\uC18C"
			            }
			          ]
			        }
			      ]
			    }
			  }
			}
			""";

		SyncDetailDocument document = parser.parseDetailDocument(detailJson, "test");

		String no = "\uC81C30\uC870\uC7582";
		String title = "\uC81C30\uC870\uC7582(\uD3D0\uAE30\uBB3C\uCC98\uB9AC\uC5C5\uC790\uC758 \uD3D0\uAE30\uBB3C \uBCF4\uAD00\uC7A5\uC18C)";
		assertThat(document.sections()).hasSize(2);
		assertThat(document.sections()).allSatisfy(section -> {
			assertThat(section.no()).isEqualTo(no);
			assertThat(section.title()).isEqualTo(title);
		});
		assertThat(document.sections().get(0).body()).startsWith(title);
		assertThat(document.sections().get(1).body()).isEqualTo("1. \uC784\uC2DC\uBCF4\uAD00\uC7A5\uC18C");
	}

	@Test
	void parseDetailDocumentSkipsArticleEffectiveDateMetadataAsBodyText() {
		String detailJson = """
			{
			  "\\uBC95\\uB839": {
			    "\\uAE30\\uBCF8\\uC815\\uBCF4": {
			      "\\uC870\\uBB38\\uC2DC\\uD589\\uC77C\\uC790\\uBB38\\uC790\\uC5F4": "20260310:20270311:\\uC81C2\\uC870, \\uC81C10\\uC870, \\uC81C134\\uC870\\uC7586, \\uC81C134\\uC870\\uC7587, \\uC81C134\\uC870\\uC7588, \\uC81C142\\uC870\\uC81C2\\uD56D, \\uC81C161\\uC870\\uC7587"
			    },
			    "\\uC870\\uBB38": {
			      "\\uC870\\uBB38\\uB2E8\\uC704": [
			        {
			          "\\uC870\\uBB38\\uBC88\\uD638": "1",
			          "\\uC870\\uBB38\\uB0B4\\uC6A9": "\\uC81C1\\uC870(\\uBAA9\\uC801) \\uC774 \\uBC95\\uC740 \\uC870\\uD569\\uC758 \\uC6B4\\uC601\\uC5D0 \\uD544\\uC694\\uD55C \\uC0AC\\uD56D\\uC744 \\uC815\\uD55C\\uB2E4.",
			          "\\uC870\\uBB38\\uC81C\\uBAA9": "\\uBAA9\\uC801"
			        }
			      ]
			    }
			  }
			}
			""";

		SyncDetailDocument document = parser.parseDetailDocument(detailJson, "test");

		assertThat(document.sections()).hasSize(1);
		assertThat(document.sections().get(0).body()).doesNotContain("20260310:20270311");
		assertThat(document.sections().get(0).title()).isEqualTo("\uC81C1\uC870(\uBAA9\uC801)");
	}

	@Test
	void parseDetailDocumentSkipsRevisionTextAsCurrentLawBodyText() {
		String detailJson = """
			{
			  "\\uBC95\\uB839": {
			    "\\uAC1C\\uC815\\uBB38": {
			      "\\uAC1C\\uC815\\uBB38\\uB0B4\\uC6A9": "\\uC81C108\\uC870\\uC7583\\uC744 \\uB2E4\\uC74C\\uACFC \\uAC19\\uC774 \\uAC1C\\uC815\\uD55C\\uB2E4. \\uC774 \\uBB38\\uC7A5\\uC740 \\uD604\\uD589 \\uC870\\uBB38 \\uADFC\\uAC70\\uB85C \\uC0AC\\uC6A9\\uD558\\uBA74 \\uC548 \\uB41C\\uB2E4."
			    },
			    "\\uC81C\\uAC1C\\uC815\\uC774\\uC720": {
			      "\\uC81C\\uAC1C\\uC815\\uC774\\uC720\\uB0B4\\uC6A9": "\\uC81C\\uB3C4 \\uC6B4\\uC601\\uC0C1 \\uBBF8\\uBE44\\uC810\\uC744 \\uAC1C\\uC120\\uD558\\uB824\\uB294 \\uAC83\\uC784."
			    },
			    "\\uC870\\uBB38": {
			      "\\uC870\\uBB38\\uB2E8\\uC704": [
			        {
			          "\\uC870\\uBB38\\uBC88\\uD638": "1",
			          "\\uC870\\uBB38\\uB0B4\\uC6A9": "\\uC81C1\\uC870(\\uBAA9\\uC801) \\uC774 \\uBC95\\uC740 \\uD604\\uD589 \\uC870\\uBB38 \\uAC80\\uC0C9\\uC744 \\uC704\\uD55C \\uBCF8\\uBB38\\uC744 \\uB2F4\\uB294\\uB2E4.",
			          "\\uC870\\uBB38\\uC81C\\uBAA9": "\\uBAA9\\uC801"
			        }
			      ]
			    }
			  }
			}
			""";

		SyncDetailDocument document = parser.parseDetailDocument(detailJson, "test");

		assertThat(document.sections()).hasSize(1);
		assertThat(document.sections().get(0).body()).contains("\uD604\uD589 \uC870\uBB38 \uAC80\uC0C9");
		assertThat(document.sections().get(0).body()).doesNotContain("\uAC1C\uC815\uD55C\uB2E4");
		assertThat(document.sections().get(0).body()).doesNotContain("\uBBF8\uBE44\uC810");
	}
}
