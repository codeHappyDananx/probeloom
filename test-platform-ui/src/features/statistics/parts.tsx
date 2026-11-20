import { useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode, type RefObject } from "react"
import { RotateCw } from "lucide-react"
import { cn } from "@/lib/utils"
import { errorSummary, outcomeLabels, stackOrder, swatchClass, weekdayLabel, monthDay, type Counts, type Outcome } from "./statistics"
import type { Tip } from "./use-float-tip"

export function Swatch({ outcome, className }: { outcome: Outcome; className?: string }) {
  return <i aria-hidden="true" className={cn("inline-block size-2 shrink-0 rounded-[2px]", swatchClass[outcome], className)} />
}

const chipClass: Record<Outcome, string> = {
  PASSED: "bg-st-pass-tint text-st-pass-ink",
  FAILED: "bg-st-fail-tint text-st-fail-ink",
  WARN: "bg-st-warn-tint text-st-warn-ink",
  INTERRUPTED: "bg-st-halt-tint text-st-halt-ink",
  NOT_RUN: "bg-white text-st-muted ring-1 ring-inset ring-st-rule",
}

/** 结果标签。prefix 用于接口范围区分“接口失败”和“案例失败”。 */
export function ResultChip({ outcome, prefix }: { outcome: Outcome; prefix?: string }) {
  return <span className={cn("inline-flex h-6 shrink-0 items-center gap-1.5 rounded-full pr-2.5 pl-2 text-xs font-medium whitespace-nowrap", chipClass[outcome])}>
    <Swatch outcome={outcome} className="size-1.5 rounded-full" />{prefix}{outcomeLabels[outcome]}
  </span>
}

/** 单选分段控件，键盘左右键切换。quiet 用于次一级的选择（如日期快捷范围），选中态不用深色块。 */
export function Segmented<T extends string | number>({ label, value, options, onChange, className, quiet }: {
  label: string; value?: T; options: Array<{ value: T; label: string }>; onChange: (value: T) => void; className?: string; quiet?: boolean
}) {
  const group = useRef<HTMLDivElement>(null)
  const focusable = options.some((option) => option.value === value) ? value : options[0]?.value
  const move = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const step = event.key === "ArrowRight" || event.key === "ArrowDown" ? 1 : event.key === "ArrowLeft" || event.key === "ArrowUp" ? -1 : 0
    if (!step) return
    event.preventDefault()
    const next = options[(index + step + options.length) % options.length]
    onChange(next.value)
    group.current?.querySelectorAll<HTMLButtonElement>("[role=radio]")[options.indexOf(next)]?.focus()
  }
  return <div ref={group} role="radiogroup" aria-label={label} className={cn("inline-flex h-9 shrink-0 items-center rounded-lg border border-st-rule bg-white p-[3px] shadow-[0_1px_1px_rgba(26,34,50,0.04)]", className)}>
    {options.map((option, index) => {
      const checked = option.value === value
      return <button key={String(option.value)} type="button" role="radio" aria-checked={checked} tabIndex={option.value === focusable ? 0 : -1}
        onClick={() => onChange(option.value)} onKeyDown={(event) => move(event, index)}
        className={cn("h-full rounded-md px-3 text-sm whitespace-nowrap transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring/60",
          checked ? quiet ? "bg-st-wash font-medium text-st-ink shadow-[inset_0_0_0_1px_var(--color-st-line)]" : "bg-st-ink font-medium text-white" : "text-st-muted hover:bg-st-wash hover:text-st-ink")}>
        {option.label}
      </button>
    })}
  </div>
}

/** 看板里的数据区块：白底、一条边框，标题下一句话说明这块在看什么。 */
export function Panel({ title, description, aside, children, className, labelledBy }: {
  title: ReactNode; description?: ReactNode; aside?: ReactNode; children: ReactNode; className?: string; labelledBy: string
}) {
  return <section aria-labelledby={labelledBy} className={cn("min-w-0 rounded-xl border border-st-rule bg-white", className)}>
    <header className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3 px-5 pt-4 pb-3">
      <div className="min-w-0">
        <h3 id={labelledBy} className="text-[15px] leading-6 font-semibold text-st-ink">{title}</h3>
        {description && <p className="mt-0.5 max-w-[46rem] text-[13px] leading-5 text-st-muted">{description}</p>}
      </div>
      {aside}
    </header>
    {children}
  </section>
}

