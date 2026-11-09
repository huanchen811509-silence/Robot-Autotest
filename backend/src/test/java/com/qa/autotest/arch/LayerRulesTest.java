package com.qa.autotest.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 正向验证：真实代码必须满足 R1~R5（TC-v0.1-010、TC-v0.1-014）。
 *
 * <p>规则对象直接引用 {@link LayerRules} 里的常量，不在本类重抄一遍——
 * 重抄会与元测试使用的规则漂移，等于没测。
 */
@AnalyzeClasses(packages = "com.qa.autotest", importOptions = ImportOption.DoNotIncludeTests.class)
public class LayerRulesTest {

    @ArchTest
    static final ArchRule R1_domain_不依赖上层 = LayerRules.R1_DOMAIN_NOT_DEPEND_ON_UPPER_LAYERS;

    @ArchTest
    static final ArchRule R2_domain_不依赖框架 = LayerRules.R2_DOMAIN_NOT_DEPEND_ON_FRAMEWORKS;

    @ArchTest
    static final ArchRule R3_api_不依赖基础设施 = LayerRules.R3_API_NOT_DEPEND_ON_INFRASTRUCTURE;

    @ArchTest
    static final ArchRule R4_application_不依赖api = LayerRules.R4_APPLICATION_NOT_DEPEND_ON_API;

    @ArchTest
    static final ArchRule R5_common_是叶子 = LayerRules.R5_COMMON_IS_LEAF;

    @ArchTest
    static final ArchRule 分层无环 = LayerRules.NO_PACKAGE_CYCLES;

    @ArchTest
    static final ArchRule R6_禁止字段注入 = LayerRules.R6_NO_FIELD_INJECTION;

    @ArchTest
    static final ArchRule R7_仓储接口不依赖SpringJdbc =
            LayerRules.R7_REPOSITORY_INTERFACES_FREE_OF_SPRING_JDBC;

    @ArchTest
    static final ArchRule R8_端点返回统一信封 = LayerRules.R8_CONTROLLER_ENDPOINTS_RETURN_ENVELOPE;
}
