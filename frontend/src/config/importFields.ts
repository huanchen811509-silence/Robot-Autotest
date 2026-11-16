/**
 * 导入字段映射的候选字段（v5.18.0 / FR-v5.18-01）。
 *
 * <p>为什么在前端固定一份：映射页要给人看的是「这一列指到哪个业务字段」，
 * 字段名直接透出后端内部标识（`deviceName` / `caseCode`）对使用者毫无意义。
 * 这里是**下拉选项的来源**，真正的合法性由后端按别名表与 DTO 校验——
 * 前端这份清单只影响可选范围，不构成约束。
 */
export interface ImportField {
  /** 后端内部字段名，原样透传给 mapping。 */
  value: string
  /** i18n 键，不写死中文，避免英文界面出现中文选项。 */
  labelKey: string
}

export const ASSET_IMPORT_FIELDS: ImportField[] = [
  { value: 'deviceName', labelKey: 'importFields.deviceName' },
  { value: 'portName', labelKey: 'importFields.portName' },
  { value: 'portType', labelKey: 'importFields.portType' },
  { value: 'speedGbps', labelKey: 'importFields.speedGbps' },
  { value: 'macAddress', labelKey: 'importFields.macAddress' },
  { value: 'portStatus', labelKey: 'importFields.portStatus' },
  { value: 'portIp', labelKey: 'importFields.portIp' },
  { value: 'nicName', labelKey: 'importFields.nicName' },
  { value: 'credName', labelKey: 'importFields.credName' },
  { value: 'username', labelKey: 'importFields.username' },
  { value: 'deviceType', labelKey: 'importFields.deviceType' },
  { value: 'managementIp', labelKey: 'importFields.managementIp' },
  { value: 'model', labelKey: 'importFields.model' },
  { value: 'serialNo', labelKey: 'importFields.serialNo' },
]

export const CASE_IMPORT_FIELDS: ImportField[] = [
  { value: 'caseCode', labelKey: 'importFields.caseCode' },
  { value: 'title', labelKey: 'importFields.title' },
  { value: 'priority', labelKey: 'importFields.priority' },
  { value: 'category', labelKey: 'importFields.category' },
  { value: 'precondition', labelKey: 'importFields.precondition' },
  { value: 'steps', labelKey: 'importFields.steps' },
  { value: 'expected', labelKey: 'importFields.expected' },
  { value: 'domainTag', labelKey: 'importFields.domainTag' },
  { value: 'description', labelKey: 'importFields.description' },
  { value: 'safetyLevel', labelKey: 'importFields.safetyLevel' },
]

export function fieldsOf(kind: 'asset' | 'case'): ImportField[] {
  return kind === 'asset' ? ASSET_IMPORT_FIELDS : CASE_IMPORT_FIELDS
}
