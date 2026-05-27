package com.kaces.pandora.app;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

	@GetMapping("/")
	// 메소드 설명: index 처리 흐름을 수행합니다.
	public String index() {
		return "forward:/index.html";
	}
}
