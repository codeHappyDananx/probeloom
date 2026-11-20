import { useEffect, useRef, useState, type KeyboardEvent } from "react"
import { CalendarDays, ChevronLeft, ChevronRight } from "lucide-react"
import { Popover } from "radix-ui"
import { cn } from "@/lib/utils"
import { beijingToday, monthDay, rangeLabel, shiftDay } from "./statistics"
import { Segmented } from "./parts"

type Range = { from: string; to: string }
const heads = ["一", "二", "三", "四", "五", "六", "日"]
const MAX_DAYS = 366

const monthOf = (date: string) => date.slice(0, 7)
function shiftMonth(month: string, step: number) {
  const [year, value] = month.split("-").map(Number)
  const next = new Date(Date.UTC(year, value - 1 + step, 1))
  return next.toISOString().slice(0, 7)
}
function monthDays(month: string) {
  const [year, value] = month.split("-").map(Number)
  const count = new Date(Date.UTC(year, value, 0)).getUTCDate()
  const lead = (new Date(Date.UTC(year, value - 1, 1)).getUTCDay() + 6) % 7
  return { lead, days: Array.from({ length: count }, (_, i) => `${month}-${String(i + 1).padStart(2, "0")}`) }
}
const spanDays = (from: string, to: string) => (Date.parse(to) - Date.parse(from)) / 86400000 + 1

function useWide() {
  const [wide, setWide] = useState(() => window.matchMedia("(min-width: 640px)").matches)
  useEffect(() => {
    const query = window.matchMedia("(min-width: 640px)")
    const change = () => setWide(query.matches)
    query.addEventListener("change", change)
    return () => query.removeEventListener("change", change)
  }, [])
  return wide
}

