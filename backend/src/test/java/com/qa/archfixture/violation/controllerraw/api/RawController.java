package com.qa.archfixture.violation.controllerraw.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 违规 fixture：端点直接返回 String，而不是 ApiResponse（R8 必须拦住）。 */
@RestController
public class RawController {

    @GetMapping("/fixture/raw")
    public String raw() {
        return "raw";
    }
}