export function FloatTip({ host, tip }: { host: RefObject<HTMLDivElement | null>; tip?: Tip }) {
  const own = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<{ left: number; top: number }>()
  useLayoutEffect(() => {
    if (!tip || !host.current || !own.current) return
    const box = host.current.getBoundingClientRect()
    const size = own.current.getBoundingClientRect()
    const center = tip.anchor.left + tip.anchor.width / 2 - box.left
    const left = Math.min(Math.max(center - size.width / 2, -4), box.width - size.width + 4)
    let top = tip.anchor.top - box.top - size.height - 8
    if (box.top + top < 8) top = tip.anchor.bottom - box.top + 8
    setPosition({ left, top })
  }, [tip, host])
  if (!tip) return null
  return <div ref={own} role="tooltip" className="pointer-events-none absolute z-30 w-max max-w-72 rounded-lg bg-st-ink px-3 py-2.5 text-xs leading-5 text-white shadow-[0_10px_24px_-8px_rgba(26,34,50,0.5)]"
    style={position ? position : { left: 0, top: 0, visibility: "hidden" }}>{tip.content}</div>
}

/** 某一天的结果明细，用在柱子和结果格的浮层提示里。 */
export function DayTip({ date, counts, heading, hint }: { date: string; counts: Counts; heading?: string; hint?: string }) {
  const rows = [...stackOrder].reverse().filter((outcome) => (counts[outcome] ?? 0) > 0)
  return <>
    {heading && <div className="max-w-64 truncate text-white/70">{heading}</div>}
    <div className="font-medium">{monthDay(date)} {weekdayLabel(date)}</div>
    {rows.length ? <ul className="mt-1 grid grid-cols-[auto_auto] gap-x-4">
      {rows.map((outcome) => <li key={outcome} className="contents"><span className="flex items-center gap-1.5"><Swatch outcome={outcome} />{outcomeLabels[outcome]}</span><span className="text-right font-num tabular-nums">{counts[outcome]}</span></li>)}
    </ul> : <div className="mt-0.5 text-white/70">当天没有完整执行的结果</div>}
    {hint && <div className="mt-1.5 border-t border-white/15 pt-1.5 text-white/80">{hint}</div>}
  </>
}

/** 查询失败：一句话原因 + 重试；完整报错（常含 SQL）收进可展开的详情。 */
export function ErrorNote({ title, message, onRetry, className }: { title: string; message: string; onRetry: () => void; className?: string }) {
  const summary = errorSummary(message)
  return <div role="alert" className={cn("rounded-lg bg-st-fail-tint/60 px-4 py-3 text-sm text-st-fail-ink", className)}>
    <div className="flex flex-wrap items-start justify-between gap-3">
      <p className="min-w-0 max-w-[60rem] break-words"><span className="font-medium">{title}</span>：{summary}</p>
      <button type="button" onClick={onRetry} className="inline-flex shrink-0 items-center gap-1.5 rounded-md px-2 py-1 font-medium outline-none hover:bg-white/60 focus-visible:ring-2 focus-visible:ring-ring/60"><RotateCw className="size-3.5" />重试</button>
    </div>
    {summary !== message && <details className="mt-1.5 text-xs">
      <summary className="cursor-pointer text-st-fail-ink/80 select-none hover:text-st-fail-ink">错误详情</summary>
      <pre className="mt-1.5 max-h-48 overflow-auto rounded bg-white/70 p-2 font-mono text-[11px] leading-4 break-all whitespace-pre-wrap text-st-ink">{message}</pre>
    </details>}
  </div>
}

export function SkeletonRows({ rows = 4 }: { rows?: number }) {
  return <div role="status" aria-label="正在加载" className="space-y-3 px-5 pt-1 pb-5">
    {Array.from({ length: rows }, (_, index) => <div key={index} className="h-5 animate-pulse rounded bg-st-wash motion-reduce:animate-none" style={{ width: `${92 - index * 9}%` }} />)}
  </div>
}
