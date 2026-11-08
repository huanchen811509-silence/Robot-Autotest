/**
 * MENU-03：总表看板双计数（纯函数 / 路由 / 源码断言，不挂载组件、不新增依赖）。
 * EVID-04：四态枚举、原始流弹窗、JSON 下载与无证据态的机器判定断言。
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import router from '@/router'
import {
  summarizeBoard,
  CURRENT_STATUSES,
  hasEvidence,
  buildEvidenceJson,
  renderEvidenceLog,
  type CaseBoardRow,
  type RawEvidence,
} from '@/views/caseBoard'

function readSource(name: string): string {
  try {
    return readFileSync(new URL(`./${name}`, import.meta.url), 'utf-8')
  } catch {
    return readFileSync(`src/views/${name}`, 'utf-8')
  }
}
const source = readSource('CaseBoardView.vue')
const logicSource = readSource('caseBoard.ts')

describe('MENU-03 总表看板双计数', () => {
  it('空态「0 条」，两项计数互不串用', () => {
    const empty = summarizeBoard([])
    expect(empty.total).toBe(0)
    expect(empty.historicalPass).toBe(0)
    expect(empty.currentPass).toBe(0)
    expect(empty.currentUntested).toBe(0)
  })

  it('导入后：当前列全为未测试、历史通过数不为 0，且当前通过不被历史通过污染', () => {
    // 仅测试夹具里的构造样本，不是页面里的硬编码业务数据；当前列用四态枚举值
    const rows: CaseBoardRow[] = [
      { caseKey: 'C-001', historical_status: '通过', current_status: 'untested' },
      { caseKey: 'C-002', historical_status: '通过', current_status: null },
      { caseKey: 'C-003', historical_status: '失败', current_status: 'untested' },
    ]
    const s = summarizeBoard(rows)
    expect(s.total).toBe(3)
    expect(s.historicalPass).toBeGreaterThan(0)
    expect(s.currentPass).toBe(0) // 不得把 ED202 历史「通过 330」当本平台当前通过率
    expect(s.currentUntested).toBe(3) // 当前列全部为未测试（null 也计入）
    expect(s.currentPass).not.toBe(s.historicalPass)
  })

  it('看板路由可达，且同屏并列显示两列', () => {
    const resolved = router.resolve('/cases/board')
    expect(resolved.matched.at(-1)?.name).toBe('case-board')
    expect(resolved.matched.at(-1)?.name).not.toBe('not-found')
    expect(source).toContain('historical_status')
    expect(source).toContain('current_status')
  })
})

describe('EVID-04 四态枚举与双计数隔离', () => {
  it('当前轮状态收口为 4 个枚举值，且四态各有样本', () => {
    expect(CURRENT_STATUSES).toEqual(['passed', 'failed', 'untested', 'missing_evidence'])
    expect(CURRENT_STATUSES).toHaveLength(4)
    expect(logicSource).toContain('as const')
    expect(logicSource).not.toContain('export type BoardStatus = string')
    const rows: CaseBoardRow[] = [
      { caseKey: 'E-P', historical_status: null, current_status: 'passed' },
      { caseKey: 'E-F', historical_status: null, current_status: 'failed' },
      { caseKey: 'E-U', historical_status: null, current_status: 'untested' },
      { caseKey: 'E-M', historical_status: null, current_status: 'missing_evidence' },
    ]
    const s = summarizeBoard(rows)
    expect(s.total).toBe(4)
    expect(s.currentPass).toBe(1)
    expect(s.currentUntested).toBe(1)
    expect(s.historicalPass).toBe(0)
  })

  it('历史通过数不写进当前列（EVID-04 新增锁死断言）', () => {
    const rows: CaseBoardRow[] = [
      { caseKey: 'E-1', historical_status: '通过', current_status: 'failed' },
      { caseKey: 'E-2', historical_status: '通过', current_status: 'untested' },
      { caseKey: 'E-3', historical_status: '失败', current_status: 'passed' },
    ]
    const s = summarizeBoard(rows)
    expect(s.historicalPass).toBe(2)
    expect(s.currentPass).toBe(1)
    expect(s.currentPass).not.toBe(s.historicalPass)
    expect(s.currentUntested).toBe(1)
  })
})

const sampleEvidence: RawEvidence = {
  hostname: 'node-a',
  stepNo: 2,
  stepDescription: '采集吞吐基线',
  command: 'sh -c "printf hello"',
  stdout: 'hello\n',
  stderr: 'warn\n',
  exitCode: 3,
}

describe('EVID-04 原始流弹窗 / JSON 下载 / 无证据态', () => {
  it('弹窗原始流五要素与后端 EvidenceLogWriter 口径一致', () => {
    const log = renderEvidenceLog(sampleEvidence, '#步骤2说明 采集吞吐基线')
    expect(log).toContain('[node-a]')
    expect(log).toContain('#步骤2说明 采集吞吐基线')
    expect(log).toContain('$ sh -c')
    expect(log).toContain('--- stdout ---')
    expect(log).toContain('hello')
    expect(log).toContain('--- stderr ---')
    expect(log).toContain('warn')
    expect(log).toContain('exit_code=3')
  })

  it('有证据可下载真实 JSON；无证据返回 null，下载不可用', () => {
    const withEvidence: CaseBoardRow = {
      caseKey: 'E-P',
      historical_status: null,
      current_status: 'passed',
      evidence: sampleEvidence,
    }
    const withoutEvidence: CaseBoardRow = {
      caseKey: 'E-M',
      historical_status: null,
      current_status: 'missing_evidence',
      evidence: null,
    }
    expect(hasEvidence(withEvidence)).toBe(true)
    expect(hasEvidence(withoutEvidence)).toBe(false)
    const json = buildEvidenceJson(withEvidence)
    expect(json).not.toBeNull()
    const parsed = JSON.parse(json as string)
    expect(parsed.caseKey).toBe('E-P')
    expect(parsed.current_status).toBe('passed')
    expect(parsed.evidence.hostname).toBe('node-a')
    expect(parsed.evidence.exitCode).toBe(3)
    expect(buildEvidenceJson(withoutEvidence)).toBeNull()
  })

  it('页面源码具备四态分支 / 弹窗 data-testid / Blob 下载 / 无证据禁用 / 证据缺失文案', () => {
    expect(source).toContain("case 'passed'")
    expect(source).toContain("case 'failed'")
    expect(source).toContain("case 'untested'")
    expect(source).toContain("case 'missing_evidence'")
    expect(source).toContain('data-testid="evidence-dialog"')
    expect(source).toContain('data-testid="evidence-log"')
    expect(source).toContain("'data-testid': 'download-btn'")
    expect(source).toContain('new Blob')
    expect(source).toContain('URL.createObjectURL')
    expect(source).toContain('anchor.download')
    expect(source).toContain('disabled:')
    expect(source).toContain("t('caseBoard.evidence.missing')")
    expect(source).toContain('buildEvidenceJson')
    expect(source).toContain('renderEvidenceLog')
  })
})
