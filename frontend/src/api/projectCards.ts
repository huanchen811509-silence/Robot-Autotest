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
  /**
   * 当前用户是否是该项目的所有者（v4.11.0 / FR-v4.11-29）。
   *
   * `myRole` 不能用来判「我参与的」：全局 admin 在没有成员关系时后端也给 `admin`
   * （便于工作台统一显示），所以对最需要这个筛子的角色它恒为真。`own` / `joined`
   * 才是真实归属。可选是因为旧夹具没有这两个字段，缺省按 false 处理。
   */
  own?: boolean
  /** 当前用户是否是该项目的成员（有 `project_member` 行），不含「仅因是 admin 可见」。 */
  joined?: boolean
  /** 测试内核：robot（Robot Framework，默认）| pytest。缺省按 robot 显示。 */
  testKernel?: string | null
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
