package com.qa.autotest.domain.ai;

/**
 * 一条可追溯证据引用（FR-3.3-02）。
 *
 * <p>{@code id} 是回答正文里引用的编号（如 {@code E1} 对应 {@code [E1]}），
 * {@code path} 是前端可直接跳转的相对路径；权限过滤在服务端完成，前端不做二次裁剪。
 */
public record AiCitation(String id, String type, String ref, String label, String path) {
}
