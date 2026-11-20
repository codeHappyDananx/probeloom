import type { ReactNode } from "react"
import { X } from "lucide-react"
import { Dialog } from "radix-ui"
import { Segmented } from "./parts"
import { monthDay, weekdayLabel } from "./statistics"

export type DrawerTab = "failed" | "notRun"

/**
 * 下钻抽屉：从右侧滑出，关闭后回到看板原位置。
 * 某一天：在“失败”和“没跑”之间切换；date 为空时列出从未执行过的案例。
 */
export function FailureDrawer({ open, date, scopeLabel, failed = 0, notRun = 0, tab, onTab, onClose, children }: {
  open: boolean; date?: string; scopeLabel: string; failed?: number; notRun?: number; tab: DrawerTab
  onTab: (tab: DrawerTab) => void; onClose: () => void; children: ReactNode
}) {
  const both = failed > 0 && notRun > 0
  const count = tab === "failed" ? failed : notRun
  const title = !date ? "从未执行过的案例" : both ? "失败和没跑的案例" : tab === "failed" ? "失败的案例" : "没跑的案例"
  const description = !date ? "已启用、未删除，但还没有任何一次执行的案例。点案例名查看它的每日结果。"
    : tab === "failed" ? "每个案例取当天最后一次完整执行。点一条记录查看执行详情。"
    : "已启用、未删除，从首次执行那天起当天没有完整执行的案例。"
  return <Dialog.Root open={open} onOpenChange={(next) => { if (!next) onClose() }}>
    <Dialog.Portal>
      <Dialog.Overlay className="st-veil fixed inset-0 z-50 bg-st-ink/25" />
      <Dialog.Content className="st-drawer st-layer fixed inset-y-0 right-0 z-50 flex w-[min(46rem,100vw)] flex-col bg-white shadow-[-24px_0_48px_-24px_rgba(26,34,50,0.35)] outline-none">
        {open && <>
          <header className="flex items-start justify-between gap-4 px-5 pt-5 pb-4">
            <div className="min-w-0">
              <Dialog.Title className="text-lg leading-7 font-semibold text-st-ink">
                {date && <><span className="font-num">{monthDay(date)}</span>（{weekdayLabel(date)}）</>}{title}
                {!both && count > 0 && <span className={tab === "failed" && date ? "ml-2 font-num text-st-fail-ink" : "ml-2 font-num text-st-muted"}>{count.toLocaleString()}</span>}
              </Dialog.Title>
              <Dialog.Description className="mt-0.5 text-[13px] text-st-muted"><span className="font-medium text-st-ink">{scopeLabel}</span>。{description}</Dialog.Description>
              {both && <Segmented quiet className="mt-3" label="查看" value={tab} onChange={onTab}
                options={[{ value: "failed", label: `失败 ${failed.toLocaleString()}` }, { value: "notRun", label: `没跑 ${notRun.toLocaleString()}` }]} />}
            </div>
            <Dialog.Close className="grid size-8 shrink-0 place-items-center rounded-md text-st-muted outline-none hover:bg-st-wash hover:text-st-ink focus-visible:ring-2 focus-visible:ring-ring/60" aria-label="关闭"><X className="size-4" /></Dialog.Close>
          </header>
          <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
        </>}
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>
}
