import { cn } from "@/lib/utils"
import { DayTip, FloatTip } from "./parts"
import { isWeekend, labelledDays, monthDay, resultCount, shortDate, stackOrder, swatchClass, weekdayLabel, type DayCounts } from "./statistics"
import { useFloatTip } from "./use-float-tip"

function niceCeil(value: number) {
  if (value <= 2) return 2
  const magnitude = 10 ** Math.floor(Math.log10(value))
  const step = [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10].find((m) => m * magnitude >= value)! * magnitude
  return step % 2 ? step + 1 : step
}

/**
 * 每日结果柱：有结果的部分在下（失败在其顶端），没跑的部分以斜纹垫在最上面，柱高即当天应跑的案例数。
 * 有失败或没跑的柱子可点；pickNotRun 为否时（接口范围）只有失败可点。
 */
export function DailyColumns({ data, today, label, onPick, pickNotRun = true }: { data: DayCounts[]; today: string; label: string; onPick: (date: string) => void; pickNotRun?: boolean }) {
  const { host, tip, show, hide } = useFloatTip()
  const peak = Math.max(0, ...data.map((day) => day.total))
  const max = niceCeil(peak)
  const sparse = data.length <= 14
  const showFailCount = data.length <= 31
  const labelled = labelledDays(data.length)
  const gap = data.length > 90 ? "gap-px" : data.length > 31 ? "gap-0.5" : "gap-1.5"

  return <div ref={host} className="relative">
    <div className="grid grid-cols-[2.25rem_minmax(0,1fr)]">
      <div aria-hidden="true" className="relative h-56 font-num text-xs text-st-faint tabular-nums">
        {[max, max / 2, 0].map((value, i) => <span key={i} className="absolute right-2 -translate-y-1/2" style={{ top: `${i * 50}%` }}>{value}</span>)}
      </div>
      <div className="relative h-56" role="group" aria-label={`${label}每日结果`}>
        {[0, 50, 100].map((top) => <div key={top} aria-hidden="true" className={cn("absolute inset-x-0 border-t", top === 100 ? "border-st-line" : "border-dashed border-st-rule")} style={{ top: `${top}%` }} />)}
        <div className={cn("absolute inset-0 flex", gap)} onPointerLeave={hide}>
          {data.map(({ date, counts, total }, index) => {
            const failed = counts.FAILED ?? 0
            const idle = pickNotRun ? counts.NOT_RUN ?? 0 : 0
            const height = total / max * 100
            const results = resultCount(counts)
            const describe = `${monthDay(date)}，${results} 个结果${failed ? `，其中 ${failed} 个失败` : ""}${idle ? `，${idle} 个没跑` : ""}`
            const hint = failed && idle ? "点击查看当天失败和没跑的案例" : failed ? "点击查看当天失败的案例" : idle ? "点击查看当天没跑的案例" : undefined
            const tipContent = <DayTip date={date} counts={counts} hint={hint} />
            const bar = <>
              {total > 0 && <span aria-hidden="true" className="st-rise relative flex w-[min(2.75rem,64%)] min-w-px flex-col-reverse overflow-hidden rounded-t-[3px]"
                style={{ height: `${height}%`, animationDelay: `${Math.min(index * 18, 360)}ms` }}>
                {stackOrder.map((outcome) => (counts[outcome] ?? 0) > 0 && <span key={outcome} className={cn("block w-full shrink-0", swatchClass[outcome], outcome === "PASSED" && "opacity-90")} style={{ height: `${(counts[outcome] ?? 0) / total * 100}%` }} />)}
              </span>}
              {showFailCount && failed > 0 && <span aria-hidden="true" className="absolute left-1/2 -translate-x-1/2 font-num text-xs font-semibold text-st-fail-ink tabular-nums" style={{ bottom: `calc(${height}% + 3px)` }}>{failed}</span>}
            </>
            const slot = cn("relative flex h-full min-w-0 flex-1 items-end justify-center rounded-sm outline-none", isWeekend(date) && "bg-st-shade/70")
            return failed > 0 || idle > 0
              ? <button key={date} type="button" data-day-column data-fail-day={failed ? date : undefined} data-idle-day={idle ? date : undefined} aria-label={`${describe}，查看明细`} onClick={() => onPick(date)}
                  onPointerEnter={(event) => show(event.currentTarget, tipContent)} onFocus={(event) => show(event.currentTarget, tipContent)} onBlur={hide}
                  className={cn(slot, "group cursor-pointer focus-visible:ring-2 focus-visible:ring-ring/60", failed ? "hover:bg-st-fail-tint/60" : "hover:bg-st-wash")}>{bar}</button>
              : <div key={date} data-day-column role="img" aria-label={describe} onPointerEnter={(event) => show(event.currentTarget, tipContent)} className={cn(slot, "hover:bg-st-wash")}>{bar}</div>
          })}
        </div>
        {peak === 0 && <p className="absolute inset-0 grid place-items-center text-sm text-st-muted">这段日期内没有完整执行的结果</p>}
      </div>
      <div />
      <div aria-hidden="true" className={cn("flex pt-2", gap, sparse ? "h-11" : "h-7")}>
        {data.map(({ date }, index) => <div key={date} className="relative min-w-0 flex-1 text-center">
          {labelled(index) && <span className={cn("absolute left-1/2 -translate-x-1/2 font-num text-xs whitespace-nowrap tabular-nums", date === today ? "font-semibold text-st-ink" : isWeekend(date) ? "text-st-faint" : "text-st-muted")}>
            {shortDate(date)}
            {sparse && <span className={cn("block font-sans text-[11px] leading-4", date === today ? "text-st-ink" : "text-st-faint")}>{date === today ? "今天" : weekdayLabel(date)}</span>}
          </span>}
        </div>)}
      </div>
    </div>
    <FloatTip host={host} tip={tip} />
  </div>
}
