package com.qa.autotest.application.repository;

import java.util.Map;

/**
 * AI 助手配置仓储（v2.5 / FR-AI-10）。
 *
 * <p>只做存取，不做加解密与掩码——密钥的加解密属于安全边界，放在
 * {@code AiAssistantConfigService}，这样仓储层拿到的永远是密文，
 * 也避免「谁都能顺手把明文打出来」。
 */
public interface AiAssistantConfigRepository {

    /**
     * @param cliKey    CLI 标识
     * @param baseUrl   自定义端点，null 表示用 CLI 自带配置
     * @param apiKeyEnc 密钥密文；null 表示该行没有存密钥（与「清空」是两种语义，由 replaceKey 区分）
     * @param model     首选模型
     * @param enabled   是否参与候选选择
     * @param updatedAt 最后修改时间（回显用）
     */
    record Row(String cliKey, String baseUrl, String apiKeyEnc, String model,
               boolean enabled, String updatedAt) {
    }

    /** 全量读取，key = cli_key。表很小（固定 7 行量级），不做分页。 */
    Map<String, Row> findAll();

    /**
     * 写入或更新一行。
     *
     * @param row        要保存的值
     * @param replaceKey true=连 api_key_enc 一起覆盖（用户填了新 key 或明确清空）；
     *                   false=保留库里已有的密文（用户没动密钥输入框，不能把它清掉）
     * @param userId     操作人，落 updated_by（谁改的凭据要查得到）
     */
    void save(Row row, boolean replaceKey, Long userId);
}
