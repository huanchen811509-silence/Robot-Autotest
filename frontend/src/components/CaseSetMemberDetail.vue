<script setup lang="ts">
/**
 * 用例集成员详情（v5.12 / W62-03）：字段只从 `CaseSetMemberView` 读，
 * 脚本行复用 5.11 的 `CaseScriptLink`（文件名/路径都由后端给，这里不切字符串）。
 */
import { useI18n } from 'vue-i18n'
import CaseScriptLink from '@/components/CaseScriptLink.vue'
import {
  MEMBER_DETAIL_ROWS,
  type CaseSetMemberView,
  type CaseSetMemberField,
} from '@/config/caseSetMemberColumns'

const props = defineProps<{ member: CaseSetMemberView; readOnlyScript?: boolean }>()
const { t } = useI18n()

function text(field: CaseSetMemberField): string {
  const value = props.member[field]
  if (value === null || value === undefined || value === '') return t('caseDetail.emptyValue')
  return String(value)
}
</script>

<template>
  <div class="member-detail" data-testid="member-detail">
    <dl class="member-detail-grid">
      <div
        v-for="row in MEMBER_DETAIL_ROWS"
        :key="row.field"
        class="member-detail-row"
        :data-testid="`member-field-${row.field}`"
      >
        <dt class="member-detail-label">{{ t(row.labelKey) }}</dt>
        <dd class="member-detail-value">{{ text(row.field) }}</dd>
      </div>
    </dl>
    <div class="member-detail-script" data-testid="member-detail-script">
      <span class="member-detail-label">{{ t('w62.memberScript') }}</span>
      <CaseScriptLink
        v-if="!readOnlyScript"
        full
        :script-path="member.scriptPath"
        :script-name="member.scriptName"
        :binding-state="member.bindingState"
        :other-script-count="member.otherScriptCount"
        :absolute-source="member.scriptAbsoluteSource"
      />
      <span v-else class="muted" data-testid="member-script-not-applicable">
        {{ t('w62.platformReadonlyScript') }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.member-detail { display: block; }
.member-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 20px;
  margin: 0;
}
.member-detail-row { display: flex; gap: 8px; min-width: 0; }
.member-detail-label { flex: 0 0 100px; opacity: 0.7; font-size: 12px; }
.member-detail-value { margin: 0; min-width: 0; overflow-wrap: anywhere; font-size: 13px; }
.member-detail-script { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
.muted { opacity: 0.7; font-size: 13px; }
</style>
