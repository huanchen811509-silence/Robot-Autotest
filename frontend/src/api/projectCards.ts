/** 工作台项目卡牌接口（v2.3 / UI-03，FR-UI-10..14）。 */

import { request, toQuery } from './http'

/**
 * 单张项目卡牌。
 *
 * <p>可空项一律 `| null`：`passRateBp` / `lastRunAt` / `myRole` / `description`
 * 在没有数据时后端给 null，前端必须显示「—」而不是 0 —— 「0 次运行」和
 * 「从未运行」是两件事，都渲染成 0 会让用户以为项目跑挂了。
 */
export interface ProjectCard {
  id: number
  code: string
  name: string
  description: string | null
  enabled: boolean
  visibility: string
  ownerId: number | null
  isCurrent: boolean
  caseCount: number
  runCount: number
  /** 基点（1/10000）；null = 窗口内没有完成过运行。 */
  passRateBp: number | null
  lastRunAt: string | null
  deviceCount: number
  memberCount: number
  myRole: string | null
}

export const projectCardsApi = {
  /**
   * 一次拉全部项目卡牌（FR-UI-14：整个页面只发这一个请求，
   * 不许为每张卡再打 `/projects/{id}/overview`）。
   */
  list(window: string = '7d', current?: number | null): Promise<ProjectCard[]> {
    return request<ProjectCard[]>(`/projects/cards${toQuery({ window, current })}`)
  },
}
