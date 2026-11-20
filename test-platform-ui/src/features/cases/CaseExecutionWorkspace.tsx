import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import type { ReactNode } from "react"
import { ArrowLeft, Download, ListChecks, Network, Play, Trash2, Upload } from "lucide-react"
import { toast } from "sonner"
import { ExecutionQueueInfo } from "@/components/shared/execution-queue-info"
import { StatusBadge } from "@/components/shared/status-badge"
import { BackupSummaryDialog, hasPendingRecovery } from "@/components/shared/backup-summary"
import { OverflowTooltip } from "@/components/shared/overflow-tooltip"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion"
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from "@/components/ui/resizable"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { AssertionComparisonForm, ComparisonForm, ResolvedResultTable, ResponseResult, ResultIcon, ResultTable } from "@/components/shared/result-comparison"
import { parseDisplayValue } from "@/components/shared/result-values"
import { CaseListPanel } from "@/features/cases/CaseListPanel"
import { BatchExecutionPanel } from "@/features/cases/BatchExecutionPanel"
import { WorkbookImportDialog } from "@/features/cases/CaseManagementDialogs"
import { CaseRelationsCanvas } from "@/features/cases/CaseRelationsCanvas"
import { actionLabels, actionResult, actionTarget, actionsOf, caseLabel, interfaceAssertions, isDatabaseWriteAction, isSnapshotAction } from "@/features/cases/case-utils"
import { api, formatTime, tokenKey } from "@/lib/platform-api"
import { cn } from "@/lib/utils"
import type { BatchRunItem, Bootstrap, CaseAction, CasePage, CaseQuery, Execution, ExecutionPage, Id, TestCase } from "@/types/platform"
import { ApplicationEnvironments, actionApplications, selectedEnvironments } from "@/features/cases/ApplicationEnvironments"

