import { useEffect, useMemo, useState, type ReactNode } from "react"
import { CircleHelp, Info, RotateCw } from "lucide-react"
import { Popover } from "radix-ui"
import { toast } from "sonner"
import { cn } from "@/lib/utils"
import { descendantGroupIds, orderedGroups } from "@/features/cases/group-tree"
import { ExecutionResultDialog } from "@/features/executions/ExecutionsPage"
import { api } from "@/lib/platform-api"
import type { Bootstrap, Execution, Id } from "@/types/platform"
import { DailyColumns } from "./DailyColumns"
import { DateRangePicker } from "./DateRangePicker"
import { FailureDrawer, type DrawerTab } from "./FailureDrawer"
import { CasePicker, GroupPicker, InterfacePicker } from "./pickers"
import { ErrorNote, Panel, Segmented, Swatch } from "./parts"
import { MatrixLegend, ResultMatrix, StripLegend, type MatrixRow } from "./ResultMatrix"
import { CaseSummaryTable, FailureList, NotRunList } from "./ResultTables"
import {
  beijingToday, countPoints, errorSummary, retryOnce, dailyCounts, resultCount, withNotRun, dayRange, interfaceInfo, outcomeLabels, passRate, percent, rangeLabel, recentRange, spokenDay, worstOutcome,
  type Counts, type DayCounts, type InterfaceOptions, type NotRunRow, type Outcome, type StatisticsOverview, type StatisticsRow,
} from "./statistics"

type Scope = "platform" | "group" | "interface" | "case"
type InterfaceFilter = "ALL" | "outcome" | "case"
/** date 为空表示“从未执行过的案例”。 */
type Drill = { date?: string; groupId?: number; label: string; failed: number; notRun: number; direct?: boolean; tab: DrawerTab }

const scopeOptions: Array<{ value: Scope; label: string }> = [
  { value: "platform", label: "整个平台" }, { value: "group", label: "分组" }, { value: "interface", label: "接口" }, { value: "case", label: "案例" },
]
const filterOptions: Array<{ value: InterfaceFilter; label: string }> = [
  { value: "ALL", label: "全部案例" }, { value: "outcome", label: "接口失败" }, { value: "case", label: "案例失败" },
]

/** 结论句：用最近一个有结果的日子回答“现在怎么样”，并带上当天没跑的数量。 */
function headline(scope: Scope, daily: DayCounts[], from: string, to: string, today: string) {
  const latest = [...daily].reverse().find((day) => resultCount(day.counts) > 0)
  if (!latest) {
    const idle = daily.at(-1)?.counts.NOT_RUN ?? 0
    return scope !== "interface" && idle ? `${rangeLabel(from, to)}没有完整执行的结果，${spokenDay(daily.at(-1)!.date, today)}有 ${idle.toLocaleString()} 个案例没跑` : `${rangeLabel(from, to)}没有完整执行的结果`
  }
  const { counts } = latest
  const total = resultCount(counts)
  const day = spokenDay(latest.date, today)
  const n = (outcome: Outcome) => counts[outcome] ?? 0
  if (scope === "case") {
    const { NOT_RUN: _, ...results } = counts
    const after = daily.filter((item) => item.date > latest.date && (item.counts.NOT_RUN ?? 0) > 0).length
    return `${day}的完整执行结果：${outcomeLabels[worstOutcome(results) ?? "PASSED"]}${after ? `；之后 ${after} 天没跑` : ""}`
  }
  if (scope === "interface") {
    const parts = [n("FAILED") && `接口失败 ${n("FAILED")} 个`, n("WARN") && `警告 ${n("WARN")} 个`, n("INTERRUPTED") && `中断 ${n("INTERRUPTED")} 个`, n("NOT_RUN") && `${n("NOT_RUN")} 个没有执行到该接口`].filter(Boolean)
    return `${day}，调用该接口的 ${total} 个案例中，${parts.length ? parts.join("，") : "接口全部成功"}`
  }
  const parts = [n("FAILED") && `其中 ${n("FAILED")} 个失败`, n("WARN") && `${n("WARN")} 个警告`, n("INTERRUPTED") && `${n("INTERRUPTED")} 个中断`].filter(Boolean)
  const idle = n("NOT_RUN")
  return `${day}，${total.toLocaleString()} 个案例有结果，${parts.length ? parts.join("，") : "全部成功"}${idle ? `；还有 ${idle.toLocaleString()} 个没跑` : ""}`
}

