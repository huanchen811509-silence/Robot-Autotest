package com.qa.autotest.application.asset;

/**
 * 资产变更事件（v4.3.0 / FR-v4.3-05 T1、T2）。
 *
 * <p>设备、端口、链路写入，以及共享/引用/解除引用都会发这条事件。监听方在事务提交后
 * 清掉对应项目的连接/拓扑缓存，保证「资产变更立即失效」，不必等 12 小时窗口到期。
 *
 * @param projectId 受影响项目；平台资产变更时为 {@code null}（影响所有引用方）
 */
public record AssetChangedEvent(Long projectId, String assetType, long assetId) {
}
