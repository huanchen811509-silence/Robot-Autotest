<script setup lang="ts">
/**
 * 资产详情三分区面板（v4.9.2 / OPT-04 / FR-49-13~16）。
 *
 * 覆盖参考表「当前测试环境」12 字段，分硬件 / 软件与带外 / 状态三区；
 * 缺失一律显式标「未采集」，静默空白 0。四层证据（collectedAt / 命令原文 /
 * 原始输出折叠区 / 结构化值）逐项齐全。采集四态 + 凭据必选 + 二次确认；
 * 失败后保留上次成功快照。凭据只显示掩码名并逐条以 keyMasked 前缀出现。
 *
 * 派生字段「网口数 / 网卡配置汇总」由 device_port 与 spec_json 在前端计算，不新增库列。
 * 新 i18n 键交付在 docs/evidence/opt-20261111/i18n/frontend-views.json。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  assetDetailApi,
  type AssetDetailView,
  type AssetSource,
  type CollectState,
  type HostInventoryItemDto,
  type MaskedCredentialOption,
} from '@/api/assetDetail'

const props = defineProps<{ deviceId: string | number; source: AssetSource }>()

const { t } = useI18n()
const detail = ref<AssetDetailView | null>(null)
/** 上次成功快照：采集失败时不清空、继续展示（FR-49-15）。 */
const snapshot = ref<AssetDetailView | null>(null)
const loading = ref(true)
const error = ref('')
const collectState = ref<CollectState>('NOT_COLLECTED')
const notice = ref('')

interface SpecShape {
  collectedAt?: string | null
  status?: string | null
  items?: {
    category?: string
    label?: string
    command?: string
    collectedAt?: string | null
    rawOutput?: string | null
    value?: unknown
    reason?: string | null
  }[]
}

const spec = computed<SpecShape | null>(() => {
  const raw = detail.value?.specJson
  if (!raw) return null
  try {
    return JSON.parse(raw) as SpecShape
  } catch {
    return null
  }
})

const ports = computed(() => detail.value?.devicePorts ?? [])

/** 网口数优先取后端提列，否则由 device_port 派生。 */
const derivedNicCount = computed<number | null>(() => {
  const d = detail.value
  if (!d) return null
  if (d.nicCount !== null && d.nicCount !== undefined) return d.nicCount
  if (ports.value.length) return ports.value.length
  return spec.value?.items?.filter((item) => item.category === 'NIC').length ?? null
})

/** 网卡配置汇总由 device_port 的 nic_name 分组派生，不读任何新增库列。 */
const derivedNicSummary = computed<string | null>(() => {
  const d = detail.value
  if (!d) return null
  if (d.nicSummary) return d.nicSummary
  if (!ports.value.length) return null
  const groups = new Map<string, number>()
  for (const port of ports.value) {
    const key = port.nicName || t("assetDetail.unassignedNic")
    groups.set(key, (groups.get(key) ?? 0) + 1)
  }
  return [...groups.entries()].map(([name, count]) => `${name} ×${count}`).join('、')
})

function valueOf(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return t("assetDetail.notCollected")
  return String(value)
}

function isMissing(value: string): boolean {
  return value === t("assetDetail.notCollected")
}

interface FieldDef {
  labelKey: string
  value: string
  testId: string
}
interface SectionDef {
  key: string
  testId: string
  fields: FieldDef[]
}

