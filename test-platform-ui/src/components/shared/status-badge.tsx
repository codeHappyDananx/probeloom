import { Badge } from "@/components/ui/badge"
import { statusHint, statusLabel, statusTone } from "@/lib/platform-api"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { CheckCircle2, CircleAlert, CircleX, Clock, RefreshCw, SkipForward } from "lucide-react"

const statusIcons = {
  PASSED: CheckCircle2,
  SUCCESS: CheckCircle2,
  FAILED: CircleX,
  RUNNING: RefreshCw,
  QUEUED: Clock,
  WARN: CircleAlert,
  SKIPPED: SkipForward,
  NOT_SELECTED: SkipForward,
  PENDING: Clock,
}

export function StatusBadge({ status }: { status: string }) {
  const Icon = statusIcons[status as keyof typeof statusIcons] || CircleAlert
  const isSpinning = status === "RUNNING"
  const hint = statusHint(status)
  
  const badge = (
    <Badge className={`min-w-14 justify-center gap-1 ${status === "WARN" ? "border-amber-300 bg-amber-100 text-amber-800 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-300" : ""}`} variant={statusTone(status)}>
      <Icon className={`h-3 w-3 ${isSpinning ? "animate-spin" : ""}`} />
      {statusLabel(status)}
    </Badge>
  )
  
  // 如果有提示信息，用 Tooltip 包装
  if (hint) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>{badge}</TooltipTrigger>
        <TooltipContent>
          <p>{hint}</p>
        </TooltipContent>
      </Tooltip>
    )
  }
  
  return badge
}