function MethodNote({ scope }: { scope: Scope }) {
  const lines = [
    "按北京时间切日，起止两天都包含，最多 366 天。",
    "仅统计当前已启用、未回收的案例。",
    "每个案例每天只取最后一次有结果的完整执行（成功、失败、警告、中断）；只勾选部分步骤的执行不计入。",
    "分组按案例当前所在的分组统计，包含下级分组。",
    "包含所有运行环境。",
    ...(scope === "interface" ? [
      "先筛出调用过该接口的完整执行，再按天取最后一次。",
      "同一次执行多次调用该接口时取最差结果；该接口步骤没有运行记为“未执行”。",
      "缺少历史快照的执行无法识别接口，不计入接口统计。",
    ] : []),
    ...(scope === "interface" ? [] : [
      "未执行（没跑）：已启用、未删除的案例，从首次执行那天起，某天没有完整执行即计 1 次；当天的数字会随执行减少。从未执行过的案例单独列出。",
    ]),
    "通过率 = 成功 ÷ 有结果的总数，“未执行”不计入。",
  ]
  return <Popover.Root>
    <Popover.Trigger className="inline-flex h-8 shrink-0 items-center gap-1.5 rounded-md px-2 text-[13px] text-st-muted outline-none hover:bg-st-wash hover:text-st-ink focus-visible:ring-2 focus-visible:ring-ring/60 data-[state=open]:bg-st-wash data-[state=open]:text-st-ink">
      <CircleHelp aria-hidden="true" className="size-4" />统计口径
    </Popover.Trigger>
    <Popover.Portal>
      <Popover.Content align="end" sideOffset={6} collisionPadding={12} className="st-pop st-layer z-50 w-[22rem] max-w-[calc(100vw-1.5rem)] rounded-xl border border-st-rule bg-white p-4 text-st-ink shadow-[0_18px_40px_-16px_rgba(26,34,50,0.32),0_2px_6px_rgba(26,34,50,0.06)] outline-none">
        <p className="text-sm font-semibold">这些数字怎么来的</p>
        <ul className="mt-2 space-y-1.5 text-[13px] leading-5 text-st-muted">
          {lines.map((line) => <li key={line} className="flex gap-2"><span aria-hidden="true" className="mt-2 size-1 shrink-0 rounded-full bg-st-faint" />{line}</li>)}
        </ul>
      </Popover.Content>
    </Popover.Portal>
  </Popover.Root>
}

function Stat({ label, value, outcome, muted }: { label: string; value: ReactNode; outcome?: Outcome; muted?: boolean }) {
  return <div className="min-w-0">
    <dt className="flex items-center gap-1.5 text-xs text-st-muted">{outcome && <Swatch outcome={outcome} />}{label}</dt>
    <dd className={cn("mt-0.5 font-num text-[1.625rem] leading-8 font-semibold tabular-nums", muted ? "text-st-idle" : outcome === "FAILED" ? "text-st-fail-ink" : "text-st-ink")}>{value}</dd>
  </div>
}

function StatsRow({ counts, scope, from, to }: { counts: Counts; scope: Scope; from: string; to: string }) {
  const kinds: Outcome[] = ["PASSED", "FAILED", "WARN", "INTERRUPTED", "NOT_RUN"]
  const rate = passRate(counts)
  return <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
    <dl className="flex flex-wrap items-end gap-x-7 gap-y-3">
      <Stat label="通过率" value={percent(rate)} muted={rate === undefined} />
      <div aria-hidden="true" className="h-11 w-px bg-st-rule" />
      {kinds.map((outcome) => <Stat key={outcome} label={outcomeLabels[outcome]} outcome={outcome} value={(counts[outcome] ?? 0).toLocaleString()} muted={!counts[outcome]} />)}
    </dl>
    <p className="pb-1.5 text-xs text-st-faint">{rangeLabel(from, to)}合计，{scope === "case" ? "单位为天" : "每个案例每天计 1 次"}</p>
  </div>
}

