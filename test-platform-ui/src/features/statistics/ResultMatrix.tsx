import { ChevronRight } from "lucide-react"
import { cn } from "@/lib/utils"
import { DayTip, FloatTip, Swatch } from "./parts"
import { countPoints, dailyCounts, resultCount, isWeekend, labelledDays, monthDay, outcomeLabels, passRate, percent, shortDate, stackOrder, swatchClass, worstOutcome, type Outcome, type Point } from "./statistics"
import { useFloatTip } from "./use-float-tip"

export type MatrixRow = { id: number; label: string; navigable: boolean; points: Point[] }

const tileClass: Record<Outcome, string> = {
  FAILED: "bg-st-fail text-white",
  WARN: "bg-st-warn-tint text-st-warn-ink ring-1 ring-inset ring-st-warn/40",
  INTERRUPTED: "bg-st-halt-tint text-st-halt-ink ring-1 ring-inset ring-st-halt/30",
  NOT_RUN: "st-hatch text-st-halt-ink",
  PASSED: "bg-st-pass-tint text-st-pass-ink",
}

const totals = ["FAILED", "NOT_RUN", "PASSED", "WARN", "INTERRUPTED"] as const

export function MatrixLegend() {
  const items: Array<[string, string]> = [["bg-st-pass", "成功"], ["bg-st-fail", "失败"], ["st-hatch", "未执行"], ["bg-st-warn", "警告"], ["bg-st-halt", "中断"], ["bg-st-wash ring-1 ring-inset ring-st-rule", "无结果"]]
  return <ul className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-st-muted" aria-label="结果格图例">
    {items.map(([tone, text]) => <li key={text} className="flex items-center gap-1.5"><i aria-hidden="true" className={cn("inline-block size-3 rounded-[3px]", tone)} />{text}</li>)}
  </ul>
}

/**
 * 分组结果格按当天各结果的案例数分段着色，格内显示通过率；单案例结果条显示当天结果。
 * 有失败或没跑的格子可点开明细，点行名进入该分组。
 */
