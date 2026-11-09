package com.qa.autotest.arch;

import com.qa.autotest.common.ApiResponse;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 分层依赖规则（基线报告 §12.2 R1~R5）。规则本身放在这里，供两个测试共用：
 * <ul>
 *   <li>{@link LayerRulesTest} —— 对真实代码跑，必须绿；</li>
 *   <li>{@link LayerRulesMetaTest} —— 对故意违规的 fixture 跑，必须红。</li>
 * </ul>
 *
 * <p>只有「正常代码能过」不算验证过分层规则，两半都成立才算（TEST-PLAN TC-v0.1-010~014）。
 */
public final class LayerRules {

    private LayerRules() {
    }

    /** R1：domain 不得依赖 api / application / infrastructure。 */
    public static final ArchRule R1_DOMAIN_NOT_DEPEND_ON_UPPER_LAYERS =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..api..", "..application..", "..infrastructure..")
                    .because("domain 是纯领域模型，上层变化不得反向污染它（R1）");

    /** R2：domain 不得依赖框架（Spring / JPA / Bean Validation / Jackson）。 */
    public static final ArchRule R2_DOMAIN_NOT_DEPEND_ON_FRAMEWORKS =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "jakarta.validation..",
                            "com.fasterxml.jackson..")
                    .because("domain 必须能脱离容器与数据库单测（R2）");

    /** R3：api 不得直接依赖 infrastructure。 */
    public static final ArchRule R3_API_NOT_DEPEND_ON_INFRASTRUCTURE =
            noClasses()
                    .that().resideInAPackage("..api..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..infrastructure..")
                    .because("接口层只能经 application 用例访问数据，不得绕过（R3）");

    /** R4：application 不得反向依赖 api。 */
    public static final ArchRule R4_APPLICATION_NOT_DEPEND_ON_API =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..api..")
                    .because("用例编排不知道 HTTP 的存在（R4）");

    /** R5：common 是叶子层，不得依赖任何业务层。 */
    public static final ArchRule R5_COMMON_IS_LEAF =
            noClasses()
                    .that().resideInAPackage("..common..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..api..", "..application..", "..domain..", "..infrastructure..")
                    .because("公共层被所有层依赖，反向依赖会成环（R5）");

    /** 顶层包切片不得成环。 */
    public static final ArchRule NO_PACKAGE_CYCLES =
            slices()
                    .matching("com.qa.autotest.(*)..")
                    .should().beFreeOfCycles()
                    .because("分层之间只允许单向依赖");

    /**
     * R6：禁止字段注入（{@code @Autowired} 写在字段上）。
     *
     * <p>字段注入让依赖关系在构造器签名里看不见，单测必须起容器才能装配；
     * 本项目统一构造器注入，规则把「顺手加个 @Autowired」的路堵死。
     */
    public static final ArchRule R6_NO_FIELD_INJECTION =
            noFields()
                    .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                    .because("依赖一律构造器注入，字段注入无法在编译期暴露装配问题（R6）");

    /**
     * R7：仓储接口层不得依赖 Spring JDBC。
     *
     * <p>{@code application.repository} 只声明「要什么数据」，SQL 与 JdbcTemplate
     * 属于 infrastructure；接口里出现 JdbcTemplate，等于把持久化细节漏进用例层。
     */
    public static final ArchRule R7_REPOSITORY_INTERFACES_FREE_OF_SPRING_JDBC =
            noClasses()
                    .that().resideInAPackage("..application.repository..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework.jdbc..")
                    .because("仓储接口不得依赖 Spring JDBC，SQL 只能出现在 infrastructure（R7）");

    /**
     * R8：控制器端点必须返回统一信封。
     *
     * <p>所有带 {@code @GetMapping} / {@code @PostMapping} 等注解的控制器方法，
     * 返回类型必须是 {@link com.qa.autotest.common.ApiResponse} 或其包装的
     * {@code ResponseEntity}；直接返回实体，前端就得为每个端点写一套解包逻辑。
     */
    public static final ArchRule R8_CONTROLLER_ENDPOINTS_RETURN_ENVELOPE =
            methods()
                    .that().areDeclaredInClassesThat()
                    .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    .should(new ArchCondition<>("控制器端点返回 ApiResponse 或 ResponseEntity") {
                        @Override
                        public void check(JavaMethod method, ConditionEvents events) {
                            if (!isEndpoint(method)) {
                                return;
                            }
                            String raw = method.getRawReturnType().getName();
                            boolean ok = ApiResponse.class.getName().equals(raw)
                                    || "org.springframework.http.ResponseEntity".equals(raw);
                            if (!ok) {
                                events.add(SimpleConditionEvent.violated(method,
                                        method.getFullName() + " 返回 " + raw
                                                + "，必须是 ApiResponse 或 ResponseEntity（R8）"));
                            }
                        }
                    })
                    .because("控制器端点必须返回 ApiResponse 或 ResponseEntity<ApiResponse>（R8）");

    private static final Set<String> ENDPOINT_ANNOTATIONS = Set.of(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping");

    private static boolean isEndpoint(JavaMethod method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> ENDPOINT_ANNOTATIONS.contains(annotation.getRawType().getName()));
    }

    /** 逐条对给定类集检查，任一条不过即抛 AssertionError（元测试复用）。 */
    public static void checkAll(JavaClasses classes) {
        R1_DOMAIN_NOT_DEPEND_ON_UPPER_LAYERS.check(classes);
        R2_DOMAIN_NOT_DEPEND_ON_FRAMEWORKS.check(classes);
        R3_API_NOT_DEPEND_ON_INFRASTRUCTURE.check(classes);
        R4_APPLICATION_NOT_DEPEND_ON_API.check(classes);
        R5_COMMON_IS_LEAF.check(classes);
        NO_PACKAGE_CYCLES.check(classes);
        R6_NO_FIELD_INJECTION.check(classes);
        R7_REPOSITORY_INTERFACES_FREE_OF_SPRING_JDBC.check(classes);
        R8_CONTROLLER_ENDPOINTS_RETURN_ENVELOPE.check(classes);
    }
}
