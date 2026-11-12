package com.qa.autotest.application.repository;

import com.qa.autotest.domain.asset.NicModelDict;
import java.util.List;
import java.util.Optional;

/**
 * 网卡型号字典仓储（FR-v5.2-08）。
 *
 * <p><b>为什么不在表里存归一化键</b>：归一化规则是本层的 Java 逻辑，如果把它也算一份
 * 存进列里（或写成 MySQL 生成列），规则一改就出现「Java 按新规则算、库里按旧规则存」
 * 的两套真源——这类不一致最难查，因为两边看起来都对。字典只有几十到几百行，
 * 读取时在内存建索引的代价可以忽略，换来的是「规则改完立刻全库生效」。
 *
 * <p>停用而不删除：历史资产可能指向已停用的行，删掉会让「这台机器当初归到哪个型号」
 * 变成悬空引用。所以这里没有 {@code delete}，只有 {@code setEnabled(false)}。
 */
public interface NicModelDictRepository {

    /** 平台级字典（不按项目隔离）：型号是硬件事实，不属于某个项目。 */
    List<NicModelDict> list(boolean includeDisabled);

    Optional<NicModelDict> find(long id);

    /** @return 新行 id */
    long insert(NicModelDict row, Long operatorId);

    void update(NicModelDict row, Long operatorId);

    void setEnabled(long id, boolean enabled, Long operatorId);

    long countAll();

    /** 指向某字典行的设备数（停用前提示影响面）。 */
    long countDevicesUsing(long id);
}