export function ResultMatrix({ rows, days, today, variant = "matrix", onRow, onPick, pickNotRun = true }: {
  rows: MatrixRow[]; days: string[]; today: string; variant?: "matrix" | "strip"; pickNotRun?: boolean
  onRow?: (id: number) => void; onPick: (date: string, row: MatrixRow) => void
}) {
  const { host, tip, show, hide } = useFloatTip()
  const strip = variant === "strip"
  const numbers = days.length <= 14
  const labelled = labelledDays(days.length)
  const tileGap = days.length > 60 ? 1 : days.length > 31 ? 2 : 4
  const tilesMin = days.length * (days.length > 60 ? 7 : days.length > 14 ? 12 : 40)
  const tiles = { display: "grid", gridTemplateColumns: `repeat(${days.length}, minmax(0, 1fr))`, gap: tileGap }
  const template = strip ? `minmax(${tilesMin}px,1fr)` : `minmax(7rem,15rem) minmax(${tilesMin}px,1fr) auto`
  const totalsGrid = "grid grid-cols-[2.75rem_3.25rem_3rem_3.75rem_4.25rem] text-right @[56rem]:grid-cols-[2.75rem_3.25rem_3rem_2.75rem_2.75rem_3.75rem_4.25rem]"
  // 窄容器只保留失败、成功、通过率和最近结果，警告、中断列在宽度足够时出现
  const optional = (outcome: Outcome) => outcome === "WARN" || outcome === "INTERRUPTED" ? "hidden @[56rem]:block" : undefined

  const header = <div className="grid items-end gap-x-4 pb-2" style={{ gridTemplateColumns: template }}>
    {!strip && <span className="text-xs text-st-muted">分组</span>}
    <div aria-hidden="true" style={tiles}>
      {days.map((date, index) => <span key={date} className={cn("relative h-4 font-num text-xs whitespace-nowrap tabular-nums", date === today ? "font-semibold text-st-ink" : isWeekend(date) ? "text-st-faint" : "text-st-muted")}>
        {labelled(index) && <span className="absolute left-1/2 -translate-x-1/2">{days.length <= 14 ? <><span className="@[56rem]:hidden">{date.slice(8)}</span><span className="hidden @[56rem]:inline">{shortDate(date)}</span></> : shortDate(date).slice(days.length > 31 ? 0 : 3)}</span>}
      </span>)}
    </div>
    {!strip && <div className={cn(totalsGrid, "text-xs text-st-muted")}>
      {totals.map((outcome) => <span key={outcome} className={optional(outcome)}>{outcomeLabels[outcome]}</span>)}<span>通过率</span><span>最近结果</span>
    </div>}
  </div>

  return <div ref={host} className="@container relative">
    <div className={cn("overflow-x-auto", !strip && "px-5 pb-4")} onPointerLeave={hide}>
      <div className="min-w-fit">
        {header}
        <div className={cn(!strip && "divide-y divide-st-rule/70 border-t border-st-rule")} role="table" aria-label={strip ? "每日结果" : "各分组每日结果"}>
          {rows.map((row) => {
            const daily = dailyCounts(days, row.points)
            const summary = countPoints(row.points)
            const latest = [...daily].reverse().find((day) => resultCount(day.counts) > 0)
            return <div key={row.id} role="row" className={cn("grid items-center gap-x-4", !strip && "py-1.5")} style={{ gridTemplateColumns: template }}>
              {!strip && <div role="rowheader" className="min-w-0">
                {row.navigable && onRow
                  ? <button type="button" onClick={() => onRow(row.id)} className="group flex max-w-full items-center gap-1 rounded text-left text-sm text-st-ink outline-none hover:underline hover:underline-offset-4 focus-visible:ring-2 focus-visible:ring-ring/60" title={`进入“${row.label}”`}>
                      <span className="truncate">{row.label}</span><ChevronRight aria-hidden="true" className="size-3.5 shrink-0 text-st-faint group-hover:text-st-ink" />
                    </button>
                  : <span className="block truncate text-sm text-st-muted">{row.label}</span>}
              </div>}
              <div role="cell" style={tiles}>
                {daily.map(({ date, counts, total }) => {
                  const worst = worstOutcome(counts)
                  const failed = counts.FAILED ?? 0
                  const idle = pickNotRun ? counts.NOT_RUN ?? 0 : 0
                  const rate = passRate(counts)
                  const figure = strip ? (days.length <= 14 && worst ? outcomeLabels[worst] : undefined) : numbers && rate !== undefined ? percent(rate) : undefined
                  const tipContent = <DayTip date={date} counts={counts} heading={strip ? undefined : row.label} hint={failed && idle ? "点击查看当天失败和没跑的案例" : failed ? "点击查看当天失败的案例" : idle ? "点击查看当天没跑的案例" : undefined} />
                  // 文字样式放在内层 span：全局样式让 button 继承父级字体，外层的字号类对按钮无效
                  const text = <>
                    {!strip && total > 0 && <span aria-hidden="true" className="absolute inset-0 flex">
                      {stackOrder.map((outcome) => (counts[outcome] ?? 0) > 0 && <span key={outcome} className={cn("h-full shrink-0", swatchClass[outcome])} style={{ width: `${(counts[outcome] ?? 0) / total * 100}%` }} />)}
                    </span>}
                    {figure !== undefined && <span className={cn("relative font-num font-semibold tabular-nums", strip ? "text-sm" : "rounded-sm bg-white/90 px-1 text-[13px] text-st-ink")}>{figure}</span>}
                  </>
                  const tone = cn("relative grid place-items-center overflow-hidden rounded-[4px] outline-none", strip ? "h-11" : "h-7", strip && worst ? tileClass[worst] : "bg-st-wash/70", date === today && !worst && "ring-1 ring-inset ring-st-line")
                  const results = resultCount(counts)
                  const describe = `${row.label} ${monthDay(date)}：${total ? `${results} 个结果${rate !== undefined ? `，通过率 ${percent(rate)}` : ""}${failed ? `，${failed} 个失败` : ""}${idle ? `，${idle} 个没跑` : ""}` : "无结果"}`
                  return failed > 0 || idle > 0
                    ? <button key={date} type="button" data-fail-day={failed ? date : undefined} data-idle-day={idle ? date : undefined} aria-label={`${describe}，查看明细`} onClick={() => onPick(date, row)}
                        onPointerEnter={(event) => show(event.currentTarget, tipContent)} onFocus={(event) => show(event.currentTarget, tipContent)} onBlur={hide}
                        className={cn(tone, "cursor-pointer transition-[filter] hover:brightness-110 focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1")}>{text}</button>
                    : <span key={date} role="img" aria-label={describe} onPointerEnter={(event) => show(event.currentTarget, tipContent)} className={tone}>{text}</span>
                })}
              </div>
              {!strip && <div role="cell" className={cn(totalsGrid, "font-num text-sm tabular-nums")}>
                {totals.map((outcome) => {
                  const value = summary[outcome] ?? 0
                  return <span key={outcome} className={cn(optional(outcome), !value ? "text-st-idle" : outcome === "FAILED" ? "font-semibold text-st-fail-ink" : "text-st-ink")}>{value}</span>
                })}
                <span className="text-st-ink">{percent(passRate(summary))}</span>
                <span className="text-st-muted">{latest ? shortDate(latest.date) : <span className="font-sans text-xs text-st-faint">无结果</span>}</span>
              </div>}
            </div>
          })}
        </div>
      </div>
    </div>
    <FloatTip host={host} tip={tip} />
  </div>
}

export function StripLegend() {
  return <ul className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-st-muted" aria-label="图例">
    {(["PASSED", "FAILED", "WARN", "INTERRUPTED", "NOT_RUN"] as const).map((outcome) => <li key={outcome} className="flex items-center gap-1.5"><Swatch outcome={outcome} />{outcomeLabels[outcome]}</li>)}
    <li className="flex items-center gap-1.5"><i aria-hidden="true" className="inline-block size-2 rounded-[2px] bg-st-wash ring-1 ring-inset ring-st-rule" />首次执行之前</li>
  </ul>
}
