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

	@Test
	void parseDetailDocumentPreservesAdministrativeRuleArticleArrayBoundaries() {
		String detailJson = """
			{
			  "AdmRulService": {
			    "\uC870\uBB38\uB0B4\uC6A9": [
			      "\uC81C1\uC7A5 \uCD1D\uCE59",
			      "\uC81C1\uC870(\uBAA9\uC801) \uC774 \uC608\uADDC\uB294 \uC6A9\uC5ED\uACC4\uC57D\uC758 \uC774\uD589\uC870\uAC74\uC744 \uC815\uD568\uC744 \uBAA9\uC801\uC73C\uB85C \uD55C\uB2E4. \uC774 \uC870\uBB38\uC5D0\uC11C \uC81C27\uC870\uB97C \uCC38\uC870\uD558\uB294 \uBB38\uC7A5\uC740 \uC0C8 \uC870\uBB38\uC758 \uC2DC\uC791\uC774 \uC544\uB2C8\uB2E4.",
			      "\uC81C20\uC870(\uAC80\uC0AC) \uACC4\uC57D\uC0C1\uB300\uC790\uAC00 \uC6A9\uC5ED\uC744 \uC644\uB8CC\uD558\uBA74 \uACC4\uC57D\uB2F4\uB2F9\uACF5\uBB34\uC6D0\uC740 \uC644\uB8CC\uB41C \uC6A9\uC5ED\uC744 \uAC80\uC0AC\uD558\uC5EC\uC57C \uD558\uBA70 \uACC4\uC57D\uBB38\uC11C\uC5D0\uC11C \uC815\uD55C \uC694\uAC74\uC744 \uD655\uC778\uD55C\uB2E4.",
			      "\uC81C27\uC870(\uB300\uAC00\uC758 \uC9C0\uAE09) \uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uC6A9\uC5ED\uC744 \uC644\uB8CC\uD558\uACE0 \uAC80\uC0AC\uC5D0 \uD569\uACA9\uD55C \uD6C4 \uB300\uAC00\uC9C0\uAE09\uC744 \uCCAD\uAD6C\uD560 \uC218 \uC788\uC73C\uBA70 \uC808\uCC28\uB294 \uACC4\uC57D\uBB38\uC11C\uC5D0 \uB530\uB978\uB2E4."
			    ]
			  }
			}
			""";

		SyncDetailDocument document = parser.parseDetailDocument(detailJson, "test");

		assertThat(document.sections()).hasSize(3);
		assertThat(document.sections()).extracting(SyncDetailSection::type)
			.containsExactly("admin-rule-article", "admin-rule-article", "admin-rule-article");
		assertThat(document.sections()).extracting(SyncDetailSection::no)
			.containsExactly("\uC81C1\uC870", "\uC81C20\uC870", "\uC81C27\uC870");
		assertThat(document.sections()).extracting(SyncDetailSection::title)
			.containsExactly(
				"\uC81C1\uC870(\uBAA9\uC801)",
				"\uC81C20\uC870(\uAC80\uC0AC)",
				"\uC81C27\uC870(\uB300\uAC00\uC758 \uC9C0\uAE09)"
			);
		assertThat(document.sections()).extracting(SyncDetailSection::sourcePath)
			.containsExactly(
				"$.AdmRulService.\uC870\uBB38\uB0B4\uC6A9[1]",
				"$.AdmRulService.\uC870\uBB38\uB0B4\uC6A9[2]",
				"$.AdmRulService.\uC870\uBB38\uB0B4\uC6A9[3]"
			);
		assertThat(document.sections().get(0).body())
			.contains("\uC81C27\uC870\uB97C \uCC38\uC870")
			.doesNotContain("\uC81C20\uC870(\uAC80\uC0AC)");
	}

	@Test
	void parseDetailDocumentDoesNotTreatLeadingArticleCitationAsANewAdministrativeRuleArticle() {
		String detailJson = """
			{
			  "AdmRulService": {
			    "\uC870\uBB38\uB0B4\uC6A9": [
			      "\uC81C1\uC870(\uBAA9\uC801) \uC774 \uC608\uADDC\uB294 \uC6A9\uC5ED\uACC4\uC57D\uC758 \uC808\uCC28\uB97C \uC815\uD55C\uB2E4.",
			      "\uC81C1\uC870\uC5D0 \uB530\uB77C \uCC98\uB9AC\uD558\uB294 \uBCF4\uCDA9 \uC124\uBA85\uC73C\uB85C, \uC774 \uBB38\uC7A5\uC740 \uC0C8\uB85C\uC6B4 \uC870\uBB38 \uC81C\uBAA9\uC774 \uC544\uB2C8\uB77C \uAE30\uC874 \uC870\uBB38\uC744 \uC778\uC6A9\uD558\uB294 \uC77C\uBC18 \uBCF8\uBB38\uC774\uB2E4. \uC778\uC6A9\uBB38\uC73C\uB85C \uC2DC\uC791\uD574\uB3C4 \uB0B4\uC6A9 \uC190\uC2E4 \uC5C6\uC774 \uC77C\uBC18 \uD14D\uC2A4\uD2B8\uB85C \uBCF4\uC874\uB418\uC5B4\uC57C \uD55C\uB2E4."
			    ]
			  }
			}
			""";

		SyncDetailDocument document = parser.parseDetailDocument(detailJson, "test");

		assertThat(document.sections()).hasSize(2);
		assertThat(document.sections().get(0).type()).isEqualTo("admin-rule-article");
		assertThat(document.sections().get(0).no()).isEqualTo("\uC81C1\uC870");
		assertThat(document.sections().get(1).type()).isEqualTo("text");
		assertThat(document.sections().get(1).no()).isNull();
	}
}