/** 快捷范围 + 双月日历。点选两天即生效，最多 366 天，不能选今天之后。 */
export function DateRangePicker({ range, preset, onPreset, onRange }: { range: Range; preset: number; onPreset: (days: number) => void; onRange: (range: Range) => void }) {
  const today = beijingToday()
  const wide = useWide()
  const [open, setOpen] = useState(false)
  const [anchor, setAnchor] = useState<string>()
  const [hover, setHover] = useState<string>()
  const [focus, setFocus] = useState(range.to)
  const [right, setRight] = useState(monthOf(range.to))
  const [error, setError] = useState("")
  const grid = useRef<HTMLDivElement>(null)
  const months = wide ? [shiftMonth(right, -1), right] : [right]

  const changeOpen = (next: boolean) => {
    if (next) {
      setAnchor(undefined)
      setHover(undefined)
      setError("")
      setFocus(range.to)
      setRight(monthOf(range.to))
    }
    setOpen(next)
  }
  const pick = (date: string) => {
    if (date > today) return
    if (!anchor) {
      setAnchor(date)
      setError("")
      return
    }
    const [from, to] = anchor <= date ? [anchor, date] : [date, anchor]
    if (spanDays(from, to) > MAX_DAYS) {
      setError(`最多选择 ${MAX_DAYS} 天，当前 ${spanDays(from, to)} 天`)
      return
    }
    onRange({ from, to })
    setOpen(false)
  }
  const moveFocus = (date: string) => {
    if (date > today) date = today
    setFocus(date)
    if (!months.includes(monthOf(date))) setRight(date < `${months[0]}-01` ? (wide ? shiftMonth(monthOf(date), 1) : monthOf(date)) : monthOf(date))
    if (anchor) setHover(date)
    requestAnimationFrame(() => grid.current?.querySelector<HTMLButtonElement>(`[data-date="${date}"]`)?.focus())
  }
  const keys = (event: KeyboardEvent<HTMLButtonElement>, date: string) => {
    const steps: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }
    if (!(event.key in steps)) return
    event.preventDefault()
    moveFocus(shiftDay(date, steps[event.key]))
  }

  const [low, high] = anchor ? [anchor, hover ?? anchor].sort() : [range.from, range.to]
  const hint = error || (anchor ? `从 ${monthDay(anchor)} 开始，再点选结束日期` : "点选开始日期，再点选结束日期")
  return <div className="flex flex-wrap items-center gap-2">
    <Segmented quiet label="快捷日期范围" value={preset || undefined} onChange={onPreset} options={[{ value: 7, label: "近 7 天" }, { value: 30, label: "近 30 天" }]} />
    <Popover.Root open={open} onOpenChange={changeOpen}>
      <Popover.Trigger asChild>
        <button type="button" aria-label={`日期范围：${rangeLabel(range.from, range.to)}，点击自定义`}
          className={cn("inline-flex h-9 items-center gap-2 rounded-lg border bg-white pr-3 pl-2.5 text-sm shadow-[0_1px_1px_rgba(26,34,50,0.04)] transition-colors outline-none hover:border-st-line focus-visible:ring-2 focus-visible:ring-ring/60 data-[state=open]:border-st-ink",
            preset ? "border-st-rule text-st-muted" : "border-st-ink font-medium text-st-ink")}>
          <CalendarDays aria-hidden="true" className="size-4 text-st-faint" />
          <span className="font-num">{rangeLabel(range.from, range.to)}</span>
        </button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content align="end" sideOffset={6} collisionPadding={12} aria-label="选择日期范围"
          onOpenAutoFocus={(event) => { event.preventDefault(); grid.current?.querySelector<HTMLButtonElement>(`[data-date="${focus}"]`)?.focus() }}
          className="st-pop st-layer z-50 w-max max-w-[calc(100vw-1.5rem)] rounded-xl border border-st-rule bg-white p-4 text-st-ink shadow-[0_18px_40px_-16px_rgba(26,34,50,0.32),0_2px_6px_rgba(26,34,50,0.06)] outline-none">
          <div ref={grid} className="flex gap-6" onPointerLeave={() => setHover(undefined)}>
            {months.map((month, index) => {
              const { lead, days } = monthDays(month)
              return <div key={month} className="w-[16.5rem]">
                <div className="mb-2 flex h-8 items-center justify-between">
                  <button type="button" aria-label="上个月" onClick={() => setRight(shiftMonth(right, -1))} className={cn("grid size-8 place-items-center rounded-md text-st-muted hover:bg-st-wash hover:text-st-ink focus-visible:ring-2 focus-visible:ring-ring/60 focus-visible:outline-none", index !== 0 && "invisible")}><ChevronLeft className="size-4" /></button>
                  <span className="font-num text-sm font-semibold">{month.slice(0, 4)} 年 {Number(month.slice(5))} 月</span>
                  <button type="button" aria-label="下个月" disabled={right >= monthOf(today)} onClick={() => setRight(shiftMonth(right, 1))} className={cn("grid size-8 place-items-center rounded-md text-st-muted hover:bg-st-wash hover:text-st-ink focus-visible:ring-2 focus-visible:ring-ring/60 focus-visible:outline-none disabled:opacity-30 disabled:hover:bg-transparent", index !== months.length - 1 && "invisible")}><ChevronRight className="size-4" /></button>
                </div>
                <div role="grid" aria-label={`${month.slice(0, 4)}年${Number(month.slice(5))}月`} className="grid grid-cols-7 gap-y-1">
                  {heads.map((head, column) => <span key={head} role="columnheader" className={cn("grid h-7 place-items-center text-xs", column > 4 ? "text-st-faint" : "text-st-muted")}>{head}</span>)}
                  {Array.from({ length: lead }, (_, i) => <span key={`lead-${i}`} />)}
                  {days.map((date) => {
                    const future = date > today
                    const inside = date >= low && date <= high
                    const edge = date === low || date === high
                    return <div key={date} role="gridcell" aria-selected={inside} className="relative h-9">
                      {inside && (edge ? low !== high : true) && <span aria-hidden="true" className={cn("absolute inset-y-0 bg-st-wash", !edge ? "inset-x-0" : date === low ? "right-0 left-1/2" : "right-1/2 left-0")} />}
                      <button type="button" data-date={date} disabled={future} tabIndex={date === focus ? 0 : -1}
                        onClick={() => pick(date)} onKeyDown={(event) => keys(event, date)} onPointerEnter={() => anchor && setHover(date)} onFocus={() => setFocus(date)}
                        aria-label={`${monthDay(date)}${date === today ? "，今天" : ""}`}
                        className={cn("relative mx-auto grid size-9 place-items-center rounded-md font-num text-sm tabular-nums outline-none focus-visible:ring-2 focus-visible:ring-ring/60",
                          edge ? "bg-st-ink font-semibold text-white" : future ? "cursor-not-allowed text-st-idle" : "text-st-ink hover:bg-st-rule")}>
                        {Number(date.slice(8))}
                        {date === today && <span aria-hidden="true" className={cn("absolute bottom-1 size-1 rounded-full", edge ? "bg-white" : "bg-st-ink")} />}
                      </button>
                    </div>
                  })}
                </div>
              </div>
            })}
          </div>
          <div className="mt-3 flex items-center justify-between gap-4 border-t border-st-rule pt-3 text-xs">
            <span role={error ? "alert" : "status"} className={error ? "text-st-fail-ink" : "text-st-muted"}>{hint}</span>
            <span className="font-num text-st-faint tabular-nums">{anchor ? "" : `${spanDays(range.from, range.to)} 天`}</span>
          </div>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  </div>
}
