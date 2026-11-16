<script setup lang="ts">
/**
 * 资产导入块（v5.18.0 / FR-v5.18-01 / W68-05 提前落地）。
 *
 * <p>把「导入入口 + 五步向导 + 批次记录」从 `GlobalAssetsView` 里搬出来。搬的原因有两个：
 * 一是 W68-04~06 要把两个资产页压到 ≤2200 行，导入是最大的一块；
 * 二是原来「导入记录」在资产侧根本没有入口，批次只能靠接口查——判据 7 要求结果页能跳过去看批次。
 *
 * <p>受控方式：`open` 由父页持有（工具栏按钮在父页），`openBatches` 让父页把 `?view=imports`
 * 传进来时直接展开批次面板。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import DataTable from '@/components/DataTable.vue'
import ImportWizard from '@/components/ImportWizard.vue'
import { globalAssetApi } from '@/api/globalAssets'

const props = defineProps<{
  open: boolean
  /** 只读用户不给导入入口：预检与提交都要求全局写权限。 */
  canWrite: boolean
  /** 父页 `?view=imports` → 直接展开批次列表。 */
  openBatches?: boolean
}>()

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'committed'): void
}>()

const { t } = useI18n()

const batchOpen = ref(props.openBatches === true)
const batches = ref<Record<string, unknown>[]>([])
const batchTotal = ref(0)
const loading = ref(false)

const columns = computed(() => [
  { title: t('importWizard.batchId'), key: 'id', width: 90 },
  { title: t('importWizard.batchSource'), key: 'source_name', minWidth: 200 },
  { title: t('importWizard.batchStatus'), key: 'status', width: 120 },
  { title: t('importWizard.batchRows'), key: 'total_rows', width: 100 },
  { title: t('importWizard.batchCreated'), key: 'created_at', minWidth: 160 },
])

async function loadBatches(): Promise<void> {
  loading.value = true
  try {
    const page = await globalAssetApi.batches(1, 20)
    batches.value = page.items
    batchTotal.value = page.total
  } catch {
    // 批次列表失败不影响导入本身：这里不抛，避免把「看得见的功能」连带打掉
    batches.value = []
    batchTotal.value = 0
  } finally {
    loading.value = false
  }
}

function openWizard(): void {
  emit('update:open', true)
}

function closeWizard(): void {
  emit('update:open', false)
}

function toggleBatches(): void {
  batchOpen.value = !batchOpen.value
  if (batchOpen.value) {
    void loadBatches()
  }
}

function onCommitted(): void {
  emit('committed')
  // 提交完顺手刷批次：用户下一步多半就是去看这批到底写了什么
  void loadBatches()
}

watch(
  () => props.openBatches,
  (value) => {
    if (value) {
      batchOpen.value = true
      void loadBatches()
    }
  },
)

onMounted(() => {
  if (batchOpen.value) {
    void loadBatches()
  }
})
</script>

<template>
  <div class="asset-import-block" data-testid="asset-import-block">
    <div class="asset-import-actions">
      <button
        v-if="canWrite"
        class="btn btn-primary"
        data-testid="asset-import-open"
        @click="openWizard"
      >
        {{ t('global.import') }}
      </button>
      <button class="btn" data-testid="asset-batches-toggle" @click="toggleBatches">
        {{ t('importWizard.viewBatches') }}
      </button>
    </div>

    <ImportWizard
      v-if="open"
      kind="asset"
      @close="closeWizard"
      @committed="onCommitted"
      @open-batches="batchOpen = true; loadBatches()"
    />

    <div v-if="batchOpen" class="modal-mask" data-testid="asset-batches-panel" @click.self="batchOpen = false">
      <div class="modal">
        <header class="modal-head">
          <h2>{{ t('importWizard.batchesTitle') }}</h2>
          <button class="link" @click="batchOpen = false">{{ t('global.close') }}</button>
        </header>
        <p class="muted">{{ t('importWizard.batchTotal', { total: batchTotal }) }}</p>
        <p v-if="loading" class="muted">{{ t('global.loading') }}</p>
        <DataTable
          :columns="columns"
          :data="batches"
          :row-key="(row: Record<string, unknown>) => String(row.id)"
          :empty-title="t('global.empty')"
          :max-height="420"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.asset-import-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
