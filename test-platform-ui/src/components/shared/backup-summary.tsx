import { useState } from "react"
import { toast } from "sonner"
import { AlertTriangle, DatabaseBackup, LoaderCircle } from "lucide-react"
import type { Execution, TableBackup } from "@/types/platform"
import { api, formatTime } from "@/lib/platform-api"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog"
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger } from "@/components/ui/alert-dialog"

const labels: Record<TableBackup["status"], string> = {
  CREATING: "备份尚未确认完成",
  READY: "已备份，待案例结束恢复",
  BACKUP_FAILED: "备份失败",
  RESTORE_FAILED: "恢复失败，备份保留",
  RESTORED: "已恢复，等待收尾完成后删除备份",
  DROP_FAILED: "已恢复，备份清理失败",
  DROPPED: "已恢复，备份已删除",
}

export const hasPendingRecovery = (execution?: Execution) => execution?.status === "FAILED" && !!execution.progressAt && execution.finalizationPhase !== "COMPLETE"
const canEndExecution = (execution?: Execution) => !!execution && (["QUEUED", "RUNNING"].includes(execution.status) || hasPendingRecovery(execution))

export function EndExecutionButton({ execution }: { execution: Execution }) {
  const [open, setOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  if (!canEndExecution(execution)) return null
  return <AlertDialog open={open} onOpenChange={setOpen}>
    <AlertDialogTrigger asChild><Button size="sm" variant="outline" disabled={execution.finalizationState === "ENDING"} onClick={(event) => event.stopPropagation()}>{execution.finalizationState === "ENDING" ? "结束中…" : "结束执行"}</Button></AlertDialogTrigger>
    <AlertDialogContent onClick={(event) => event.stopPropagation()}>
      <AlertDialogHeader><AlertDialogTitle>结束本次执行？</AlertDialogTitle><AlertDialogDescription>停止后续步骤，还原已建立的备份并清理备份表。普通清理失败不阻塞结束；还原失败会保留备份和错误，释放执行占用。</AlertDialogDescription></AlertDialogHeader>
      <AlertDialogFooter><AlertDialogCancel disabled={submitting}>取消</AlertDialogCancel><AlertDialogAction disabled={submitting} onClick={async (event) => {
        event.preventDefault()
        setSubmitting(true)
        try {
          await api(`/api/executions/${execution.id}/end`, { method: "POST" })
          toast.success(execution.status === "QUEUED" ? "已结束排队" : "已提交结束，将还原备份并释放执行占用")
          setOpen(false)
        } catch (error) { toast.error(error instanceof Error ? error.message : "结束执行失败") }
        finally { setSubmitting(false) }
      }}>{submitting && <LoaderCircle className="size-4 animate-spin" />}{submitting ? "提交中…" : "结束执行"}</AlertDialogAction></AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
}

export function BackupSummary({ execution }: { execution?: Execution }) {
  if (!execution || (!execution.backups?.length && !canEndExecution(execution))) return null
  const waiting = execution.finalizationState === "WAITING"
  const phases: Record<string, string> = { BUSINESS: "案例执行", CLEANUP: "清理案例数据", RESTORE: "还原备份", CASE_END: "执行案例收尾步骤", DROP_BACKUPS: "清理备份", COMPLETE: "收尾结束" }
  const states: Record<string, string> = { ACTIVE: "处理中", ENDING: "正在结束并还原备份", PENDING: "等待接续", WAITING: "等待超时结束", COMPLETE: "已结束" }
  return <section aria-label="案例备份与收尾" className="max-h-[60vh] shrink-0 space-y-4 overflow-auto rounded-lg border bg-background p-4 text-sm">
    <div className="flex items-center gap-2 font-medium"><DatabaseBackup className="size-4 text-primary" />案例备份与收尾<span className="ml-auto text-xs text-muted-foreground">{execution.backups?.length || 0} 份备份</span><EndExecutionButton execution={execution} /></div>
    {hasPendingRecovery(execution) && <div role="alert" className="flex gap-3 rounded-md border border-amber-300/60 bg-amber-50 p-3 text-amber-950 dark:bg-amber-950/30 dark:text-amber-100">
      <AlertTriangle className="mt-0.5 size-4 shrink-0" />
      <div className="space-y-1"><p className="font-medium">执行已失败，{waiting ? "等待结束" : "正在收尾"}</p><p className="text-xs">阻塞超过 10 分钟自动结束，也可点击“结束执行”；仅还原成功建立的备份，普通清理失败不阻塞结束。</p></div>
    </div>}
    {execution.operationConfirmation && <p>核查登记：{execution.operationConfirmation}</p>}
    {canEndExecution(execution) && <div className="space-y-2">
      <p>{execution.finalizationPhase && phases[execution.finalizationPhase]} · {execution.finalizationState && states[execution.finalizationState]}</p>
      <p>最近进展：{formatTime(execution.progressAt)}</p>
      {execution.finalizationDeadlineAt && <p className="text-xs text-muted-foreground">自动结束时间：{formatTime(execution.finalizationDeadlineAt)}，到期进入备份还原和清理。</p>}
      {execution.finalizationError && <p role="alert" className="text-destructive">{execution.finalizationError}</p>}
    </div>}
    {execution.backups?.map((backup) => <div key={backup.id} className="space-y-1 break-all border-t pt-2">
      <p className="font-medium">{backup.tableName}<span className="ml-2 text-xs font-normal text-muted-foreground">{backup.datasourceKey}{backup.rowCount != null ? ` · ${backup.rowCount} 行` : ""}</span></p>
      <p className="text-xs text-muted-foreground">快照：{backup.snapshotName}</p>
      <p>备份及还原范围：{backup.backupCondition || "整表"}</p>
      <p className={backup.status.endsWith("FAILED") ? "text-destructive" : "text-muted-foreground"}>{backup.status === "DROPPED" && backup.rowCount == null ? "备份未建立，残留备份已清理" : labels[backup.status]}</p>
      {backup.status !== "DROPPED" && <p>备份表：{backup.backupTable}</p>}
      {backup.errorMessage && <p role="alert" className="text-destructive">{backup.errorMessage}</p>}
    </div>)}
  </section>
}

export function BackupSummaryDialog({ execution }: { execution?: Execution }) {
  if (!execution || (!execution.backups?.length && !canEndExecution(execution))) return null
  const waiting = execution.finalizationState === "WAITING" && (execution.status === "RUNNING" || hasPendingRecovery(execution))
  const failed = waiting || execution.backups?.some((backup) => backup.status.endsWith("FAILED"))
  const restored = !!execution.backups?.length && execution.backups.every((backup) => backup.status === "DROPPED")
  return <Dialog>
    <DialogTrigger asChild><Button variant="outline" size="sm" className={failed ? "text-destructive" : ""}><DatabaseBackup className="size-4" />备份与收尾 · {failed ? "失败" : restored ? "已恢复" : execution.finalizationPhase === "COMPLETE" ? "已结束" : "待收尾"}</Button></DialogTrigger>
    <DialogContent className="sm:max-w-xl">
      <DialogHeader><DialogTitle>备份与收尾</DialogTitle><DialogDescription>仅针对本次案例执行。恢复成功后删除备份表。</DialogDescription></DialogHeader>
      <BackupSummary execution={execution} />
    </DialogContent>
  </Dialog>
}