function Crumbs({ items }: { items: Array<{ label: string; onClick?: () => void }> }) {
  return <nav aria-label="当前统计范围" className="flex min-w-0 flex-wrap items-center gap-x-1.5 text-sm">
    {items.map((item, index) => <span key={index} className="flex min-w-0 items-center gap-1.5">
      {index > 0 && <span aria-hidden="true" className="text-st-idle">/</span>}
      {item.onClick
        ? <button type="button" onClick={item.onClick} className="truncate rounded text-st-muted outline-none hover:text-st-ink hover:underline hover:underline-offset-4 focus-visible:ring-2 focus-visible:ring-ring/60">{item.label}</button>
        : <span aria-current="page" className="truncate font-medium text-st-ink">{item.label}</span>}
    </span>)}
  </nav>
}

function Choose({ text, action, onAction }: { text: string; action: string; onAction: () => void }) {
  return <div className="flex min-h-72 flex-col items-start justify-center gap-4 rounded-xl border border-dashed border-st-line px-8 py-10">
    <p className="max-w-[34rem] text-lg leading-7 text-st-ink">{text}</p>
    <button type="button" onClick={onAction} className="inline-flex h-9 items-center rounded-lg bg-st-ink px-4 text-sm font-medium text-white outline-none hover:bg-st-ink/90 focus-visible:ring-2 focus-visible:ring-ring/60 focus-visible:ring-offset-2">{action}</button>
  </div>
}

function HeroSkeleton() {
  return <div role="status" aria-label="正在统计" className="animate-pulse space-y-5 motion-reduce:animate-none">
    <div className="h-8 w-[28rem] max-w-full rounded-md bg-st-wash" />
    <div className="flex gap-7">{Array.from({ length: 5 }, (_, i) => <div key={i} className="h-11 w-16 rounded-md bg-st-wash" />)}</div>
    <div className="flex h-56 items-end gap-1.5 pl-9">{Array.from({ length: 14 }, (_, i) => <div key={i} className="flex-1 rounded-t-[3px] bg-st-wash" style={{ height: `${40 + ((i * 37) % 50)}%` }} />)}</div>
  </div>
}

