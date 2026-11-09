package com.qa.autotest.arch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 元测试：证明分层规则**真的能拦住**违规代码（TEST-PLAN TC-v0.1-011 ~ 013）。
 *
 * <p>只写「正常代码能过」证明不了规则有效——那只能证明规则没把一切判违规。
 * 这里对故意违规的 fixture 包跑同一条规则，必须抛 {@link AssertionError} 且消息里带违规类名。
 */
class LayerRulesMetaTest {

    private static JavaClasses importFixture(String pkg) {
        return new ClassFileImporter().importPackages(pkg);
    }

    @Test
    @DisplayName("TC-v0.1-011 domain 依赖 api 必须被拦住")
    void domain_依赖_api_被拦() {
        JavaClasses bad = importFixture("com.qa.archfixture.violation.domain2api.domain");
        assertThatThrownBy(() -> LayerRules.R1_DOMAIN_NOT_DEPEND_ON_UPPER_LAYERS.check(bad))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DomainUsingApi")
                .hasMessageContaining("ApiDto");
    }

    @Test
    @DisplayName("TC-v0.1-012 api 直接依赖 infrastructure 必须被拦住")
    void api_依赖_infrastructure_被拦() {
        JavaClasses bad = importFixture("com.qa.archfixture.violation.api2infra.api");
        assertThatThrownBy(() -> LayerRules.R3_API_NOT_DEPEND_ON_INFRASTRUCTURE.check(bad))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ApiCallingInfra")
                .hasMessageContaining("InfraRepository");
    }

    @Test
    @DisplayName("TC-v0.1-013 domain 带 JPA 注解必须被拦住")
    void domain_带_JPA_注解_被拦() {
        JavaClasses bad = importFixture("com.qa.archfixture.violation.domainjpa.domain");
        assertThatThrownBy(() -> LayerRules.R2_DOMAIN_NOT_DEPEND_ON_FRAMEWORKS.check(bad))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("JpaAnnotatedEntity")
                .hasMessageContaining("jakarta.persistence");
    }

    @Test
    @DisplayName("干净的领域对象不被误判（规则不能把所有代码都判红）")
    void 干净领域对象通过() {
        JavaClasses clean = importFixture("com.qa.archfixture.clean.domain");
        assertThatCode(() -> LayerRules.R1_DOMAIN_NOT_DEPEND_ON_UPPER_LAYERS.check(clean))
                .doesNotThrowAnyException();
        assertThatCode(() -> LayerRules.R2_DOMAIN_NOT_DEPEND_ON_FRAMEWORKS.check(clean))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R6：字段注入必须被拦住")
    void 字段注入被拦() {
        JavaClasses bad = importFixture("com.qa.archfixture.violation.fieldinjection");
        assertThatThrownBy(() -> LayerRules.R6_NO_FIELD_INJECTION.check(bad))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("FieldInjectedBean")
                .hasMessageContaining("Autowired");
    }

    @Test
    @DisplayName("R7：仓储接口依赖 Spring JDBC 必须被拦住")
    void 仓储接口依赖Jdbc被拦() {
        JavaClasses bad = importFixture("com.qa.archfixture.violation.repojdbc.application.repository");
        assertThatThrownBy(() -> LayerRules.R7_REPOSITORY_INTERFACES_FREE_OF_SPRING_JDBC.check(bad))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("LeakyOrderRepository")
                .hasMessageContaining("JdbcTemplate");
    }

    @Test
    @DisplayName("R8：端点返回裸类型必须被拦住")
    void 端点返回裸类型被拦() {
        JavaClasses bad = importFixture("com.qa.archfixture.violation.controllerraw.api");
        assertThatThrownBy(() -> LayerRules.R8_CONTROLLER_ENDPOINTS_RETURN_ENVELOPE.check(bad))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RawController.raw")
                .hasMessageContaining("必须是 ApiResponse");
    }
}
