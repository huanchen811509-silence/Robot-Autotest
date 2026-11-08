package com.qa.autotest.api.iam;

import com.qa.autotest.application.iam.ProjectCardService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.iam.ProjectCard;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目卡牌聚合（PRD v2.3 FR-UI-09）。
 *
 * <p><b>单独一个控制器</b>而不是挂进 {@code ProjectController}：那里有
 * {@code @GetMapping("/{projectId}")}（路径变量是 {@code long}），
 * {@code /api/projects/cards} 一旦被它先匹配上就会因为类型转换失败变成 400，
 * 分开映射可以彻底避开这条歧义。
 *
 * <p>只读，没有写方法：卡牌是聚合视图，不产生状态。
 */
@RestController
@RequestMapping("/api/projects/cards")
public class ProjectCardController {

    private final ProjectCardService cards;

    public ProjectCardController(ProjectCardService cards) {
        this.cards = cards;
    }

    /**
     * @param window  统计窗口，{@code 7d}（默认）/ {@code 30d}；非法值返回 400 INVALID_ARGUMENT
     * @param current 前端当前项目 id（localStorage 状态由调用方回传），用于回填 {@code isCurrent}
     */
    @GetMapping
    public ApiResponse<List<ProjectCard>> list(@RequestParam(required = false) String window,
                                               @RequestParam(required = false) Long current) {
        return ApiResponse.ok(cards.cards(window, current));
    }
}