export function StatisticsPage({ data }: { data: Bootstrap }) {
  const today = beijingToday()
  const [scope, setScope] = useState<Scope>("platform")
  const [range, setRange] = useState(() => recentRange(7))
  const [preset, setPreset] = useState(7)
  const [refresh, setRefresh] = useState(0)
  const [groupId, setGroupId] = useState<number>()
  const [caseId, setCaseId] = useState<number>()
  const [interfaceKey, setInterfaceKey] = useState("")
  const [interfaceFilter, setInterfaceFilter] = useState<InterfaceFilter>("ALL")
  const [drill, setDrill] = useState<Drill>()
  const [overview, setOverview] = useState<StatisticsOverview>()
  const [error, setError] = useState("")
  const [interfaces, setInterfaces] = useState<InterfaceOptions>()
  const [interfaceError, setInterfaceError] = useState("")
  const [detail, setDetail] = useState<{ execution: Execution; stepId?: number }>()
  const [picker, setPicker] = useState<"interface" | "case">()

  const days = useMemo(() => dayRange(range.from, range.to), [range])
  const groups = useMemo(() => orderedGroups(data.groups), [data.groups])
  const groupById = useMemo(() => new Map(groups.map((g) => [g.id, g])), [groups])
  const selectedGroup = scope === "platform" ? undefined : groupId
  const groupScopeIds = useMemo(() => selectedGroup === undefined ? undefined : new Set([selectedGroup, ...descendantGroupIds(groups, selectedGroup)]), [groups, selectedGroup])
  const cases = useMemo(() => data.cases.filter((item) => item.enabled && (!groupScopeIds || groupScopeIds.has(item.groupId ?? 0))), [data.cases, groupScopeIds])
  const scopeParams = new URLSearchParams(range)
  if (selectedGroup !== undefined) scopeParams.set("groupId", String(selectedGroup))
  const interfaceOptionsRequest = scope === "interface" && days.length ? `/api/statistics/interfaces?${scopeParams}` : undefined
  if (scope === "interface" && interfaceKey) scopeParams.set("interfaceKey", interfaceKey)
  if (scope === "case" && caseId !== undefined) scopeParams.set("caseId", String(caseId))
  const ready = !!days.length && (scope !== "interface" || !!interfaceKey) && (scope !== "case" || caseId !== undefined)
  const overviewRequest = ready ? `/api/statistics/overview?${scopeParams}` : undefined

  useEffect(() => {
    setInterfaces(undefined)
    setInterfaceError("")
    if (!interfaceOptionsRequest) return
    const controller = new AbortController()
    void retryOnce(() => api<InterfaceOptions>(interfaceOptionsRequest, { signal: controller.signal }), controller.signal).then((options) => {
      if (controller.signal.aborted) return
      setInterfaces(options)
      setInterfaceKey((previous) => options.keys.includes(previous) ? previous : "")
    }).catch((err: Error) => { if (!controller.signal.aborted) setInterfaceError(err.message) })
    return () => controller.abort()
  }, [interfaceOptionsRequest, refresh])

  useEffect(() => {
    setOverview(undefined)
    setError("")
    if (!overviewRequest) return
    const controller = new AbortController()
    void retryOnce(() => api<StatisticsOverview>(overviewRequest, { signal: controller.signal }), controller.signal).then((result) => {
      if (!controller.signal.aborted) setOverview(result)
    }).catch((err: Error) => { if (!controller.signal.aborted) setError(err.message) })
    return () => controller.abort()
  }, [overviewRequest, refresh])

  const changeScope = (next: Scope, nextGroup?: number, nextCase?: number) => {
    setScope(next)
    setGroupId(nextGroup)
    setCaseId(nextCase)
    setInterfaceKey("")
    setDrill(undefined)
    setInterfaceFilter("ALL")
  }
  const changeRange = (next: typeof range) => {
    setRange(next)
    setDrill(undefined)
  }
  const showDetail = async (row: StatisticsRow) => {
    try {
      const execution = await api<Execution>(`/api/executions/${row.executionId}`)
      setDetail({ execution, stepId: row.failedStepId ?? execution.steps?.find((step) => step.status === "FAILED")?.id })
    } catch (err) {
      toast.error(err instanceof Error ? `读取执行详情失败：${err.message}` : "读取执行详情失败")
    }
  }

  const points = useMemo(() => overview ? withNotRun(overview.points, overview.notRun) : [], [overview])
  const neverRun = (overview?.neverRun ?? []).reduce((sum, item) => sum + item.count, 0)
  const totals = countPoints(points)
  const daily = useMemo(() => dailyCounts(days, points), [days, points])
  const groupPath = (id?: Id) => id === 0 ? "未分组" : id === undefined ? "整个平台" : groupById.get(id)?.fullPath || groupById.get(id)?.name || "分组"

  // 结果格的行：整个平台按顶层分组，分组范围按直属下级分组，另加“未分组”或“本组直属案例”。
  const rows: MatrixRow[] = []
  if (scope === "platform" || scope === "group") {
    for (const group of groups.filter((g) => (g.parentId ?? undefined) === selectedGroup)) {
      const ids = new Set([group.id, ...descendantGroupIds(groups, group.id)])
      rows.push({ id: group.id!, label: group.name, navigable: true, points: points.filter((point) => ids.has(point.groupId)) })
    }
    if (scope === "platform" && points.some((point) => point.groupId === 0)) rows.push({ id: 0, label: "未分组", navigable: true, points: points.filter((point) => point.groupId === 0) })
    if (scope === "group" && selectedGroup !== undefined && selectedGroup !== 0 && rows.length && points.some((point) => point.groupId === selectedGroup)) {
      rows.unshift({ id: selectedGroup, label: "本组直属案例", navigable: false, points: points.filter((point) => point.groupId === selectedGroup) })
    }
  }

  const selectedCase = caseId === undefined ? undefined : data.cases.find((item) => item.id === caseId)
  const info = scope === "interface" && interfaceKey ? interfaceInfo(interfaceKey) : undefined
  const crumbs: Array<{ label: string; onClick?: () => void }> = [{ label: "整个平台", onClick: scope === "platform" ? undefined : () => changeScope("platform") }]
  const chain: number[] = []
  for (let id = selectedGroup; id !== undefined && id !== 0; id = groupById.get(id)?.parentId ?? undefined) chain.unshift(id)
  if (selectedGroup === 0) chain.push(0)
  chain.forEach((id) => crumbs.push({ label: id === 0 ? "未分组" : groupById.get(id)?.name ?? "分组", onClick: scope === "group" && id === selectedGroup ? undefined : () => changeScope("group", id) }))
  if (info) crumbs.push({ label: `接口 ${info.name ? `${info.name}（${info.code}）` : info.code}` })
  else if (scope === "interface") crumbs.push({ label: "接口" })
  if (scope === "case") crumbs.push({ label: selectedCase ? `案例 ${selectedCase.caseCode ?? ""} ${selectedCase.name}`.trim() : "案例" })
  const subject = info ? (info.name ?? info.code) : scope === "case" ? selectedCase?.name ?? "案例" : groupPath(selectedGroup)

  const caption = scope === "platform" || (scope === "group" && selectedGroup === undefined) ? "统计全部案例。每个案例每天只计最后一次完整执行；下方按顶层分组拆开。"
    : scope === "group" ? `统计“${groupPath(selectedGroup)}”${selectedGroup === 0 ? "" : "及其下级分组"}的案例。每个案例每天只计最后一次完整执行。`
    : scope === "interface" ? "只看调用过该接口的案例：每个案例每天取最后一次完整执行，统计其中这个接口步骤的结果。"
    : "这个案例每天最后一次完整执行的结果。"

  const tableParams = new URLSearchParams(scopeParams)
  if (scope === "case") tableParams.set("status", "FAILED")
  else if (scope === "interface" && interfaceFilter !== "ALL") {
    tableParams.set("status", "FAILED")
    tableParams.set("field", interfaceFilter)
  } else tableParams.set("byCase", "true")
  const drillParams = new URLSearchParams(scopeParams)
  if (drill?.date) drillParams.set("date", drill.date)
  if (drill?.groupId !== undefined) drillParams.set("groupId", String(drill.groupId))
  const failedParams = new URLSearchParams(drillParams)
  failedParams.set("status", "FAILED")
  const idleParams = new URLSearchParams(drillParams)
  idleParams.delete("interfaceKey")
  const reload = () => { if (preset) changeRange(recentRange(preset)); setRefresh((value) => value + 1) }
  const open = (base: Omit<Drill, "tab">) => setDrill({ ...base, notRun: scope === "interface" ? 0 : base.notRun, tab: base.failed ? "failed" : "notRun" })
  const pickDay = (date: string) => {
    const counts = daily.find((day) => day.date === date)?.counts ?? {}
    open({ date, label: subject, failed: counts.FAILED ?? 0, notRun: counts.NOT_RUN ?? 0 })
  }
  const pickTile = (date: string, row: MatrixRow) => {
    const sum = (outcome: Outcome) => row.points.filter((point) => point.date === date && point.outcome === outcome).reduce((total, point) => total + point.count, 0)
    open({ date, groupId: row.id, direct: !row.navigable, label: row.navigable ? groupPath(row.id) : `${groupPath(selectedGroup)}的直属案例`, failed: sum("FAILED"), notRun: sum("NOT_RUN") })
  }
  const openCase = (row: NotRunRow) => changeScope("case", selectedGroup, row.caseId)
  const openLast = (row: NotRunRow) => { if (row.lastExecutionId) void showDetail({ executionId: row.lastExecutionId } as StatisticsRow) }

  let hero: ReactNode
  if (!days.length) hero = <p role="alert" className="rounded-lg bg-st-fail-tint/60 px-4 py-3 text-sm text-st-fail-ink">日期范围无效：开始日期不能晚于结束日期，且不超过 366 天。</p>
  else if (scope === "interface" && !interfaceKey) hero = <Choose text={interfaceError ? `接口列表读取失败：${errorSummary(interfaceError)}` : interfaces && !interfaces.keys.length ? "这个日期和分组范围内，没有调用过接口的完整执行。换一个日期或分组再看。" : "选择一个接口，查看调用它的案例每天的结果：接口步骤本身成功还是失败，案例整体又如何。"} action="选择接口" onAction={() => setPicker("interface")} />
  else if (scope === "case" && caseId === undefined) hero = <Choose text="选择一个案例，查看它每天最后一次完整执行的结果和失败记录。" action="选择案例" onAction={() => setPicker("case")} />
  else if (error) hero = <ErrorNote title="统计查询失败" message={error} onRetry={() => setRefresh((value) => value + 1)} />
  else if (!overview) hero = <HeroSkeleton />
  else hero = <>
    <h2 className="font-num text-[1.75rem] leading-9 font-semibold tracking-[-0.01em] text-balance text-st-ink">{headline(scope, daily, range.from, range.to, today)}</h2>
    <p className="mt-1.5 max-w-[48rem] text-sm leading-6 text-st-muted">{caption}</p>
    <div className="mt-6"><StatsRow counts={totals} scope={scope} from={range.from} to={range.to} /></div>
    {scope !== "interface" && neverRun > 0 && <p className="mt-4 flex flex-wrap items-center gap-x-2 gap-y-1 text-[13px] text-st-muted"><Info aria-hidden="true" className="size-4 text-st-faint" />
      另有 <span className="font-num font-semibold text-st-ink">{neverRun.toLocaleString()}</span> 个已启用的案例从未执行过，不计入每日“未执行”。
      <button type="button" onClick={() => open({ label: subject, failed: 0, notRun: neverRun })} className="rounded font-medium text-primary outline-none hover:underline hover:underline-offset-4 focus-visible:ring-2 focus-visible:ring-ring/60">查看这些案例</button></p>}
    {scope === "interface" && !!interfaces?.runsWithoutSnapshot && <p className="mt-4 flex items-center gap-2 text-[13px] text-st-muted"><Info aria-hidden="true" className="size-4 text-st-faint" />范围内有 {interfaces.runsWithoutSnapshot} 次完整执行缺少历史快照，无法识别调用的接口，没有计入。</p>}
    <div className="mt-6">
      {scope === "case"
        ? <><div className="mb-3 flex justify-end"><StripLegend /></div><ResultMatrix variant="strip" rows={[{ id: caseId!, label: subject, navigable: false, points }]} days={days} today={today} onPick={(date) => pickDay(date)} /></>
        : <DailyColumns data={daily} today={today} label={subject} onPick={pickDay} pickNotRun={scope !== "interface"} />}
    </div>
  </>

  const showTable = !!overview && ready
  return <div className="st-board min-h-0 flex-1 overflow-auto">
    <div className="z-20 flex flex-wrap items-center gap-x-6 gap-y-2 bg-background pb-4 md:sticky md:top-0">
      <div className="flex min-w-0 flex-[1_1_auto] flex-wrap items-center gap-2">
        <Segmented label="统计范围" value={scope} options={scopeOptions} onChange={(value) => changeScope(value, value === "platform" ? undefined : selectedGroup)} />
        {scope !== "platform" && <GroupPicker groups={data.groups} cases={data.cases} value={groupId} onChange={(id) => changeScope(scope, id)} />}
        {scope === "interface" && <InterfacePicker interfaces={interfaces} error={interfaceError} value={interfaceKey}
          open={picker === "interface"} onOpenChange={(open) => setPicker(open ? "interface" : undefined)}
          onChange={(key) => { setInterfaceKey(key); setDrill(undefined); setInterfaceFilter("ALL") }} />}
        {scope === "case" && <CasePicker cases={cases} groups={data.groups} value={caseId}
          open={picker === "case"} onOpenChange={(open) => setPicker(open ? "case" : undefined)}
          onChange={(id) => { setCaseId(id); setDrill(undefined) }} />}
      </div>
      <div className="ml-auto flex min-w-0 flex-wrap items-center gap-2">
        <DateRangePicker range={range} preset={preset} onPreset={(value) => { setPreset(value); changeRange(recentRange(value)) }} onRange={(value) => { setPreset(0); changeRange(value) }} />
        <button type="button" onClick={reload} aria-label="刷新统计" title="刷新统计"
          className="grid size-9 place-items-center rounded-lg border border-st-rule bg-white text-st-muted shadow-[0_1px_1px_rgba(26,34,50,0.04)] outline-none hover:border-st-line hover:text-st-ink focus-visible:ring-2 focus-visible:ring-ring/60">
          <RotateCw className="size-4" />
        </button>
      </div>
    </div>

    <section aria-label="统计结论" className="pb-8">
      <div className="mb-3 flex items-center justify-between gap-4">
        {scope === "platform" ? <span /> : <Crumbs items={crumbs} />}
        <MethodNote scope={scope} />
      </div>
      {hero}
    </section>

    {showTable && rows.length > 0 && <Panel labelledBy="stats-groups" className="mb-5" title={selectedGroup === undefined ? "各分组每日结果" : "下级分组每日结果"}
      description="点分组名进入该分组。每天的色块按各结果数量占比分段，绿色为成功、红色为失败、斜纹为未执行，格内显示通过率；有失败或未执行时可点击查看明细，右侧是区间合计。"
      aside={<MatrixLegend />}>
      <ResultMatrix rows={rows} days={days} today={today} onRow={(id) => changeScope("group", id)} onPick={pickTile} />
    </Panel>}

    {showTable && <Panel labelledBy="stats-cases" className="mb-2"
      title={scope === "case" ? "失败记录" : scope === "interface" ? interfaceFilter === "outcome" ? "接口失败记录" : interfaceFilter === "case" ? "案例失败记录" : "调用该接口的案例" : "案例结果"}
      description={scope === "case" ? "这个案例在所选日期内失败的执行，按时间倒序。点一条查看执行详情。"
        : scope === "interface" ? interfaceFilter === "outcome" ? "该接口步骤失败的执行，按时间倒序。" : interfaceFilter === "case" ? "调用了该接口、案例整体失败的执行，按时间倒序。" : "每个案例一行，接口失败天数多的排在前面。点案例或执行编号查看最近一次执行。"
        : "区间内有结果的案例，失败天数多的排在前面。点案例名查看它每天的结果，点执行编号查看那次执行。"}
      aside={scope === "interface" ? <Segmented label="筛选" value={interfaceFilter} options={filterOptions} onChange={setInterfaceFilter} /> : undefined}>
      {scope === "case" || (scope === "interface" && interfaceFilter !== "ALL")
        ? <FailureList key={`${tableParams}:${refresh}`} query={tableParams.toString()} interfaceScope={scope === "interface"} onOpen={showDetail}
            emptyText={scope === "case" ? "所选日期内这个案例没有失败。" : "所选日期内没有这类失败。"} />
        : <CaseSummaryTable key={`${tableParams}:${refresh}`} query={tableParams.toString()} interfaceScope={scope === "interface"} onOpen={showDetail}
            onCase={scope === "interface" ? undefined : (id) => changeScope("case", selectedGroup, id)} emptyText="所选日期内没有案例结果。" />}
    </Panel>}

    <FailureDrawer open={!!drill} date={drill?.date} scopeLabel={drill?.label ?? ""} failed={drill?.failed} notRun={drill?.notRun} tab={drill?.tab ?? "failed"}
      onTab={(tab) => setDrill((current) => current && { ...current, tab })} onClose={() => setDrill(undefined)}>
      {drill && (drill.tab === "failed"
        ? <FailureList key={`f:${failedParams}:${refresh}`} query={failedParams.toString()} interfaceScope={scope === "interface"} onOpen={showDetail}
            onlyGroupId={drill.direct ? drill.groupId : undefined} emptyText="当天没有失败的案例。" />
        : <NotRunList key={`n:${idleParams}:${refresh}`} query={idleParams.toString()} onCase={openCase} onOpenExecution={openLast}
            onlyGroupId={drill.direct ? drill.groupId : undefined} emptyText={drill.date ? "当天没有没跑的案例。" : "没有从未执行过的案例。"} />)}
    </FailureDrawer>
    <ExecutionResultDialog data={data} detail={detail?.execution} initialStepId={detail?.stepId} open={!!detail} onOpenChange={(open) => { if (!open) setDetail(undefined) }} />
  </div>
}
