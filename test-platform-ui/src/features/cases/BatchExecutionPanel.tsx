import { useState, type ReactNode } from "react"
import { Play } from "lucide-react"
import { ExecutionQueueInfo } from "@/components/shared/execution-queue-info"
import { StatusBadge } from "@/components/shared/status-badge"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { formatTime } from "@/lib/platform-api"
import type { BatchRunItem, Execution, TestCase } from "@/types/platform"

export function BatchExecutionPanel({ cases, items, executions, busy, submitting, maxParallelism, environmentSelector, canRun, errorMessage, onRun, onDetail }: {
  cases: TestCase[]; items?: BatchRunItem[]; executions: Record<number, Execution>;
  busy: boolean; submitting: boolean; maxParallelism: number; environmentSelector: ReactNode; canRun: boolean; errorMessage: string;
  onRun: (parallelism: number) => void; onDetail: (item: BatchRunItem) => void;
}) {
  const [parallelismInput, setParallelismInput] = useState(String(maxParallelism))
  const parallelism = Number(parallelismInput)
  const validParallelism = Number.isInteger(parallelism) && parallelism >= 2 && parallelism <= maxParallelism
  const skipped = cases.filter((item) => !item.enabled).length
  const queued = items?.filter((item) => item.executionId !== undefined && executions[item.executionId]?.status === "QUEUED").length || 0
  const running = items?.filter((item) => item.executionId !== undefined && executions[item.executionId]?.status === "RUNNING").length || 0
  const finished = items?.filter((item) => item.errorMessage || (item.executionId !== undefined && executions[item.executionId] && !["QUEUED", "RUNNING"].includes(executions[item.executionId].status))).length || 0
  const syncing = items?.filter((item) => item.executionId !== undefined && !executions[item.executionId]).length || 0
  return <Card className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
    <CardHeader className="shrink-0 border-b">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <CardTitle className="text-base">执行列表 · {cases.length} 条</CardTitle>
        <div className="flex min-w-0 flex-1 flex-col gap-3 sm:items-end">
          {environmentSelector}
          <div className="flex w-full flex-wrap items-center justify-end gap-3">
            <Button size="sm" variant="outline" disabled={busy || !canRun} onClick={() => onRun(1)}><Play />串行执行</Button>
            <div className="flex items-center gap-2 border-l pl-3">
              <label className="flex items-center gap-2 whitespace-nowrap text-xs">并发数<Input className="h-8 w-16" aria-label="本批次并发数" type="number" min={2} max={maxParallelism} step={1} value={parallelismInput} disabled={busy || maxParallelism < 2} onChange={(event) => setParallelismInput(event.target.value)} /></label>
              <Button size="sm" disabled={busy || !canRun || !validParallelism} onClick={() => onRun(parallelism)}><Play />并行执行</Button>
            </div>
          </div>
        </div>
      </div>
      {skipped > 0 && <p className="text-xs text-muted-foreground">将执行 {cases.length - skipped} 条，跳过 {skipped} 条停用案例。</p>}
      <p className="text-xs text-muted-foreground">串行按列表顺序逐条完成；并发数仅限制本批次，上限 {maxParallelism}。其他批次可同时运行，同案例同环境按提交顺序等待。</p>
      <p role="status" className="text-xs tabular-nums text-muted-foreground">{submitting ? "正在准备并提交批次…" : items ? `排队 ${queued} · 执行中 ${running} · 已结束 ${finished}${syncing ? ` · 状态同步中 ${syncing}` : ""} · 每秒刷新` : "尚未提交"}</p>
      {errorMessage && <p role="alert" className="text-sm text-destructive">{errorMessage}</p>}
    </CardHeader>
    <CardContent className="min-h-0 flex-1 overflow-auto p-0">
      <Table>
        <TableHeader><TableRow><TableHead>案例</TableHead><TableHead>本次状态</TableHead><TableHead>开始时间</TableHead><TableHead>耗时</TableHead><TableHead>失败原因</TableHead><TableHead>操作</TableHead></TableRow></TableHeader>
        <TableBody>{cases.map((testCase) => {
          const item = items?.find((value) => value.caseId === testCase.id)
          const execution = item?.executionId === undefined ? undefined : executions[item.executionId]
          const status = item?.errorMessage ? "FAILED" : execution?.status || (item?.executionId !== undefined ? "QUEUED" : "PENDING")
          const duration = execution?.startedAt && execution.finishedAt ? new Date(execution.finishedAt).getTime() - new Date(execution.startedAt).getTime() : undefined
          return <TableRow key={testCase.id}>
            <TableCell><p className="font-mono text-xs text-muted-foreground">{testCase.caseCode}</p><p className="text-sm font-medium">{testCase.name}</p></TableCell>
            <TableCell>{!testCase.enabled ? <Badge variant="outline">已停用 · 跳过</Badge> : !execution && item?.executionId !== undefined ? <span className="text-xs text-muted-foreground">状态同步中</span> : <StatusBadge status={status} />}{execution && <ExecutionQueueInfo execution={execution} />}</TableCell>
            <TableCell className="text-xs">{formatTime(execution?.startedAt)}</TableCell>
            <TableCell className="text-xs tabular-nums">{duration === undefined ? "-" : `${duration}ms`}</TableCell>
            <TableCell className="max-w-64 whitespace-normal break-words text-xs text-destructive">{item?.errorMessage || execution?.errorMessage || "-"}</TableCell>
            <TableCell><Button variant="link" size="sm" onClick={() => onDetail(item || { caseId: testCase.id! })}>详情</Button></TableCell>
          </TableRow>
        })}</TableBody>
      </Table>
    </CardContent>
  </Card>
}
