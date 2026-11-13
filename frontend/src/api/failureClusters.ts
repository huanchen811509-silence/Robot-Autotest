/**
 * 失败聚类接口（ORCH-03，v2.1）。
 *
 * 两级结构：`classes[]`（失败类别，复用后端 FailureClassifier）
 * → `clusters[]`（归一化簇键）。runId 用业务 ID，与报告/打印页引用的是同一个标识。
 */
import { request, toQuery } from './http'
import type { ExecutionRun } from './execution'

export interface ClusterSample {
  caseId: number | null
  caseCode: string | null
  caseTitle: string | null
}

export interface FailureCluster {
  /** 归一化簇指纹：聚合与重跑用它，界面上不要直接展示 */
  key: string
  /** 可读原文（首个样本失败消息首行），清单展示用它（v5.6.0 / FR-v5.6-22） */
  display: string
  count: number
  ratio: number
  samples: ClusterSample[]
  caseIds: number[]
}

export interface FailureClassGroup {
  failureClass: string
  count: number
  ratio: number
  clusters: FailureCluster[]
}

export interface FailureClusterView {
  runId: string
  total: number
  runStatus: string | null
  runFailureClass: string | null
  classes: FailureClassGroup[]
}

export const failureClusterApi = {
  load(projectId: number, runId: string): Promise<FailureClusterView> {
    return request<FailureClusterView>(`/failure-clusters${toQuery({ projectId, runId })}`)
  },

  /**
   * 跨 run 聚类（W5）：最近 N 轮失败按同一套归一化规则合并。
   * 返回的 `runId` 是作用域标记 `recent:N`，不能拿去调 rerun。
   */
  recent(projectId: number, runs: number): Promise<FailureClusterView> {
    return request<FailureClusterView>(`/failure-clusters/recent${toQuery({ projectId, runs })}`)
  },

  rerun(projectId: number, runId: string, clusterKey: string): Promise<ExecutionRun> {
    return request<ExecutionRun>('/failure-clusters/rerun', {
      method: 'POST',
      body: JSON.stringify({ projectId, runId, clusterKey }),
    })
  },
}