const sections = computed<SectionDef[]>(() => {
  const d = detail.value
  if (!d) return []
  return [
    {
      key: "assetDetail.section.hardware", testId: 'hardware', fields: [
        { labelKey: "assetDetail.field.hostname", value: valueOf(d.hostname), testId: 'hostname' },
        { labelKey: "assetDetail.field.managementIp", value: valueOf(d.managementIp), testId: 'managementIp' },
        { labelKey: "assetDetail.field.role", value: valueOf(d.role), testId: 'role' },
        { labelKey: "assetDetail.field.model", value: valueOf(d.model), testId: 'model' },
        { labelKey: "assetDetail.field.nicCount", value: valueOf(derivedNicCount.value), testId: 'nicCount' },
        { labelKey: "assetDetail.field.nicSummary", value: valueOf(derivedNicSummary.value), testId: 'nicSummary' },
      ],
    },
    {
      key: "assetDetail.section.software", testId: 'software', fields: [
        { labelKey: "assetDetail.field.os", value: valueOf(d.os), testId: 'os' },
        { labelKey: "assetDetail.field.kernel", value: valueOf(d.kernel), testId: 'kernel' },
        { labelKey: "assetDetail.field.bmc", value: valueOf(d.bmc), testId: 'bmc' },
        { labelKey: "assetDetail.field.login", value: valueOf(d.login), testId: 'login' },
      ],
    },
    {
      key: "assetDetail.section.status", testId: 'status', fields: [
        { labelKey: "assetDetail.field.credentialRef", value: valueOf(d.credentialRef), testId: 'credentialRef' },
        { labelKey: "assetDetail.field.vfVm", value: valueOf(d.vfVm), testId: 'vfVm' },
      ],
    },
  ]
})

interface EvidenceRow {
  label: string
  category: string
  command: string
  collectedAt: string
  rawOutput: string
  value: string
}

/** 四层证据逐项齐全；未采集时也用占位渲染齐全，不伪造事实。 */
const evidence = computed<EvidenceRow[]>(() => {
  const d = detail.value
  if (!d) return []
  const rawItems: HostInventoryItemDto[] = d.evidence?.length
    ? d.evidence
    : (spec.value?.items ?? []).map((item) => ({
        category: item.category ?? '',
        label: item.label ?? '',
        command: item.command ?? '',
        collectedAt: item.collectedAt ?? spec.value?.collectedAt ?? d.collectedAt ?? null,
        rawOutput: item.rawOutput ?? null,
        value: item.value ?? null,
        reason: item.reason ?? null,
      }))
  if (!rawItems.length) {
    const placeholder = t("assetDetail.notCollected")
    return [{
      label: placeholder,
      category: '',
      command: placeholder,
      collectedAt: placeholder,
      rawOutput: placeholder,
      value: placeholder,
    }]
  }
  return rawItems.map((item) => ({
    label: item.label || item.category || t("assetDetail.notCollected"),
    category: item.category || '',
    command: item.command || t("assetDetail.notCollected"),
    collectedAt: item.collectedAt || d.collectedAt || t("assetDetail.notCollected"),
    rawOutput: item.rawOutput || t("assetDetail.rawOutputMissing"),
    value: item.value == null ? t("assetDetail.notCollected") : JSON.stringify(item.value),
  }))
})

const showSnapshot = computed(() => collectState.value === 'FAILED' || collectState.value === 'PARTIAL')

const collectOpen = ref(false)
const credentialOptions = ref<MaskedCredentialOption[]>([])
const credentialId = ref<number | null>(null)
const confirmed = ref(false)
const collecting = ref(false)
const canCollect = computed(
  () => credentialId.value !== null && confirmed.value && !collecting.value,
)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    detail.value = await assetDetailApi.detail(props.deviceId, props.source)
    collectState.value = detail.value.collectState
    if (detail.value.collectState === 'OK') snapshot.value = detail.value
  } catch (ex) {
    error.value = (ex as { message?: string }).message ?? t("assetDetail.loadFailed")
  } finally {
    loading.value = false
  }
}

async function loadCredentials(): Promise<void> {
  try {
    credentialOptions.value = await assetDetailApi.listCredentials()
  } catch {
    credentialOptions.value = []
  }
}

function openCollect(): void {
  collectOpen.value = true
  credentialId.value = null
  confirmed.value = false
  notice.value = ''
  void loadCredentials()
}

