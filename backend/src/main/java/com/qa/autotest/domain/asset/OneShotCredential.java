package com.qa.autotest.domain.asset;

/**
 * 一次性凭证（v5.7.0 / W57-39、40、42）。
 *
 * <p>纯内存 record：材料只从 POST body 进来，只在本次调用栈里活一次，
 * <b>不落 DB、不写日志 / 审计 / 响应 / 证据包 / 异常堆栈</b>。它没有任何仓储、没有
 * {@code @Entity}、没有序列化出口；{@link #toString()} 对口令 / 私钥 / 连接参数全部打码，
 * 防止有人「顺手 log 一下这个对象」把明文带出去。
 *
 * @param secret        口令（与 privateKey 至少给一个）
 * @param privateKey    私钥（与 secret 至少给一个）
 * @param connectOptions 连接参数，可能夹带敏感片段，同样不入 toString
 */
public record OneShotCredential(String username, String secret, String privateKey,
                                String credType, String connectOptions) {

    public OneShotCredential {
        if ((secret == null || secret.isBlank()) && (privateKey == null || privateKey.isBlank())) {
            // 固定文案，绝不把入参拼进异常消息
            throw new IllegalArgumentException("一次性凭证至少要给出口令或私钥");
        }
    }

    /** 打码：口令 / 私钥 / 连接参数一律不出现明文，连长度都不暴露。 */
    @Override
    public String toString() {
        return "OneShotCredential[username=" + (username == null ? "-" : username)
                + ", credType=" + credType
                + ", secret=" + mask(secret)
                + ", privateKey=" + mask(privateKey)
                + ", connectOptions=" + mask(connectOptions) + "]";
    }

    private static String mask(String value) {
        return value == null ? "-" : "***";
    }
}
