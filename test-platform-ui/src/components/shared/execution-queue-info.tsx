import { formatTime } from "@/lib/platform-api"
import type { Execution } from "@/types/platform"

const waitLabels = { CAPACITY: "等待并发容量", CASE: "等待同案例执行完成", BATCH: "等待本批次前序或并发名额" }

export function ExecutionQueueInfo({ execution }: { execution: Execution }) {
  return <div className="mt-1 space-y-0.5 whitespace-normal text-xs text-muted-foreground">
    <p>执行 #{execution.id} · 提交人 {execution.submittedByName || "-"} · 入队 {formatTime(execution.queuedAt)}</p>
    {execution.batchId != null && <p>批次 #{execution.batchId} · 第 {execution.batchPosition! + 1} 条 · {execution.batchParallelism === 1 ? "串行" : `并发 ${execution.batchParallelism}`}</p>}
    {execution.status === "QUEUED" && execution.waitReason && <p>{waitLabels[execution.waitReason]}{execution.blockedByExecutionIds?.length ? ` · 阻塞执行 ${execution.blockedByExecutionIds.map((id) => `#${id}`).join("、")}` : ""}</p>}
  </div>
}
