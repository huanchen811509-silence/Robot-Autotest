package com.qa.autotest.application.kernel.pytest;

import java.util.Locale;

/**
 * 网卡厂商/型号的归一化规则（FR-v5.2-08 / 验收 B21–B23 / W52-03）。
 *
 * <p><b>只做字符层面的确定性收敛，不做语义猜测</b>。资产里的 {@code nic_model}
 * 是自由文本，同一个型号在不同录入批次里会写成
 * {@code ConnectX-7} / {@code connectx7} / {@code CX-7}——这三种字符差异可以靠规则抹平；
 * 但 {@code ConnectX-7} 与 {@code MT2910} 之间的等价关系是**业务事实**，只能由字典行
 * 人工登记，平台不能靠相似度去猜。猜错的后果是「基线拿另一张卡的阈值来比」，
 * 比不比对更危险。
 *
 * <p>唯一的例外是厂商更名（{@code mellanox → nvidia}）：这是公开事实且一对一，
 * 写成规则比让每个项目各自登记一条字典更不容易出错。规则只作用于**匹配键**，
 * 不改资产上记录的原始厂商名——界面上该显示什么就显示什么。
 */
public final class NicModelNormalizer {

    private NicModelNormalizer() {
    }

    /**
     * 归一化单个字段：小写，并**删掉所有非字母数字字符**。
     *
     * <p>为什么连连字符也删：{@code ConnectX-7} 与 {@code connectx7} 是同一张卡的两种排版，
     * 只把分隔符折叠成 {@code -} 会让这两者仍然不等（一个 {@code connectx-7}、
     * 一个 {@code connectx7}），字典就会被迫登记两行。型号字段里的连字符、空格、斜杠
     * 全部是排版差异，删掉最稳；而真正区分型号的字符（数字与字母）一个不动。
     *
     * <p>空值返回 {@code null} 而不是空串：调用方要能区分「没填」与「填了但归一化后为空」。
     */
    public static String field(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        value = value.replaceAll("[^a-z0-9]", "");
        return value.isEmpty() ? null : value;
    }

    /**
     * 厂商匹配键。{@code Mellanox} 与 {@code NVIDIA} 是同一家（2020 年收购），
     * 两个名字在存量资产里都有；不合并就会出现「同一个卡按厂商分了两条字典」。
     */
    public static String vendor(String raw) {
        String value = field(raw);
        if (value == null) {
            return null;
        }
        // 注意：别名表里的键必须是**已经过 field() 归一化**的形态（无连字符、全小写），
        // 写成 "nvidia-corporation" 永远命中不了——这个坑只有实测才会暴露。
        return switch (value) {
            case "mellanox", "nvidiacorporation", "nvidiacorp" -> "nvidia";
            case "broadcominc", "broadcomltd", "broadcomlimited" -> "broadcom";
            case "intelcorporation", "intelcorp" -> "intel";
            default -> value;
        };
    }

    /** 型号匹配键。 */
    public static String model(String raw) {
        return field(raw);
    }

    /**
     * 字典唯一键：{@code 厂商键|型号键}。任一侧为空返回 {@code null}——
     * 缺厂商或型号的行不该进字典，否则会出现一条「谁都能匹配上」的空行。
     */
    public static String dictKey(String vendor, String model) {
        String vendorKey = vendor(vendor);
        String modelKey = model(model);
        if (vendorKey == null || modelKey == null) {
            return null;
        }
        return vendorKey + "|" + modelKey;
    }

    /**
     * 资产行的匹配键。厂商缺失时只按型号匹配是不安全的（同型号不同厂商的卡能力不同），
     * 所以返回 {@code null} 表示「无法匹配」，由调用方记为未映射。
     */
    public static String assetKey(String vendor, String model) {
        return dictKey(vendor, model);
    }

    /** 展示用文本：折叠空白但保留原始大小写与连字符写法。 */
    public static String display(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().replaceAll("\\s{2,}", " ");
        return value.isEmpty() ? null : value;
    }
}
