/**
 * 错误文案本地化（FR-v0.2-36）。
 *
 * <p>后端 `ErrorCode` 的每个码都带稳定英文 `name`（见 `common/ErrorCode.java`），
 * 但 `message` 字段是**中文业务原文**（213 处）。英文界面直接把 `message` 弹出来，
 * 用户看到的就是一大段中文。
 *
 * <p>本文件的口径（也是 DEV-PLAN §10.4 写明的边界）：
 *
 * <pre>
 * 界面语言   主文案（summary）                     详情（detail，折叠）
 * 中文       服务端原文 —— 它比通用文案更具体       错误码 name + code
 * 英文       按错误码本地化的通用文案              服务端中文原文 + 错误码
 * 前端合成   按错误码本地化（超时/断网/未知）        浏览器原始报错
 * </pre>
 *
 * <p>**为什么不一律用本地化文案**：中文界面下服务端说「用例编号与名称必填」，
 * 而通用文案只能说「参数校验失败」——把更具体的那句藏进折叠里是退步。
 * 反过来英文界面下中文原文不可读，就只能以码为准、原文留作追溯。
 *
 * <p>**彻底解决**需要服务端错误结构化（码 + 参数 + i18n key），单列 `UX-03b`，不在本包。
 */
import { i18n } from '@/locales'
import { ApiError, SYNTHETIC_CODE_BASE } from '@/api/http'

export interface ErrorLine {
  /** 主文案，一定非空。 */
  summary: string
  /** 折叠里显示的原始信息，无内容时为 null（不渲染折叠条）。 */
  detail: string | null
}

function translate(key: string): string | null {
  return i18n.global.te(key) ? i18n.global.t(key) : null
}

/**
 * 任意异常 → 主文案 + 详情。
 *
 * <p>不抛异常、不返回空串：最差也会落到 `error.UNKNOWN`，避免出现"操作失败但界面没有任何提示"。
 */
export function localizeError(e: unknown): ErrorLine {
  const unknown = translate('error.UNKNOWN') ?? 'Unknown error'

  if (!(e instanceof ApiError)) {
    const text = e instanceof Error ? e.message : String(e ?? '')
    return { summary: text.trim() || unknown, detail: null }
  }

  const localized = translate(`error.${e.codeName}`)
  const code = `${e.codeName} (${e.code})`
  // v4.9.1 / T49-17：追踪号一并展示，用户可直接复制给运维定位。
  const trace = e.traceId ? `traceId: ${e.traceId}` : null
  // 前端合成的码（超时 / 断网）没有服务端原文，也就没有可追溯的详情
  if (e.code >= SYNTHETIC_CODE_BASE) {
    return {
      summary: localized ?? unknown,
      detail: [e.message.trim(), trace].filter((part) => part && part.length > 0).join('\n') || null,
    }
  }

  if (i18n.global.locale.value.startsWith('zh')) {
    return {
      summary: e.message.trim() || localized || unknown,
      detail: [code, trace].filter((part) => part && part.length > 0).join(' · '),
    }
  }
  return {
    summary: localized ?? unknown,
    detail: [e.message.trim(), code, trace].filter((part) => part && part.length > 0).join('\n'),
  }
}

/** `error` 提示条是否该显示。集中一处，避免各页面各写 `v-if="!!error"`。 */
export function hasError(line: ErrorLine | null | undefined): boolean {
  return Boolean(line && line.summary)
}