async function runCollect(): Promise<void> {
  if (!canCollect.value) return
  collecting.value = true
  collectState.value = 'COLLECTING'
  notice.value = ''
  try {
    const result = await assetDetailApi.collect(props.deviceId, {
      credentialId: credentialId.value as number,
      confirm: true,
    })
    if (detail.value) snapshot.value = detail.value
    collectState.value = result.status === 'PARTIAL' ? 'PARTIAL' : 'OK'
    notice.value = t("assetDetail.collectDone")
    collectOpen.value = false
    await load()
  } catch (ex) {
    collectState.value = 'FAILED'
    notice.value = (ex as { message?: string }).message ?? t("assetDetail.collectFailed")
  } finally {
    collecting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="asset-detail">
    <div v-if="loading" class="asset-detail-state" data-testid="asset-detail-loading">
      {{ t("assetDetail.loading") }}
    </div>
    <p v-else-if="error" class="asset-detail-state asset-detail-error" data-testid="asset-detail-error">
      {{ error }}
    </p>

    <template v-else-if="detail">
      <div class="asset-detail-head">
        <h2 class="asset-detail-name">{{ valueOf(detail.name) }}</h2>
        <span class="badge" data-testid="collect-state">
          {{ t(`assetDetail.state.${collectState}`) }}
        </span>
        <span class="badge" data-testid="collect-collected-at">
          {{ t("assetDetail.collectedAt") }}：{{ valueOf(detail.collectedAt) }}
        </span>
        <button
          type="button"
          class="btn"
          data-testid="collect-open"
          @click="openCollect"
        >{{ t("assetDetail.collectOpen") }}</button>
      </div>

      <p v-if="notice" class="asset-detail-notice" data-testid="collect-notice">{{ notice }}</p>
      <p v-if="showSnapshot && snapshot" class="asset-detail-notice" data-testid="last-snapshot">
        {{ t("assetDetail.lastSnapshot", { at: valueOf(snapshot.collectedAt) }) }}
      </p>

      <!-- 三分区：12 字段逐项对照，缺失显式标「未采集」 -->
      <section
        v-for="section in sections"
        :key="section.key"
        class="asset-detail-section"
        :data-testid="`asset-section-${section.testId}`"
      >
        <h3 class="asset-detail-section-title">{{ t(section.key) }}</h3>
        <dl class="asset-detail-grid">
          <div
            v-for="field in section.fields"
            :key="field.testId"
            class="asset-detail-row"
            :data-testid="`asset-field-${field.testId}`"
          >
            <dt class="asset-detail-label">{{ t(field.labelKey) }}</dt>
            <dd
              class="asset-detail-value"
              :class="{ 'is-missing': isMissing(field.value) }"
            >{{ field.value }}</dd>
          </div>
        </dl>
      </section>

      <!-- 凭据：只显示掩码名，逐条 keyMasked 前缀 -->
      <section class="asset-detail-section" data-testid="asset-credentials">
        <h3 class="asset-detail-section-title">{{ t("assetDetail.credentialsTitle") }}</h3>
        <div v-if="detail.credentials.length" class="asset-detail-badges">
          <span
            v-for="credential in detail.credentials"
            :key="credential.id"
            class="badge"
            data-key-masked="keyMaskedCredential"
          >keyMasked-{{ credential.name }}：{{ credential.keyMasked }}</span>
        </div>
        <p v-else class="asset-detail-value">{{ t("assetDetail.notCollected") }}</p>
      </section>

      <!-- 四层证据：collectedAt / 命令原文 / 原始输出折叠区 / 结构化值 -->
      <section class="asset-detail-section" data-testid="evidence-section">
        <h3 class="asset-detail-section-title">{{ t("assetDetail.evidenceTitle") }}</h3>
        <div
          v-for="(row, index) in evidence"
          :key="index"
          class="evidence-item"
          :data-testid="`evidence-item-${index}`"
        >
          <p class="evidence-item-title">{{ row.label }}</p>
          <div class="evidence-layer" data-testid="evidence-collectedAt">
            <span class="evidence-key">{{ t("assetDetail.evidence.collectedAt") }}</span>
            <span>{{ row.collectedAt }}</span>
          </div>
          <div class="evidence-layer" data-testid="evidence-command">
            <span class="evidence-key">{{ t("assetDetail.evidence.command") }}</span>
            <code>{{ row.command }}</code>
          </div>
          <details class="evidence-layer" data-testid="evidence-raw">
            <summary>{{ t("assetDetail.evidence.rawOutput") }}</summary>
            <pre>{{ row.rawOutput }}</pre>
          </details>
          <div class="evidence-layer" data-testid="evidence-value">
            <span class="evidence-key">{{ t("assetDetail.evidence.value") }}</span>
            <pre>{{ row.value }}</pre>
          </div>
        </div>
      </section>

      <!-- 采集弹层：必选凭据 + 二次确认 -->
      <div v-if="collectOpen" class="modal-mask" data-testid="collect-modal">
        <div class="modal">
          <h3>{{ t("assetDetail.collectTitle") }}</h3>
          <p class="muted">{{ t("assetDetail.collectHint") }}</p>
          <div class="field" data-testid="collect-credential">
            <span>{{ t("assetDetail.collectCredential") }}</span>
            <div class="credential-options">
              <button
                v-for="option in credentialOptions"
                :key="option.id"
                type="button"
                class="chip"
                :class="{ active: credentialId === option.id }"
                :data-testid="`collect-credential-${option.id}`"
                @click="credentialId = option.id"
              >keyMasked-{{ option.name }}</button>
              <span v-if="!credentialOptions.length" class="muted">
                {{ t("assetDetail.collectNoCredential") }}
              </span>
            </div>
          </div>
          <label class="confirm-row">
            <input v-model="confirmed" type="checkbox" data-testid="collect-confirm" />
            <span>{{ t("assetDetail.collectConfirmAgain") }}</span>
          </label>
          <div class="modal-actions">
            <button type="button" class="btn" @click="collectOpen = false">
              {{ t("assetDetail.close") }}
            </button>
            <button
              type="button"
              class="btn btn-primary"
              data-testid="collect-submit"
              :disabled="!canCollect"
              @click="runCollect"
            >{{ t("assetDetail.collectSubmit") }}</button>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.asset-detail {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.asset-detail-state {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
.asset-detail-error,
.asset-detail-notice {
  color: var(--fail);
  font-size: var(--font-size-sm);
}
.asset-detail-notice {
  color: var(--warn);
}
.asset-detail-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}
.asset-detail-name {
  margin: 0 var(--space-2) 0 0;
  font-size: var(--font-size-md);
}
.badge {
  padding: 2px var(--space-2);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  color: var(--text-muted);
  background: var(--panel);
}
.asset-detail-section {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  background: var(--panel);
}
.asset-detail-section-title {
  margin: 0 0 var(--space-2);
  font-size: var(--font-size-sm);
  color: var(--text-muted);
  font-weight: 600;
}
.asset-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2) var(--space-3);
  margin: 0;
}
.asset-detail-row {
  display: flex;
  gap: var(--space-2);
  min-width: 0;
  font-size: var(--font-size-sm);
  line-height: 1.6;
}
.asset-detail-label {
  flex: none;
  width: 8em;
  color: var(--text-muted);
}
.asset-detail-value {
  margin: 0;
  min-width: 0;
  overflow-wrap: anywhere;
}
.asset-detail-value.is-missing {
  color: var(--warn);
  font-style: italic;
}
.asset-detail-badges {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}
.evidence-item {
  border-top: 1px solid var(--border);
  padding-top: var(--space-2);
  margin-top: var(--space-2);
}
.evidence-item-title {
  margin: 0 0 var(--space-1);
  font-weight: 600;
  font-size: var(--font-size-sm);
}
.evidence-layer {
  display: flex;
  gap: var(--space-2);
  font-size: var(--font-size-xs);
  padding: 2px 0;
}
.evidence-key {
  flex: none;
  width: 8em;
  color: var(--text-muted);
}
.evidence-layer pre,
.evidence-layer code {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-family: var(--font-mono, monospace);
}
.muted {
  color: var(--text-muted);
  font-size: var(--font-size-sm);
}
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
}
.modal {
  width: min(480px, 92vw);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
}
.field,
.confirm-row {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin: var(--space-2) 0;
  font-size: var(--font-size-sm);
}
.confirm-row {
  flex-direction: row;
  align-items: center;
}
.credential-options {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.chip {
  padding: 2px 10px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--panel);
  color: var(--text-muted);
  cursor: pointer;
  font-size: var(--font-size-xs);
}

.chip.active {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}

.select,
.btn {
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--panel);
  color: var(--text);
  cursor: pointer;
}
.btn-primary {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}
@media (max-width: 760px) {
  .asset-detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
