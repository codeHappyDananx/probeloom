import { useEffect, useState } from "react"
import type { ColumnDef } from "@tanstack/react-table"
import { RefreshCw, Search } from "lucide-react"
import { toast } from "sonner"
import { AssertionComparisonForm, ComparisonForm, ResponseResult, ResultTable } from "@/components/shared/result-comparison"
import { ExecutionQueueInfo } from "@/components/shared/execution-queue-info"
import { Checkbox } from "@/components/ui/checkbox"
import { StatusBadge } from "@/components/shared/status-badge"
import { BackupSummary, EndExecutionButton, hasPendingRecovery } from "@/components/shared/backup-summary"
import { OverflowTooltip } from "@/components/shared/overflow-tooltip"
import { Button } from "@/components/ui/button"
import { DataTable } from "@/components/ui/data-table"
import { DataTableColumnHeader } from "@/components/ui/data-table-column-header"
import type { DataTableFeatures } from "@/components/ui/data-table-features"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { actionsOf, caseLabel, groupLabel } from "@/features/cases/case-utils"
import { api, formatTime, statusLabel } from "@/lib/platform-api"
import type { Bootstrap, Execution, ExecutionPage, Id } from "@/types/platform"

function ExecutionDetail({ detail, data, initialStepId }: { detail: Execution; data: Bootstrap; initialStepId?: Id }) {
  const [stepId, setStepId] = useState<Id | undefined>(initialStepId ?? detail.steps?.[0]?.id)
  const step = detail.steps?.find((item) => item.id === stepId) || detail.steps?.[0]
  const stepAssertions = detail.assertions?.filter((item) => step && item.stepSortNo === step.sortNo && !!item.caseEnd === !!step.caseEnd) || []
  const interfaceResults = stepAssertions.filter((item) => item.assertionType.startsWith("INTERFACE "))
  const databaseResults = stepAssertions.filter((item) => item.assertionType.startsWith("DATABASE "))
  const testCase = detail.frozenCase || data.cases.find((item) => item.id === detail.caseId)
  const actions = testCase ? actionsOf(testCase) : []

  // 统一提示信息
  const hasSkipped = detail.steps?.some(step => step.status === "SKIPPED")
  const hasMessage = !!detail.message

  return (
    <Tabs defaultValue={hasPendingRecovery(detail) ? "backups" : "steps"} className="grid min-h-0 grid-rows-[auto_minmax(0,1fr)]">
      <TabsList className="mb-4">
        <TabsTrigger value="steps">执行步骤</TabsTrigger>
        <TabsTrigger value="assertions">断言结果</TabsTrigger>
        {(!!detail.backups?.length || ["RUNNING", "QUEUED"].includes(detail.status) || hasPendingRecovery(detail)) && <TabsTrigger value="backups">备份与收尾</TabsTrigger>}
      </TabsList>
      <TabsContent value="backups" className="min-h-0 overflow-auto"><BackupSummary execution={detail} /></TabsContent>
      
      <TabsContent value="steps" className="min-h-0 min-w-0 space-y-4 overflow-hidden">
        {/* 统一提示区域 */}
        {(hasMessage || hasSkipped) && (
          <div className="rounded-lg border border-amber-200 bg-gradient-to-r from-amber-50 to-orange-50 p-4">
            <div className="flex items-start gap-3">
              <div className="flex-shrink-0 text-amber-600 text-lg">⚠️</div>
              <div className="flex-1 space-y-1">
                {hasMessage && (
                  <p className="text-sm font-medium text-amber-800">{detail.message}</p>
                )}
                {hasSkipped && (
                  <p className="text-sm text-amber-700">
                    部分步骤因前面步骤失败而正常跳过，这是预期行为
                  </p>
                )}
              </div>
            </div>
          </div>
        )}

        {/* 步骤列表和详情区域 */}
        <div className="grid min-h-0 min-w-0 grid-cols-1 gap-4 lg:grid-cols-2">
          {/* 步骤列表 */}
          <div className="min-h-0 min-w-0 space-y-2">
            <h3 className="text-sm font-semibold text-gray-900 mb-3">执行步骤</h3>
            <div className="border rounded-lg overflow-hidden bg-white">
              <div className="max-h-96 overflow-y-auto">
                <table className="w-full">
                  <thead className="bg-gray-50 border-b">
                    <tr>
                      <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">步骤</th>
                      <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">状态</th>
                      <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">耗时</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-200">
                    {detail.steps?.map((item) => (
                      <tr 
                        key={item.id} 
                        className={`hover:bg-gray-50 cursor-pointer transition-colors ${
                          item.id === step?.id ? 'bg-blue-50 border-l-4 border-blue-500' : ''
                        }`}
                        onClick={() => setStepId(item.id)}
                      >
                        <td className="px-4 py-3">
                          <span className="text-sm font-medium text-gray-900">
                            步骤 {item.sortNo + 1}{item.caseEnd ? " · 收尾" : ""} {actions.find((action) => action.sortNo === item.sortNo)?.name || ""}
                          </span>
                        </td>
                        <td className="px-4 py-3">
                          <StatusBadge status={item.status} />
                        </td>
                        <td className="px-4 py-3">
                          <span className="text-sm text-gray-500">
                            {item.durationMs || 0}ms
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          {/* 步骤详情 */}
          <div className="flex min-h-0 min-w-0 flex-col space-y-2">
            <h3 className="text-sm font-semibold text-gray-900 mb-3">步骤详情</h3>
            <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden rounded-lg border bg-white">
              {step ? (
                <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-4">
                  <ResultTable label="请求参数" value={step.requestBody} />
                  <ResponseResult value={step.responseBody || step.errorMessage} />
                  {step.errorMessage && (
                    <div className="rounded-md bg-red-50 border border-red-200 p-3">
                      <p className="text-sm text-red-800">{step.errorMessage}</p>
                    </div>
                  )}
                </div>
              ) : (
                <div className="flex items-center justify-center h-64 text-gray-500">
                  <p className="text-sm">请选择一个步骤查看详情</p>
                </div>
              )}
            </div>
          </div>
        </div>
      </TabsContent>
      
      <TabsContent value="assertions" className="space-y-4">
        <Select value={step ? String(step.id) : ""} onValueChange={(value) => setStepId(Number(value))}>
          <SelectTrigger aria-label="断言步骤" className="w-52"><SelectValue placeholder="选择步骤" /></SelectTrigger>
          <SelectContent>
            {detail.steps?.map((item) => <SelectItem key={item.id} value={String(item.id)}>步骤 {item.sortNo + 1}{item.caseEnd ? " · 收尾" : ""} {actions.find((action) => action.sortNo === item.sortNo)?.name || ""}</SelectItem>)}
          </SelectContent>
        </Select>
        <div className="border rounded-lg bg-white p-4">
          <div className="space-y-4">
            <AssertionComparisonForm assertions={interfaceResults} expectedTemplate={step ? (actions.find((item) => item.sortNo === step.sortNo)?.upload?.assertionsJson || actions.find((item) => item.sortNo === step.sortNo)?.assertionsJson) : undefined} />
            {databaseResults.length === 0 ? (
              <div className="text-center py-8 text-gray-500">
                <p className="text-sm">没有数据库断言结果</p>
              </div>
            ) : (
              databaseResults.map((assertion) => (
                <section key={assertion.id} className="border rounded-lg p-4 bg-gray-50">
                  <div className="flex items-center justify-between mb-3">
                    <h4 className="font-medium text-gray-900">
                      {assertion.assertionType.replace(/^DATABASE\s+/, "")}
                    </h4>
                    <StatusBadge status={assertion.status} />
                  </div>
                  <ComparisonForm 
                    label="数据库断言结果" 
                    expectedValue={assertion.expectedValue} 
                    actualValue={assertion.actualValue} 
                    expectedTemplate={assertion.expectedTemplate || (step ? actions.find((item) => item.sortNo === step.sortNo)?.expectedRowsJson : undefined)}
                    expectedDisplayValue={assertion.expectedDisplayValue}
                    matchType={assertion.matchType}
                    regexPattern={assertion.regexPattern}
                  />
                  {assertion.diffMessage && (
                    <div className="mt-3 rounded-md bg-red-50 border border-red-200 p-3">
                      <p className="text-sm text-red-800">{assertion.diffMessage}</p>
                    </div>
                  )}
                </section>
              ))
            )}
          </div>
        </div>
      </TabsContent>
    </Tabs>
  )
}

export function ExecutionResultDialog({ detail, data, open, onOpenChange, initialStepId }: {
  detail?: Execution; data: Bootstrap; open: boolean; onOpenChange: (open: boolean) => void; initialStepId?: Id
}) {
  return <Dialog open={open} onOpenChange={onOpenChange}><DialogContent className="grid max-h-[calc(100dvh-2rem)] min-h-0 sm:max-w-5xl grid-rows-[auto_minmax(0,1fr)] overflow-hidden"><DialogHeader><DialogTitle>执行编号 {detail?.id}{detail?.frozenCase ? ` · ${caseLabel(detail.frozenCase)}` : ""}</DialogTitle><DialogDescription>{detail ? statusLabel(detail.status) : ""}{detail?.errorMessage ? ` - ${detail.errorMessage}` : ""}</DialogDescription>{detail && <ExecutionQueueInfo execution={detail} />}</DialogHeader>{detail && <ExecutionDetail key={`${detail.id}:${initialStepId ?? ""}`} detail={detail} data={data} initialStepId={initialStepId} />}</DialogContent></Dialog>
}

function executionTargetLabel(execution: Execution, data: Bootstrap) {
  const testCase = execution.frozenCase || data.cases.find((item) => item.id === execution.caseId)
  if (testCase) return caseLabel(testCase)
  const group = data.groups.find((item) => item.id === execution.groupId)
  return group ? groupLabel(group) : "分组执行"
}

const endExecutionColumn: ColumnDef<DataTableFeatures, Execution> = {
  id: "end", header: "操作", cell: ({ row }) => <EndExecutionButton key={row.original.id} execution={row.original} />,
}

export function ExecutionsPage({ data }: { data: Bootstrap; reload: () => void }) {
  const [detail, setDetail] = useState<Execution>()
  const [open, setOpen] = useState(false)
  const [keyword, setKeyword] = useState("")
  const [status, setStatus] = useState("ALL")
  const [trigger, setTrigger] = useState("ALL")
  const [caseId, setCaseId] = useState("ALL")
  const [environmentId, setEnvironmentId] = useState("ALL")
  const [page, setPage] = useState(1)
  const [mine, setMine] = useState(false)
  const [recoveryPending, setRecoveryPending] = useState(false)
  const [executionPage, setExecutionPage] = useState<ExecutionPage>({ items: [], page: 1, pageSize: 20, total: 0 })
  const [refreshKey, setRefreshKey] = useState(0)
  const statuses = ["QUEUED", "RUNNING", "PASSED", "WARN", "FAILED", "INTERRUPTED"]
  useEffect(() => {
    let active = true
    let timer: number | undefined
    const refresh = async () => {
      const params = new URLSearchParams({ page: String(page), pageSize: "20", mine: String(mine) })
      if (keyword.trim()) params.set("keyword", keyword.trim())
      if (recoveryPending) params.set("recoveryPending", "true")
      else if (status !== "ALL") params.set("status", status)
      if (trigger !== "ALL") params.set("triggerType", trigger)
      if (caseId !== "ALL") params.set("caseId", caseId)
      if (environmentId !== "ALL") params.set("environmentId", environmentId)
      try { const next = await api<ExecutionPage>(`/api/executions/page?${params}`); if (active) setExecutionPage(next) }
      catch (error) { if (active && timer === undefined) toast.error(error instanceof Error ? error.message : "加载执行记录失败") }
      if (active) timer = window.setTimeout(() => void refresh(), 1000)
    }
    void refresh()
    return () => { active = false; window.clearTimeout(timer) }
  }, [page, mine, recoveryPending, refreshKey, keyword, status, trigger, caseId, environmentId])
  useEffect(() => { setPage(1) }, [mine, recoveryPending, keyword, status, trigger, caseId, environmentId])
  const detailId = detail?.id
  useEffect(() => {
    if (!open || detailId === undefined) return
    let active = true
    let timer: number | undefined
    const refresh = async () => {
      try {
        const value = await api<Execution>(`/api/executions/${detailId}`)
        if (!active) return
        setDetail(value)
        if (["QUEUED", "RUNNING"].includes(value.status) || hasPendingRecovery(value)) timer = window.setTimeout(() => void refresh(), 1000)
      } catch (error) { if (active) toast.error(error instanceof Error ? error.message : "读取执行详情失败") }
    }
    void refresh()
    return () => { active = false; window.clearTimeout(timer) }
  }, [open, detailId])
  const rows = executionPage.items
  const select = async (id: Id) => {
    try {
      setDetail(await api<Execution>(`/api/executions/${id}`))
      setOpen(true)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "加载失败")
    }
  }
  const columns: ColumnDef<DataTableFeatures, Execution>[] = [
    { id: "case", accessorFn: (row) => executionTargetLabel(row, data), meta: { label: "案例" }, header: ({ column }) => <DataTableColumnHeader column={column} title="案例" />, cell: ({ row }) => <span className="font-medium">{executionTargetLabel(row.original, data)}</span> },
    { id: "environment", accessorFn: (row) => data.environments.find((value) => value.id === row.environmentId)?.name || "", meta: { label: "环境" }, header: ({ column }) => <DataTableColumnHeader column={column} title="环境" />, cell: ({ row }) => data.environments.find((value) => value.id === row.original.environmentId)?.name || "-" },
    { accessorKey: "triggerType", meta: { label: "来源" }, header: ({ column }) => <DataTableColumnHeader column={column} title="来源" />, cell: ({ row }) => row.original.triggerType === "SCHEDULE" ? "定时" : "手工" },
    { accessorKey: "submittedByName", meta: { label: "提交人" }, header: "提交人", cell: ({ row }) => row.original.submittedByName || "-" },
    { accessorKey: "queuedAt", meta: { label: "入队时间" }, header: "入队时间", cell: ({ row }) => formatTime(row.original.queuedAt) },
    { accessorKey: "status", meta: { label: "状态" }, header: ({ column }) => <DataTableColumnHeader column={column} title="状态" />, cell: ({ row }) => <div><StatusBadge status={row.original.status} />{hasPendingRecovery(row.original) && <span className="ml-2 text-xs text-amber-700 dark:text-amber-400">数据待恢复</span>}<ExecutionQueueInfo execution={row.original} /></div> },
    { id: "recovery", header: "数据恢复", cell: ({ row }) => hasPendingRecovery(row.original) ? <Button size="sm" variant="outline" onClick={(event) => { event.stopPropagation(); void select(row.original.id) }}>查看并恢复</Button> : row.original.finalizationPhase === "COMPLETE" ? <span className="text-xs text-muted-foreground">收尾完成</span> : null },
    { accessorKey: "startedAt", meta: { label: "开始时间" }, header: ({ column }) => <DataTableColumnHeader column={column} title="开始时间" />, cell: ({ row }) => formatTime(row.original.startedAt) },
    endExecutionColumn,
    { accessorKey: "errorMessage", meta: { label: "错误信息" }, header: ({ column }) => <DataTableColumnHeader column={column} title="错误信息" />, cell: ({ row }) => <OverflowTooltip content={row.original.errorMessage || "-"} className="block max-w-72"><span className="block truncate text-muted-foreground">{row.original.errorMessage || "-"}</span></OverflowTooltip> },
  ]

  return <div className="flex min-h-0 min-w-0 flex-1 flex-col gap-4">
    <div className="flex shrink-0 flex-wrap items-end justify-between gap-3"><div><h1 className="text-lg font-semibold">执行记录</h1><p className="mt-1 text-sm text-muted-foreground">保留接口响应、断言结果和字段差异。</p></div><Button variant="outline" onClick={() => setRefreshKey((value) => value + 1)}><RefreshCw />刷新</Button></div>
    <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden"><DataTable columns={columns} data={rows} pagination={{ page, pageSize: executionPage.pageSize, total: executionPage.total, onPageChange: setPage }} emptyMessage="暂无匹配的执行记录" onRowClick={(row) => void select(row.id)} toolbar={() => <div className="flex min-w-0 flex-1 flex-wrap gap-2"><label className="flex items-center gap-2 text-sm"><Checkbox checked={mine} onCheckedChange={(checked) => setMine(checked === true)} />仅本人</label><label className="flex items-center gap-2 text-sm"><Checkbox checked={recoveryPending} onCheckedChange={(checked) => setRecoveryPending(checked === true)} />待恢复</label><InputGroup className="min-w-56 flex-1"><InputGroupInput aria-label="检索执行记录" placeholder="搜索案例、状态或错误信息" value={keyword} onChange={(event) => setKeyword(event.target.value)} /><InputGroupAddon><Search /></InputGroupAddon></InputGroup><Select value={status} onValueChange={setStatus}><SelectTrigger className="w-32"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="ALL">全部状态</SelectItem>{statuses.map((item) => <SelectItem key={item} value={item}>{statusLabel(item)}</SelectItem>)}</SelectContent></Select><Select value={trigger} onValueChange={setTrigger}><SelectTrigger className="w-28"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="ALL">全部来源</SelectItem><SelectItem value="MANUAL">手工</SelectItem><SelectItem value="SCHEDULE">定时</SelectItem></SelectContent></Select><Select value={caseId} onValueChange={setCaseId}><SelectTrigger className="w-52"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="ALL">全部案例</SelectItem>{data.cases.map((item) => <SelectItem key={item.id} value={String(item.id)}>{caseLabel(item)}</SelectItem>)}</SelectContent></Select><Select value={environmentId} onValueChange={setEnvironmentId}><SelectTrigger className="w-36"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="ALL">全部环境</SelectItem>{data.environments.map((item) => <SelectItem key={item.id} value={String(item.id)}>{item.name}</SelectItem>)}</SelectContent></Select></div>} /></div>
    <ExecutionResultDialog detail={detail} data={data} open={open} onOpenChange={setOpen} />
  </div>
}