async function download(path: string, filename: string) {
  const token = localStorage.getItem(tokenKey)
  const response = await fetch(path, {
    headers: token ? { "X-Platform-Token": token } : undefined,
  })
  if (!response.ok) {
    const payload = await response.json()
    throw new Error(payload.message || "下载失败")
  }
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement("a")
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

function databaseTarget(action: CaseAction, data: Bootstrap) {
  if (!action.datasourceKey) return actionTarget(action)
  if (action.actionType === "BACKUP" && action.snapshotRows?.length) return `数据库：${action.snapshotRows.map((item) => item.datasetName).join("、")}`
  if (action.datasetName && (isDatabaseWriteAction(action) || action.actionType === "ASSERT" || isSnapshotAction(action))) return `数据库：${action.datasetName}`
  const datasource = data.datasources.find((item) => item.resourceKey === action.datasourceKey)
  return `数据库：${datasource?.name || action.datasourceKey}`
}

function ResultDetail({ action, execution, data, showRequest }: { action: CaseAction; execution?: Execution; data: Bootstrap; showRequest: boolean }) {
  const [activeResult, setActiveResult] = useState<"" | "ASSERTION" | "RESPONSE">("ASSERTION")
  const [end, setEnd] = useState(false)
  const caseEnd = !!action.runOnCaseEnd && end
  const result = actionResult(action, execution, caseEnd)
  const isInterfaceAction = action.actionType === "HTTP" || action.actionType === "UPLOAD"
  const assertions = isInterfaceAction ? interfaceAssertions(action, execution, caseEnd) : []
  const interfaceExpected = isInterfaceAction ? (parseDisplayValue(action.upload?.assertionsJson || action.assertionsJson) as { expected: unknown }).expected : undefined
  const emptyInterfaceAssertion = interfaceExpected !== null && typeof interfaceExpected === "object" && !Array.isArray(interfaceExpected) && Object.keys(interfaceExpected).length === 0
  const actualValue = parseDisplayValue(result?.actualValue)
  const noData = action.compareMode === "NOT_EXISTS" && actualValue === false
  const databaseExpected = action.compareMode === "ROW_COUNT" ? String(action.expectedRowCount)
    : action.compareMode === "NOT_EXISTS" ? "false" : action.expectedRowsJson
  const target = databaseTarget(action, data)
  const isDatabaseWrite = isDatabaseWriteAction(action)
  const insertVariables = action.actionType === "INSERT" ? JSON.parse(action.variablesJson || "{}") as {
    __platform_insert_rows?: Array<{ 记录号: string; 字段: string; 值: unknown }>
    __platform_parameterized_insert?: boolean
  } : undefined
  const insertFields = insertVariables?.__platform_insert_rows
  const resolvedInsertVariables: Record<string, unknown> | undefined = insertFields && result?.requestBody && result.resolvedVariablesJson
    ? JSON.parse(result.resolvedVariablesJson) : undefined
  const insertRows = new Map<string, Record<string, unknown>>()
  insertFields?.forEach((field, index) => {
    let row = insertRows.get(field.记录号)
    if (!row) {
      row = {}
      insertRows.set(field.记录号, row)
    }
    const value = field.值
    row[field.字段] = !resolvedInsertVariables ? value
      : insertVariables?.__platform_parameterized_insert ? resolvedInsertVariables[`data_${index}_${field.字段}`]
      : typeof value === "string" ? value.replace(/\{\{\s*([a-zA-Z0-9_.-]+)\s*}}/g, (_expression, key: string) => String(resolvedInsertVariables[key])) : value
  })
  const snapshotRows = action.snapshotRows?.length
    ? action.snapshotRows
    : action.datasetName && action.snapshotName
      ? [{ datasourceKey: action.datasourceKey || "", datasetName: action.datasetName, snapshotName: action.snapshotName, clearAfterBackup: action.clearAfterBackup, backupCondition: action.backupCondition }]
      : []
  return (
    <div className="flex min-w-0 flex-col gap-4">
      {action.runOnCaseEnd && <div className="flex gap-2">
        <Button size="sm" variant={end ? "outline" : "secondary"} onClick={() => setEnd(false)}>正常执行</Button>
        <Button size="sm" variant={end ? "secondary" : "outline"} onClick={() => setEnd(true)}>收尾执行</Button>
      </div>}
      <div className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
        <ResultIcon status={result?.status} />
        <Badge variant="outline">{actionLabels[action.actionType]}</Badge>
        <OverflowTooltip content={target} className="max-w-full truncate"><span className="block truncate text-xs text-muted-foreground">{target}</span></OverflowTooltip>
        <span className="text-xs text-muted-foreground">{result ? `耗时 ${result.durationMs || 0}ms` : "未执行"}</span>
      </div>
      {action.description && <section className="grid min-w-0 max-w-full gap-2 overflow-hidden rounded-md border bg-background p-3">
        <p className="text-sm font-medium">步骤说明</p>
        <p className="min-w-0 whitespace-pre-wrap [overflow-wrap:anywhere] text-sm leading-6">{action.description}</p>
      </section>}
      {insertRows.size > 0 && <section className="grid min-w-0 max-w-full gap-2 overflow-hidden rounded-md border bg-background p-3">
        <ResultTable label={`插入数据（${insertRows.size} 条） · ${resolvedInsertVariables ? "本次执行参数" : "配置值"}`} value={JSON.stringify([...insertRows.values()])} />
        {!insertVariables?.__platform_parameterized_insert && <p className="text-xs text-muted-foreground">数据库函数等 SQL 表达式保留原文。</p>}
      </section>}
      <Accordion type="single" collapsible className="min-w-0 rounded-md border bg-background px-3">
        <AccordionItem value="config" className="min-w-0">
          <AccordionTrigger className="py-1.5 hover:no-underline">步骤配置</AccordionTrigger>
          <AccordionContent className="grid min-w-0 gap-2 pb-2">
        {isSnapshotAction(action) && <>
          {snapshotRows.map((item, index) => (
            <ResultTable key={`${item.snapshotName}-${index}`} label={snapshotRows.length > 1 ? `备份表${index + 1}` : "备份表"}
              value={`${item.datasourceKey} / ${item.datasetName} / 快照 ${item.snapshotName}${action.actionType === "BACKUP" ? ` / 备份后清空：${item.clearAfterBackup ? "是" : "否"} / 备份条件：${item.backupCondition || "整表"}` : ""}`} />
          ))}
          <p className="text-xs text-muted-foreground">本案例结束时按备份条件恢复数据，同表同条件以最早成功快照为准；恢复成功后删除备份。</p>
        </>}
        {isInterfaceAction && <>
          {(action.actionType === "HTTP") && <ResolvedResultTable label="请求参数" template={action.requestBody} value={result?.requestBody} />}
          {action.actionType === "UPLOAD" && <>
            <ResultTable label="上传路径" value={action.upload?.path} />
            <ResultTable label="表单参数" value={action.upload?.formFieldsJson} />
          </>}
          {emptyInterfaceAssertion
            ? <p className="text-sm text-muted-foreground">未配置接口返回值断言</p>
            : <ResultTable label="期望断言" value={action.upload?.assertionsJson || action.assertionsJson} />}
        </>}
        {isDatabaseWrite && <>
          <ResultTable label="数据源" value={action.datasourceKey} />
          <ResolvedResultTable label="SQL" template={action.actionType === "CLEANUP" ? action.cleanupSql : action.prepareSql} value={result?.requestBody} />
        </>}
        {action.actionType === "ASSERT" && <>
          <ResolvedResultTable label="查询 SQL" template={action.querySql} value={result?.requestBody} />
          <ResolvedResultTable label="期望结果" template={databaseExpected} value={result?.expectedValue} />
        </>}
          </AccordionContent>
        </AccordionItem>
      </Accordion>
      {!result && <div className="rounded-md border border-dashed bg-background px-4 py-5 text-center text-sm text-muted-foreground">当前步骤尚未执行</div>}
      {result && <>
      {isInterfaceAction && (
        <>
          <AssertionComparisonForm assertions={assertions} expectedTemplate={action.upload?.assertionsJson || action.assertionsJson} open={showRequest ? undefined : activeResult === "ASSERTION"} onOpenChange={showRequest ? undefined : (open) => setActiveResult(open ? "ASSERTION" : "")} />
          <ResponseResult value={result.responseBody || result.errorMessage} emptyMessage={result.status === "PASSED" ? "接口调用完成，未返回响应正文" : "暂无响应结果"} open={showRequest ? undefined : activeResult === "RESPONSE"} onOpenChange={showRequest ? undefined : (open) => setActiveResult(open ? "RESPONSE" : "")} />
        </>
      )}
      {action.actionType === "ASSERT" && result.expectedValue != null && result.actualValue != null &&
        (noData ? (
          <div className="flex items-center gap-2 text-sm font-medium text-primary">
            <ResultIcon status={result.status} />
            <span>无数据</span>
          </div>
        ) : (
          <ComparisonForm label="数据库断言结果" expectedValue={result.expectedValue} actualValue={result.actualValue} expectedTemplate={result.expectedTemplate || databaseExpected} expectedDisplayValue={result.expectedDisplayValue} />
        ))}
      {(isDatabaseWrite || isSnapshotAction(action)) && (
        <>
          {result.responseBody && <ResultTable label="执行结果" value={result.responseBody} />}
        </>
      )}
      {showRequest && isInterfaceAction && <ResultTable label="请求参数" value={result.requestBody} />}
      {result.errorMessage && !isInterfaceAction && result.errorMessage !== result.diffMessage && (
        <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">
          {result.errorMessage}
        </p>
      )}
      {result.diffMessage && (
        <p role="alert" className={`rounded-md border px-3 py-2 text-sm ${result.status === "WARN" ? "border-amber-300 bg-amber-50 text-amber-800 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-300" : "border-destructive/30 bg-destructive/5 text-destructive"}`}>
          {result.diffMessage}
        </p>
      )}
      </>}
    </div>
  )
}

function StepDetailPage({ action, execution, data, onBack }: { action: CaseAction; execution?: Execution; data: Bootstrap; onBack: () => void }) {
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col gap-4 overflow-hidden">
      <div className="flex shrink-0 items-center gap-3">
        <Button variant="outline" onClick={onBack}>
          <ArrowLeft />
          返回案例
        </Button>
        <span className="text-sm text-muted-foreground">步骤详情</span>
      </div>
      <Card className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
        <CardContent className="flex min-h-0 flex-1 flex-col overflow-y-auto p-4">
          <ResultDetail action={action} execution={execution} data={data} showRequest />
        </CardContent>
      </Card>
    </div>
  )
}

