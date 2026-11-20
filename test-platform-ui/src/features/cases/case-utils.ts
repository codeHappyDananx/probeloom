import type { ActionResult, CaseAction, CaseGroup, Execution, TestCase } from "@/types/platform"

export const actionLabels: Record<CaseAction["actionType"], string> = { INSERT: "数据准备", UPDATE: "更新数据", UPLOAD: "文件导入", HTTP: "HTTP 调用", ASSERT: "核对操作", CLEANUP: "清理数据", BACKUP: "备份数据表", RESTORE: "恢复数据表" }

export function isSnapshotAction(action: Pick<CaseAction, "actionType">) {
  return action.actionType === "BACKUP" || action.actionType === "RESTORE"
}

export function isDatabaseWriteAction(action: Pick<CaseAction, "actionType">) {
  return ["INSERT", "UPDATE", "CLEANUP"].includes(action.actionType)
}

export const caseLabel = (testCase: Pick<TestCase, "caseCode" | "name">) => `${testCase.caseCode || "未分组"} · ${testCase.name}`

export const groupLabel = (group: Pick<CaseGroup, "name"> & { groupCode?: string; fullPath?: string }) => `${group.groupCode || ""} · ${group.fullPath || group.name}`

export function actionsOf(testCase: TestCase): CaseAction[] {
  return [...testCase.actions].sort((first, second) => first.sortNo - second.sortNo)
}

export function actionTarget(action: CaseAction) {
  if (action.actionType === "BACKUP" && action.snapshotRows?.length) return action.snapshotRows.map((item) => `${item.datasetName} · 快照 ${item.snapshotName}`).join("；")
  if (isSnapshotAction(action)) return `${action.datasetName} · 快照 ${action.snapshotName}`
  if (isDatabaseWriteAction(action) || action.actionType === "ASSERT") return action.datasetName || "未标明"
  if (action.actionType === "HTTP") { const request = JSON.parse(action.requestBody || "{}"); return `${request.connection} · ${request.path}` }
  if (action.actionType === "UPLOAD") return `${action.upload?.connection} · ${action.upload?.path}`
  return action.name
}

export function actionResult(action: CaseAction, execution?: Execution, end = false): ActionResult | undefined {
  if (!execution) return undefined
  const stepResult = execution.steps?.find((step) => step.sortNo === action.sortNo && !!step.caseEnd === end)
  if (action.actionType === "ASSERT") {
    const databaseAssertions = execution.assertions?.filter((item) => item.assertionType.startsWith("DATABASE ")) || []
    const assertion = databaseAssertions.find((item) => item.stepSortNo === action.sortNo && !!item.caseEnd === end)
    if (stepResult && assertion) return { ...stepResult, status: assertion.status, expectedValue: assertion.expectedValue, actualValue: assertion.actualValue, diffMessage: assertion.diffMessage, expectedTemplate: assertion.expectedTemplate, expectedPath: assertion.expectedPath, expectedDisplayValue: assertion.expectedDisplayValue, matchType: assertion.matchType, regexPattern: assertion.regexPattern }
    return assertion || stepResult
  }
  return stepResult
}

export function interfaceAssertions(action: CaseAction, execution?: Execution, end = false) {
  if (action.actionType !== "HTTP" && action.actionType !== "UPLOAD") return []
  return execution?.assertions?.filter((item) => item.assertionType.startsWith("INTERFACE ") && item.stepSortNo === action.sortNo && !!item.caseEnd === end) || []
}

export function variableDeclarations(actions: CaseAction[], sortNo: number) {
  const declarations: Record<string, unknown> = {}
  actions.filter((item) => item.sortNo <= sortNo).forEach((item) => {
    const source = item.upload?.responseVariablesJson || item.responseVariablesJson
    if (!source) return
    const parsed: unknown = JSON.parse(source)
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) Object.assign(declarations, parsed)
  })
  return declarations
}