function CaseDetailPanel({ selectedCase, data, execution, latest, focusedActionId, setFocusedActionId, selectedActionIds, setSelectedActionIds, setDetailAction, canRun, environmentSelector, running, run, downloadWorkbook, setDeletingCase }: {
  selectedCase: TestCase; data: Bootstrap; execution?: Execution; latest?: Execution;
  focusedActionId?: Id; setFocusedActionId: (id?: Id) => void;
  selectedActionIds: Id[]; setSelectedActionIds: (ids: Id[]) => void;
  setDetailAction: (action: CaseAction) => void;
  canRun?: (actions: CaseAction[]) => boolean; environmentSelector?: ReactNode; running?: "all" | Id;
  run?: (actionIds?: Id[]) => void; downloadWorkbook?: (path: string, filename: string) => void;
  setDeletingCase?: (testCase: TestCase) => void;
}) {
  const actions = actionsOf(selectedCase)
  const focusedAction = actions.find((item) => item.id === focusedActionId) || actions[0]
  const allActionsSelected = actions.length > 0 && selectedActionIds.length === actions.length
  const toggleAction = (actionId: Id, checked: boolean) => setSelectedActionIds(checked ? [...selectedActionIds, actionId] : selectedActionIds.filter((id) => id !== actionId))
  return <Card className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden 2xl:h-full">
              <CardHeader className="shrink-0 gap-3 border-b bg-muted/10">
                <div className="flex min-w-0 items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex min-w-0 items-center gap-2">
                      <OverflowTooltip content={caseLabel(selectedCase)} className="block max-w-full truncate"><CardTitle className="truncate text-base">{caseLabel(selectedCase)}</CardTitle></OverflowTooltip>
                      <Badge variant={selectedCase.enabled ? "secondary" : "outline"} className="shrink-0">{selectedCase.enabled ? "启用" : "停用"}</Badge>
                    </div>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {actions.length} 个步骤{selectedCase.groupName ? ` · ${selectedCase.groupName}` : " · 未分组"}{latest ? ` · 最近执行 ${formatTime(latest.finishedAt || latest.startedAt)}` : ""}
                    </p>
                    {execution?.steps && execution.steps.length > 0 && <p className="mt-1 text-xs text-muted-foreground">执行 #{execution.id} · 本次范围 {execution.steps.filter((step) => !step.caseEnd && step.status !== "NOT_SELECTED").length}/{execution.steps.filter((step) => !step.caseEnd).length} 步</p>}
                    {execution && <ExecutionQueueInfo execution={execution} />}
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <CardDescription tabIndex={0} className="mt-1 cursor-help truncate">{selectedCase.description || "暂无业务说明"}</CardDescription>
                      </TooltipTrigger>
                      <TooltipContent side="bottom" align="start" sideOffset={6} className="max-h-80 max-w-[min(48rem,calc(100vw-2rem))] overflow-y-auto whitespace-pre-wrap [overflow-wrap:anywhere] text-sm leading-6">
                        {selectedCase.description || "暂无业务说明"}
                      </TooltipContent>
                    </Tooltip>
                  </div>
                  <div className="flex shrink-0 flex-col items-end gap-2">
                    <div className="flex items-center gap-1">
                      <BackupSummaryDialog execution={execution} />
                      {downloadWorkbook && <Button variant="ghost" size="icon-sm" title="导出案例工作簿" onClick={() => downloadWorkbook(`/api/cases/${selectedCase.id}/workbook`, `${selectedCase.caseCode}-${selectedCase.name}.xlsx`.replace(/[\\/:*?"<>|]/g, "_"))}>
                        <Download />
                      </Button>}
                      {setDeletingCase && <Button variant="ghost" size="icon-sm" title="删除案例" onClick={() => setDeletingCase(selectedCase)}>
                        <Trash2 />
                      </Button>}
                    </div>
                    {run && <div className="flex flex-wrap items-end gap-2">
                      {environmentSelector}
                      <Button size="sm" disabled={!canRun?.(actions) || running !== undefined || !selectedCase.enabled} onClick={() => void run()}>
                        <Play />
                        执行案例
                      </Button>
                    </div>}
                  </div>
                </div>
              </CardHeader>
              <CardContent className="flex min-h-0 flex-1 flex-col p-0">
                <ResizablePanelGroup orientation="vertical" className="min-h-0 flex-1">
                  <ResizablePanel defaultSize={52} minSize={30}>
                    <div className="flex h-full min-h-0 flex-col">
                      <div className="shrink-0 border-b bg-muted/20 px-4 py-2.5">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <div><p className="text-sm font-semibold">案例步骤</p><p className="mt-0.5 text-xs text-muted-foreground">{run ? "勾选后按步骤号顺序执行；点击步骤行查看配置、响应与断言详情。" : "点击步骤行查看本次结果；点击步骤名称查看详情。"}</p></div>
                          {run && <div className="flex items-center gap-2"><span className="text-xs text-muted-foreground">已选 {selectedActionIds.length} 项</span><Button size="sm" disabled={!canRun?.(actions.filter((action) => action.id !== undefined && selectedActionIds.includes(action.id))) || running !== undefined || !selectedCase.enabled || selectedActionIds.length === 0} onClick={() => void run(selectedActionIds)}><Play />执行选中步骤</Button></div>}
                        </div>
                      </div>
                      <div className="min-h-0 flex-1 overflow-auto">
                        <Table className="min-w-[880px] table-fixed">
                    <TableHeader className="sticky top-0 z-10 bg-muted/40">
                      <TableRow>
                        <TableHead className="w-10 text-center"><Checkbox aria-label="全选步骤" checked={allActionsSelected} disabled={!run || running !== undefined} onCheckedChange={(checked) => setSelectedActionIds(checked ? actions.flatMap((action) => action.id === undefined ? [] : [action.id]) : [])} /></TableHead>
                        <TableHead className="w-10 text-center">步骤</TableHead>
                        <TableHead className="w-28">类型</TableHead>
                        <TableHead>操作对象</TableHead>
                        <TableHead className="w-36">目标表</TableHead>
                        <TableHead className="w-16 text-right">耗时</TableHead>
                        <TableHead className="w-24">执行结果</TableHead>
                        <TableHead className="w-16 text-right">操作</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {actions.map((action, index) => {
                        const result = actionResult(action, execution)
                        const endResult = action.runOnCaseEnd ? actionResult(action, execution, true) : undefined
                        return (
                          <TableRow
                            key={action.id || action.sortNo}
                            className={cn(
                              "cursor-pointer",
                              action.coreAssertion
                                ? focusedAction?.id === action.id
                                  ? "bg-amber-100 hover:bg-amber-100 dark:bg-amber-900/35 dark:hover:bg-amber-900/40"
                                  : "bg-amber-50 hover:bg-amber-100/80 dark:bg-amber-950/25 dark:hover:bg-amber-900/30"
                                : focusedAction?.id === action.id
                                  ? "bg-accent/40 hover:bg-accent/50"
                                  : "hover:bg-muted/30",
                            )}
                            onClick={() => setFocusedActionId(action.id)}
                          >
                            <TableCell className="text-center"><Checkbox aria-label={`选择步骤 ${index + 1}`} checked={action.id !== undefined && selectedActionIds.includes(action.id)} disabled={!run || !action.id || running !== undefined} onClick={(event) => event.stopPropagation()} onCheckedChange={(checked) => { if (action.id !== undefined) toggleAction(action.id, checked === true) }} /></TableCell>
                            <TableCell className="text-center font-mono text-xs text-muted-foreground">{index + 1}</TableCell>
                            <TableCell>
                              <Badge variant="outline" className="rounded-full px-2.5 font-normal">{actionLabels[action.actionType]}</Badge>
                              {action.runOnCaseEnd && <span className="mt-1 block text-xs text-muted-foreground">收尾复用</span>}
                            </TableCell>
                            <TableCell className="align-top">
                              <OverflowTooltip content={action.name} className="block max-w-full truncate">
                                <button
                                  type="button"
                                  className="block max-w-full truncate text-left text-sm font-medium text-primary hover:underline"
                                  title="查看步骤详情"
                                  onClick={(event) => {
                                    event.stopPropagation()
                                    setFocusedActionId(action.id)
                                    setDetailAction(action)
                                  }}
                                >
                                  {action.name}
                                </button>
                              </OverflowTooltip>
                            </TableCell>
                            <TableCell className="align-top">
                              {(action.datasetName || action.snapshotRows?.length) && (isDatabaseWriteAction(action) || action.actionType === "ASSERT" || isSnapshotAction(action)) ? (
                                <OverflowTooltip content={actionTarget(action)} className="block max-w-full truncate">
                                  <button type="button" className="block max-w-full truncate text-left font-mono text-xs text-primary hover:underline" title="查看步骤详情" onClick={(event) => { event.stopPropagation(); setFocusedActionId(action.id); setDetailAction(action) }}>{actionTarget(action)}</button>
                                </OverflowTooltip>
                              ) : <span className="text-muted-foreground">-</span>}
                            </TableCell>
                            <TableCell className="text-right font-mono text-xs tabular-nums text-muted-foreground">{result ? `${result.durationMs || 0}ms` : "-"}</TableCell>
                            <TableCell>
                              {result ? (
                                <div className="flex items-center gap-2">
                                  <ResultIcon status={result.status} />
                                  <StatusBadge status={result.status} />
                                </div>
                              ) : (
                                <span className="text-xs text-muted-foreground">未执行</span>
                              )}
                              {endResult && <div className="mt-1 flex items-center gap-1 text-xs text-muted-foreground">
                                <span>收尾</span><StatusBadge status={endResult.status} />
                              </div>}
                            </TableCell>
                            <TableCell>
                              {run && <div className="flex justify-end">
                                <Button
                                  variant="ghost"
                                  size="icon-sm"
                                  title="执行此步骤"
                                  disabled={!action.id || !canRun?.([action]) || running !== undefined}
                                  onClick={(event) => {
                                    event.stopPropagation()
                                    if (action.id !== undefined) void run([action.id])
                                  }}
                                >
                                  <Play />
                                </Button>
                              </div>}
                            </TableCell>
                          </TableRow>
                        )
                      })}
                      {actions.length === 0 && (
                        <TableRow>
                          <TableCell colSpan={8} className="py-10 text-center text-sm text-muted-foreground">
                            当前案例没有步骤
                          </TableCell>
                        </TableRow>
                      )}
                    </TableBody>
                        </Table>
                      </div>
                    </div>
                  </ResizablePanel>
                  <ResizableHandle withHandle />
                  <ResizablePanel defaultSize={48} minSize={20}>
                    <section className="flex h-full min-h-0 min-w-0 flex-col overflow-hidden bg-muted/20 px-4 py-3">
                      <div className="mb-3 flex shrink-0 items-center gap-2 border-b border-background/60 pb-2">
                        <span className="text-sm font-semibold">步骤结果</span>
                        {focusedAction && (
                          <>
                            <ResultIcon status={actionResult(focusedAction, execution)?.status} />
                            <OverflowTooltip content={focusedAction.name} className="max-w-full truncate"><span className="block truncate text-xs text-muted-foreground">{focusedAction.name}</span></OverflowTooltip>
                          </>
                        )}
                      </div>
                      {focusedAction ? <div className="min-h-0 flex-1 overflow-y-auto pr-1"><ResultDetail action={focusedAction} execution={execution} data={data} showRequest={false} /></div> : execution?.errorMessage ? <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">执行失败：{execution.errorMessage}</p> : <div className="rounded-md border border-dashed bg-background px-4 py-8 text-center text-sm text-muted-foreground">选择步骤查看比对结果</div>}
                    </section>
                  </ResizablePanel>
                </ResizablePanelGroup>
              </CardContent>
  </Card>
}

function BatchCaseDetail({ item, data, onBack }: { item: BatchRunItem; data: Bootstrap; onBack: () => void }) {
  const [testCase, setTestCase] = useState<TestCase>()
  const [execution, setExecution] = useState<Execution>()
  const [caseError, setCaseError] = useState("")
  const [executionError, setExecutionError] = useState("")
  const [focusedActionId, setFocusedActionId] = useState<Id>()
  const [detailAction, setDetailAction] = useState<CaseAction>()
  const [selectedActionIds, setSelectedActionIds] = useState<Id[]>([])
  useEffect(() => {
    let active = true
    void api<TestCase>(`/api/cases/${item.caseId}`).then((value) => { if (active) setTestCase(value) }).catch((error) => { if (active) setCaseError(error.message) })
    return () => { active = false }
  }, [item.caseId])
  useEffect(() => {
    if (item.executionId === undefined) return
    let active = true
    let timer: number | undefined
    const refresh = async () => {
      try {
        const value = await api<Execution>(`/api/executions/${item.executionId}`)
        if (!active) return
        setExecution(value)
        setExecutionError("")
        if (value.status === "QUEUED" || value.status === "RUNNING" || hasPendingRecovery(value)) timer = window.setTimeout(() => void refresh(), 1000)
      } catch (error) {
        if (active) {
          setExecutionError(error instanceof Error ? error.message : "读取执行详情失败")
          timer = window.setTimeout(() => void refresh(), 1000)
        }
      }
    }
    void refresh()
    return () => { active = false; if (timer !== undefined) window.clearTimeout(timer) }
  }, [item.executionId])
  const displayedCase = execution?.frozenCase || testCase
  if (detailAction && displayedCase) return <StepDetailPage action={detailAction} execution={execution} data={data} onBack={() => setDetailAction(undefined)} />
  return <div className="flex min-h-0 min-w-0 flex-1 flex-col gap-4 overflow-hidden">
    <div className="flex shrink-0 items-center gap-3"><Button variant="outline" onClick={onBack}><ArrowLeft />返回执行列表</Button><span className="text-sm text-muted-foreground">案例详情{item.executionId !== undefined ? ` · 执行编号 ${item.executionId}` : " · 尚未执行"}</span></div>
    {(caseError || executionError || item.errorMessage) && <p role="alert" className="text-sm text-destructive">{caseError || executionError || item.errorMessage}</p>}
    {displayedCase ? <CaseDetailPanel selectedCase={displayedCase} data={data} execution={execution} latest={execution} focusedActionId={focusedActionId} setFocusedActionId={setFocusedActionId} selectedActionIds={selectedActionIds} setSelectedActionIds={setSelectedActionIds} setDetailAction={setDetailAction} /> : <p className="text-sm text-muted-foreground">正在加载案例详情</p>}
  </div>
}

export function CaseExecutionWorkspace({ data, reload }: { data: Bootstrap; reload: () => Promise<void> }) {
  const [checkedCases, setCheckedCases] = useState<TestCase[]>([])
  const enabledCases = useMemo(() => checkedCases.filter((item) => item.enabled), [checkedCases])
  const [batchItems, setBatchItems] = useState<BatchRunItem[]>()
  const [batchExecutions, setBatchExecutions] = useState<Record<number, Execution>>({})
  const [batchSubmitting, setBatchSubmitting] = useState(false)
  const [batchError, setBatchError] = useState("")
  const [batchDetail, setBatchDetail] = useState<BatchRunItem>()
  const batchBusy = batchSubmitting || Boolean(batchItems?.some((item) => item.executionId !== undefined && (!batchExecutions[item.executionId] || ["QUEUED", "RUNNING"].includes(batchExecutions[item.executionId].status))))
  const batchMode = checkedCases.length >= 2
  const batchDetailItem = batchItems?.find((item) => item.caseId === batchDetail?.caseId) || batchDetail
  const [selectedId, setSelectedId] = useState<Id>()
  const selectedIdRef = useRef(selectedId)
  selectedIdRef.current = selectedId
  const [selectedDetails, setSelectedDetails] = useState<TestCase>()
  const [selectedDetailsReloadKey, setSelectedDetailsReloadKey] = useState(0)
  const [casePage, setCasePage] = useState<CasePage>({ items: [], page: 1, pageSize: 20, total: 0 })
  const [caseQuery, setCaseQuery] = useState<CaseQuery>({ keyword: "" })
  const [casePageLoading, setCasePageLoading] = useState(false)
  const casePageRequest = useRef(0)
  const [relationCases, setRelationCases] = useState<TestCase[]>([])
  const [leftView, setLeftView] = useState<"list" | "relations">("list")
  const [displayedExecutionId, setDisplayedExecutionId] = useState<Id>()
  const [executionDetails, setExecutionDetails] = useState<Record<number, Execution>>({})
  const [pollingExecutionId, setPollingExecutionId] = useState<Id>()
  const [focusedActionId, setFocusedActionId] = useState<Id>()
  const [detailAction, setDetailAction] = useState<CaseAction>()
  const [selectedActionIds, setSelectedActionIds] = useState<Id[]>([])
  const [running, setRunning] = useState<"all" | Id>()
  const [importOpen, setImportOpen] = useState(false)
  const [deletingCases, setDeletingCases] = useState<TestCase[]>([])
  const [removingCases, setRemovingCases] = useState(false)
  const [environmentSelections, setEnvironmentSelections] = useState<Record<string, Id>>({})
  const [batchApplications, setBatchApplications] = useState<string[]>()
  const selectedCase = selectedDetails?.id === selectedId ? selectedDetails : undefined
  const actions = selectedCase ? actionsOf(selectedCase) : []
  const applications = batchMode ? batchApplications || [] : actionApplications(selectedCase?.actions || [])
  const environmentIds = selectedEnvironments(applications, data.environments, environmentSelections)
  const canRun = applications.every((application) => environmentIds[application] !== undefined) && (!batchMode || batchApplications !== undefined && enabledCases.length > 0)
  const canRunActions = (chosen: CaseAction[]) => actionApplications(chosen).every((application) => environmentIds[application] !== undefined)
  const displayedExecution = displayedExecutionId === undefined ? undefined : executionDetails[displayedExecutionId]
  const executedCase = displayedExecution?.frozenCase
  // 导入更新会重新生成步骤 ID；基本信息或步骤变更后，当前案例按未执行展示。
  const execution = selectedCase && executedCase
    && selectedCase.id === executedCase.id && selectedCase.groupId === executedCase.groupId
    && selectedCase.caseNo === executedCase.caseNo && selectedCase.name === executedCase.name
    && selectedCase.description === executedCase.description && selectedCase.enabled === executedCase.enabled
    && selectedCase.actions.length === executedCase.actions.length
    && selectedCase.actions.every((action, index) => action.id === executedCase.actions[index].id)
    ? displayedExecution : undefined
  const changeCheckedCases = (cases: TestCase[]) => {
    setCheckedCases(cases)
    setBatchApplications(undefined)
    setBatchItems(undefined)
    setBatchExecutions({})
    setBatchError("")
  }
  const runBatch = async (parallelism: number) => {
    if (batchBusy || !batchMode || !canRun) return
    setBatchSubmitting(true)
    setBatchError("")
    setBatchItems(undefined)
    setBatchExecutions({})
    try {
      setBatchItems(await api<BatchRunItem[]>("/api/cases/run-batch", { method: "POST", body: JSON.stringify({ caseIds: enabledCases.map((item) => item.id), environmentIds, parallelism }) }))
    } catch (error) {
      setBatchError(error instanceof Error ? error.message : "批量提交失败")
    } finally {
      setBatchSubmitting(false)
    }
  }
  useEffect(() => {
    if (!batchItems) return
    let pendingIds = batchItems.flatMap((item) => item.executionId === undefined ? [] : [item.executionId])
    if (!pendingIds.length) return
    let active = true
    let timer: number | undefined
    const refresh = async () => {
      try {
        const values = await api<Execution[]>("/api/executions/status", { method: "POST", body: JSON.stringify({ executionIds: pendingIds }) })
        if (!active) return
        setBatchExecutions((current) => ({ ...current, ...Object.fromEntries(values.map((item) => [item.id, item])) }))
        setBatchError("")
        const finishedIds = new Set(values.filter((item) => item.status !== "QUEUED" && item.status !== "RUNNING").map((item) => item.id))
        pendingIds = pendingIds.filter((id) => !finishedIds.has(id))
        if (!pendingIds.length) { void reload(); return }
      } catch (error) {
        if (!active) return
        setBatchError(error instanceof Error ? error.message : "读取批量执行状态失败")
      }
      timer = window.setTimeout(() => void refresh(), 1000)
    }
    void refresh()
    return () => { active = false; if (timer !== undefined) window.clearTimeout(timer) }
  }, [batchItems, reload])
  const fetchCasePage = useCallback(async (page: number, pageSize: number, query: CaseQuery, background = false, signal?: AbortSignal) => {
    const request = background ? casePageRequest.current : ++casePageRequest.current
    const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
    if (query.keyword) params.set("keyword", query.keyword)
    if (query.groupId !== undefined) params.set("groupId", String(query.groupId))
    if (query.enabled !== undefined) params.set("enabled", String(query.enabled))
    for (const key of ["caseCode", "caseName", "executedFrom", "executedTo", "latestStatus", "sortBy", "sortDirection"] as const) {
      if (query[key]) params.set(key, query[key])
    }
    if (!background) setCasePageLoading(true)
    try {
      const value = await api<CasePage>(`/api/cases/page?${params.toString()}`, { signal })
      if (!signal?.aborted && request === casePageRequest.current) setCasePage(value)
    } catch (error) {
      if (!signal?.aborted && request === casePageRequest.current && !background) toast.error(error instanceof Error ? error.message : "查询案例失败")
    } finally {
      if (!background && request === casePageRequest.current) setCasePageLoading(false)
    }
  }, [])
  const refreshCasePage = useCallback(() => fetchCasePage(casePage.page, casePage.pageSize, caseQuery), [casePage.page, casePage.pageSize, caseQuery, fetchCasePage])
  useEffect(() => { void fetchCasePage(1, 20, { keyword: "" }) }, [fetchCasePage])
  useEffect(() => {
    if (leftView !== "list" || casePageLoading) return
    const controller = new AbortController()
    let timer: number | undefined
    let refreshing = false
    const refresh = async () => {
      if (refreshing) return
      refreshing = true
      if (!document.hidden) await fetchCasePage(casePage.page, casePage.pageSize, caseQuery, true, controller.signal)
      refreshing = false
      if (!controller.signal.aborted) timer = window.setTimeout(() => void refresh(), 1000)
    }
    const resume = () => {
      if (!document.hidden) {
        window.clearTimeout(timer)
        void refresh()
      }
    }
    timer = window.setTimeout(() => void refresh(), 1000)
    document.addEventListener("visibilitychange", resume)
    return () => {
      controller.abort()
      window.clearTimeout(timer)
      document.removeEventListener("visibilitychange", resume)
    }
  }, [casePage.page, casePage.pageSize, casePageLoading, caseQuery, fetchCasePage, leftView])
  useEffect(() => {
    if (leftView !== "relations") return
    let active = true
    void api<TestCase[]>("/api/cases/summaries").then((value) => {
      if (active) setRelationCases(value)
    }).catch((error) => {
      if (active) toast.error(error instanceof Error ? error.message : "加载案例关系失败")
    })
    return () => { active = false }
  }, [data, leftView])
  useEffect(() => {
    setDisplayedExecutionId(undefined)
    setPollingExecutionId(undefined)
    if (selectedId === undefined) {
      setSelectedDetails(undefined)
      return
    }
    let active = true
    setSelectedDetails(undefined)
    setSelectedActionIds([])
    setFocusedActionId(undefined)
    setDetailAction(undefined)
    void Promise.all([
      api<TestCase>(`/api/cases/${selectedId}`),
      api<ExecutionPage>(`/api/executions/page?caseId=${selectedId}&page=1&pageSize=1`),
    ]).then(([value, page]) => {
      if (!active) return
      setSelectedDetails(value)
      const latest = page.items[0]
      setDisplayedExecutionId(latest?.id)
      if (latest && (latest.status === "QUEUED" || latest.status === "RUNNING" || hasPendingRecovery(latest))) setPollingExecutionId(latest.id)
    }).catch((error) => {
      if (active) toast.error(error instanceof Error ? error.message : "加载案例详情失败")
    })
    return () => { active = false }
  }, [selectedId, selectedDetailsReloadKey])
  useEffect(() => {
    setBatchApplications(undefined)
    if (!batchMode || !enabledCases.length) return
    let active = true
    void api<string[]>("/api/cases/applications", { method: "POST", body: JSON.stringify(enabledCases.map((item) => item.id)) }).then((values) => {
      if (active) setBatchApplications(values)
    }).catch((error) => { if (active) setBatchError(error instanceof Error ? error.message : "加载案例环境失败") })
    return () => { active = false }
  }, [enabledCases, batchMode])
  useEffect(() => {
    if (displayedExecutionId === undefined || displayedExecutionId === pollingExecutionId || executionDetails[displayedExecutionId]) return
    let active = true
    void api<Execution>(`/api/executions/${displayedExecutionId}`).then((value) => {
      if (active) setExecutionDetails((current) => ({ ...current, [displayedExecutionId]: value }))
    }).catch((error) => {
      if (active) toast.error(error instanceof Error ? error.message : "读取执行详情失败")
    })
    return () => { active = false }
  }, [displayedExecutionId, pollingExecutionId, executionDetails])

  useEffect(() => {
    if (pollingExecutionId === undefined) return
    let active = true
    let timer: number | undefined

    const refresh = async () => {
      try {
        const value = await api<Execution>(`/api/executions/${pollingExecutionId}`)
        if (!active) return
        setExecutionDetails((current) => ({
          ...current,
          [pollingExecutionId]: value,
        }))

        if (value.status === "QUEUED" || value.status === "RUNNING" || hasPendingRecovery(value)) {
          timer = window.setTimeout(() => void refresh(), 1000)
          return
        }
        setPollingExecutionId((current) => current === pollingExecutionId ? undefined : current)
        if (displayedExecutionId === pollingExecutionId) void reload()
      } catch (error) {
        if (!active) return
        setPollingExecutionId((current) => current === pollingExecutionId ? undefined : current)
        toast.error(error instanceof Error ? error.message : "读取执行状态失败")
      }
    }

    void refresh()
    return () => {
      active = false
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [displayedExecutionId, pollingExecutionId, reload])

  const run = async (actionIds?: Id[]) => {
    if (!selectedCase?.id) return
    const chosen = actions.filter((action) => !actionIds || action.id !== undefined && actionIds.includes(action.id))
    if (!canRunActions(chosen)) return
    const available = new Set<string>()
    for (const action of chosen) {
      if (action.actionType === "BACKUP") {
        action.snapshotRows?.forEach((item) => available.add(item.snapshotName))
        if (action.snapshotName) available.add(action.snapshotName)
      }
      if (action.actionType === "RESTORE" && !available.has(action.snapshotName!)) {
        toast.error(`请同时选中快照 ${action.snapshotName} 的前置备份步骤`)
        return
      }
    }
    setRunning(actionIds?.length === 1 ? actionIds[0] : "all")
    setDisplayedExecutionId(undefined)
    setPollingExecutionId(undefined)
    try {
      const executionId = await api<Id>(`/api/cases/${selectedCase.id}/run`, {
        method: "POST",
        body: JSON.stringify({
          environmentIds,
          ...(actionIds?.length === 1 ? { actionId: actionIds[0] } : actionIds?.length ? { actionIds } : {}),
        }),
      })
      if (selectedIdRef.current === selectedCase.id) {
        setDisplayedExecutionId(executionId)
        setPollingExecutionId(executionId)
        if (actionIds?.length === 1) setFocusedActionId(actionIds[0])
      }
      toast.success(actionIds?.length ? "选中步骤已进入执行队列" : "案例已进入执行队列")
    } catch (error) {
      if (error instanceof Error && error.message.startsWith("案例步骤不存在：")) {
        if (selectedIdRef.current === selectedCase.id) {
          setSelectedDetailsReloadKey((current) => current + 1)
          toast.warning("案例步骤已更新，请重新选择")
        }
        return
      }
      toast.error(error instanceof Error ? error.message : "执行失败")
    } finally {
      setRunning(undefined)
    }
  }

  const downloadWorkbook = (path: string, filename: string) => void download(path, filename).catch((error) => toast.error(error instanceof Error ? error.message : "下载失败"))
  const removeCases = async () => {
    if (!deletingCases.length) return
    setRemovingCases(true)
    const caseIds = deletingCases.map((item) => item.id!)
    try {
      await api<void>("/api/cases/delete-batch", { method: "POST", body: JSON.stringify(caseIds) })
      if (selectedId !== undefined && caseIds.includes(selectedId)) {
        setSelectedId(undefined)
        setSelectedDetails(undefined)
      }
      changeCheckedCases(checkedCases.filter((item) => !caseIds.includes(item.id!)))
      setDeletingCases([])
      await Promise.all([reload(), refreshCasePage()])
      toast.success(`${caseIds.length} 条案例已移入回收站`)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "删除失败")
    } finally {
      setRemovingCases(false)
    }
  }
  const environmentSelector = (id: string) => <ApplicationEnvironments id={id} applications={applications} environments={data.environments} selected={environmentIds} disabled={batchBusy || running !== undefined} onChange={(application, value) => setEnvironmentSelections((current) => ({ ...current, [application]: value }))} />
  if (detailAction) return <StepDetailPage action={detailAction} execution={execution} data={data} onBack={() => setDetailAction(undefined)} />
  return (
    <>
    {batchDetailItem && <BatchCaseDetail key={batchDetailItem.caseId} item={batchDetailItem} data={data} onBack={() => setBatchDetail(undefined)} />}
    <div className={cn("flex min-h-0 min-w-0 flex-1 flex-col gap-4 overflow-hidden", batchDetail && "hidden")}>
      <div className="flex shrink-0 flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold">案例管理</h1>
          <p className="mt-1 text-sm text-muted-foreground">筛选案例，执行步骤并查看断言比对结果。</p>
        </div>
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="destructive" disabled={!checkedCases.length || batchBusy || running !== undefined || removingCases} onClick={() => setDeletingCases([...checkedCases])}>
            <Trash2 />
            删除选中案例{checkedCases.length ? `（${checkedCases.length}）` : ""}
          </Button>
          <Button variant="outline" onClick={() => downloadWorkbook("/api/case-workbook/template", "案例工作簿模板.xlsx")}>
            <Download />
            下载模板
          </Button>
          <Button variant="outline" onClick={() => setImportOpen(true)}>
            <Upload />
            导入工作簿
          </Button>
        </div>
      </div>
      <div className="grid min-h-0 min-w-0 flex-1 grid-rows-[minmax(0,0.85fr)_minmax(0,1.15fr)] gap-4 overflow-hidden 2xl:grid-cols-[minmax(22rem,0.85fr)_minmax(0,1.15fr)] 2xl:grid-rows-1">
        <div className="flex min-h-0 min-w-0 flex-col gap-2 overflow-hidden">
          <div className="flex shrink-0 items-center justify-between gap-2">
            <p className="text-sm font-semibold">案例浏览</p>
            <div className="flex items-center rounded-md border bg-muted/30 p-1" role="group" aria-label="案例浏览方式">
              <Button type="button" variant={leftView === "list" ? "secondary" : "ghost"} size="sm" className="h-7 gap-1.5" aria-pressed={leftView === "list"} onClick={() => setLeftView("list")}>
                <ListChecks />
                列表
              </Button>
              <Button type="button" variant={leftView === "relations" ? "secondary" : "ghost"} size="sm" className="h-7 gap-1.5" aria-pressed={leftView === "relations"} onClick={() => setLeftView("relations")}>
                <Network />
                关系图
              </Button>
            </div>
          </div>
          <div className="flex min-h-0 min-w-0 flex-1 flex-col">
            {leftView === "relations" ? <CaseRelationsCanvas groups={data.groups} cases={relationCases} selectedCaseId={selectedId} onSelectCase={setSelectedId} /> : <CaseListPanel casePage={casePage} groups={data.groups} selectedCaseId={selectedId} loading={casePageLoading} checkedCases={checkedCases} selectionLocked={batchBusy} onCheckedCasesChange={changeCheckedCases} onSelect={setSelectedId} onQuery={(query) => { setCaseQuery(query); void fetchCasePage(1, casePage.pageSize, query) }} onPageChange={(page) => void fetchCasePage(page, casePage.pageSize, caseQuery)} onPageSizeChange={(pageSize) => void fetchCasePage(1, pageSize, caseQuery)} />}
          </div>
        </div>
        {batchMode && <BatchExecutionPanel cases={checkedCases} items={batchItems} executions={batchExecutions} busy={batchBusy} submitting={batchSubmitting} maxParallelism={data.executionParallelism} environmentSelector={environmentSelector("batch-case-environment")} canRun={canRun && running === undefined} errorMessage={batchError} onRun={(parallelism) => void runBatch(parallelism)} onDetail={setBatchDetail} />}
        <div className={cn("flex min-h-0 min-w-0 flex-col overflow-hidden 2xl:h-full", batchMode && "hidden")}>
          {!selectedCase ? (
            <Card className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
            <CardContent className="grid min-h-0 place-items-center p-8 text-center flex-1">
              <div className="grid justify-items-center gap-3 text-muted-foreground">
                <ListChecks size={30} />
                <p className="text-sm">{selectedId === undefined ? "从左侧列表或关系图选择一个案例" : "正在加载案例详情"}</p>
              </div>
            </CardContent>
            </Card>
          ) : (
            <CaseDetailPanel selectedCase={selectedCase} data={data} execution={execution} latest={execution} focusedActionId={focusedActionId} setFocusedActionId={setFocusedActionId} selectedActionIds={selectedActionIds} setSelectedActionIds={setSelectedActionIds} setDetailAction={setDetailAction} canRun={canRunActions} environmentSelector={environmentSelector("case-environment")} running={running} run={run} downloadWorkbook={downloadWorkbook} setDeletingCase={(item) => setDeletingCases([item])} />
          )}
        </div>
      </div>
      <WorkbookImportDialog
        open={importOpen}
        groups={data.groups}
        onOpenChange={setImportOpen}
        onImported={async (caseIds) => {
          setSelectedDetails(undefined)
          await reload()
          await refreshCasePage()
          setSelectedId(caseIds[caseIds.length - 1])
          setSelectedDetailsReloadKey((current) => current + 1)
        }}
      />
      <AlertDialog open={deletingCases.length > 0} onOpenChange={(open) => { if (!open && !removingCases) setDeletingCases([]) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除{deletingCases.length > 1 ? ` ${deletingCases.length} 条` : ""}案例</AlertDialogTitle>
            <AlertDialogDescription>{deletingCases.length === 1 ? `“${deletingCases[0].name}”将移入回收站，可在回收站恢复。` : `选中的 ${deletingCases.length} 条案例将全部移入回收站，可在回收站恢复。`}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={removingCases}>取消</AlertDialogCancel>
            <AlertDialogAction variant="destructive" disabled={removingCases} onClick={(event) => { event.preventDefault(); void removeCases() }}>{removingCases ? "删除中" : "移入回收站"}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
    </>
  )
}
