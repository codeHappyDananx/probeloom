import { useEffect, useState } from "react"
import { ChevronLeft, ChevronRight } from "lucide-react"
import { cn } from "@/lib/utils"
import { api } from "@/lib/platform-api"
import { ErrorNote, ResultChip, SkeletonRows, Swatch } from "./parts"
import { finishedLabel, fullTime, retryOnce, outcomeLabels, stackOrder, sumCounts, swatchClass, type Counts, type NotRunPage, type NotRunRow, type StatisticsDetails, type StatisticsRow } from "./statistics"

/** 分页读取统计列表。query 变化时回到第一页（由调用方用 key 重建）。 */
function usePaged<T extends { page: number }>(path: string, query: string, pageSize?: number) {
  const [page, setPage] = useState(1)
  const [result, setResult] = useState<T>()
  const [error, setError] = useState("")
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setError("")
    void retryOnce(() => api<T>(`${path}?${query}&page=${page}${pageSize ? `&pageSize=${pageSize}` : ""}`, { signal: controller.signal }), controller.signal).then((value) => {
      if (!controller.signal.aborted) setResult(value)
    }).catch((err: Error) => { if (!controller.signal.aborted) setError(err.message) })
    return () => controller.abort()
  }, [path, query, page, pageSize, attempt])
  const loading = !error && (!result || result.page !== page)
  return { page, setPage, result, error, loading, retry: () => setAttempt((value) => value + 1) }
}

const useDetails = (query: string, pageSize?: number) => usePaged<StatisticsDetails>("/api/statistics/details", query, pageSize)

function LoadError({ message, retry }: { message: string; retry: () => void }) {
  return <ErrorNote title="读取失败" message={message} onRetry={retry} className="mx-5 mb-5" />
}

function Pager({ result, page, setPage, loading }: { result: { page: number; pageSize: number; total: number }; page: number; setPage: (page: number) => void; loading: boolean }) {
  const pages = Math.ceil(result.total / result.pageSize)
  if (pages <= 1) return null
  const first = (result.page - 1) * result.pageSize + 1
  const button = "grid size-8 place-items-center rounded-md text-st-muted outline-none hover:bg-st-wash hover:text-st-ink focus-visible:ring-2 focus-visible:ring-ring/60 disabled:opacity-35 disabled:hover:bg-transparent"
  return <div className="flex items-center justify-between gap-4 border-t border-st-rule px-5 py-2 text-xs text-st-muted">
    <span className="font-num tabular-nums">第 {first}–{Math.min(first + result.pageSize - 1, result.total)} 条，共 {result.total} 条</span>
    <div className="flex items-center gap-1">
      <button type="button" aria-label="上一页" disabled={page === 1 || loading} onClick={() => setPage(page - 1)} className={button}><ChevronLeft className="size-4" /></button>
      <span className="min-w-12 text-center font-num text-sm text-st-ink tabular-nums">{page} / {pages}</span>
      <button type="button" aria-label="下一页" disabled={page >= pages || loading} onClick={() => setPage(page + 1)} className={button}><ChevronRight className="size-4" /></button>
    </div>
  </div>
}

/** 一行一个案例的天数分布条，配合数字说明“几天成功、几天失败”。 */
function Distribution({ counts, interfaceScope }: { counts: Counts; interfaceScope: boolean }) {
  const total = sumCounts(counts)
  const parts = stackOrder.filter((outcome) => (counts[outcome] ?? 0) > 0)
  const text = parts.map((outcome) => `${interfaceScope && outcome !== "NOT_RUN" ? "接口" : ""}${outcomeLabels[outcome]} ${counts[outcome]} 天`).join("，")
  return <div className="flex items-center gap-3" aria-label={text} title={text}>
    <span aria-hidden="true" className="flex h-2 w-24 shrink-0 overflow-hidden rounded-full bg-st-wash">
      {parts.map((outcome) => <span key={outcome} className={swatchClass[outcome]} style={{ width: `${(counts[outcome] ?? 0) / total * 100}%` }} />)}
    </span>
    <span aria-hidden="true" className="flex items-center gap-2.5 font-num text-xs text-st-muted tabular-nums">
      {[...parts].reverse().map((outcome) => <span key={outcome} className="flex items-center gap-1"><Swatch outcome={outcome} />{counts[outcome]}</span>)}
    </span>
  </div>
}

const th = "h-9 px-3 text-left text-xs font-normal whitespace-nowrap text-st-muted first:pl-5 last:pr-5"
const td = "px-3 py-2.5 align-middle first:pl-5 last:pr-5"

/** 案例汇总：区间内每个有结果的案例一行，失败天数多的在前（排序由服务端完成）。 */
export function CaseSummaryTable({ query, interfaceScope, onCase, onOpen, emptyText }: {
  query: string; interfaceScope: boolean; onCase?: (id: number) => void; onOpen: (row: StatisticsRow) => void; emptyText: string
}) {
  const { page, setPage, result, error, loading, retry } = useDetails(query)
  if (error) return <LoadError message={error} retry={retry} />
  if (!result) return <SkeletonRows />
  if (!result.items.length) return <p className="px-5 pb-6 text-sm text-st-muted">{emptyText}</p>
  return <>
    <div className={cn("overflow-x-auto transition-opacity", loading && "opacity-60")}>
      <table className={cn("w-full border-collapse text-sm", interfaceScope ? "min-w-[62rem]" : "min-w-[48rem]")}>
        <thead className="border-y border-st-rule bg-st-wash/50">
          <tr>
            <th className={th}>案例</th>
            <th className={cn(th, "text-right")}>{interfaceScope ? "接口失败天数" : "失败天数"}</th>
            {interfaceScope && <th className={cn(th, "text-right")}>案例失败天数</th>}
            <th className={th}>每天的结果</th>
            <th className={th}>{interfaceScope ? "最近一天（接口 / 案例）" : "最近一天"}</th>
            <th className={th}>完成时间</th>
            <th className={cn(th, "text-right")}>执行</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-st-rule/70">
          {result.items.map((row) => {
            const failed = row.counts?.FAILED ?? 0
            return <tr key={row.executionId} className="hover:bg-st-wash/50">
              <td className={cn(td, "max-w-[26rem]")}>
                <button type="button" onClick={() => onCase ? onCase(row.caseId) : onOpen(row)} title={onCase ? "查看这个案例每天的结果" : "打开这次执行的详情"}
                  className="flex max-w-full items-baseline gap-2.5 rounded text-left outline-none hover:underline hover:underline-offset-4 focus-visible:ring-2 focus-visible:ring-ring/60">
                  <span className="shrink-0 font-num text-[13px] text-st-faint tabular-nums">{row.caseCode}</span>
                  <span className="truncate text-st-ink">{row.caseName}</span>
                </button>
              </td>
              <td className={cn(td, "text-right font-num tabular-nums", failed ? "font-semibold text-st-fail-ink" : "text-st-idle")}>{failed}</td>
              {interfaceScope && <td className={cn(td, "text-right font-num tabular-nums", row.caseFailed ? "font-semibold text-st-fail-ink" : "text-st-idle")}>{row.caseFailed ?? 0}</td>}
              <td className={td}>{row.counts && <Distribution counts={row.counts} interfaceScope={interfaceScope} />}</td>
              <td className={td}><div className="flex items-center gap-1.5">{interfaceScope && <ResultChip outcome={row.outcome} prefix="接口" />}<ResultChip outcome={row.caseStatus} prefix={interfaceScope ? "案例" : undefined} /></div></td>
              <td className={cn(td, "font-num whitespace-nowrap text-st-muted tabular-nums")} title={fullTime(row.finishedAt)}>{finishedLabel(row.finishedAt)}</td>
              <td className={cn(td, "text-right")}>
                <button type="button" data-open-execution onClick={() => onOpen(row)} aria-label={`打开执行 ${row.executionId} 的详情`}
                  className="rounded text-primary outline-none hover:underline hover:underline-offset-4 focus-visible:ring-2 focus-visible:ring-ring/60"><span className="font-num text-[13px] tabular-nums">#{row.executionId}</span></button>
              </td>
            </tr>
          })}
        </tbody>
      </table>
    </div>
    <Pager result={result} page={page} setPage={setPage} loading={loading} />
  </>
}

/** 失败记录：一次执行一行，整行可点，打开执行详情并定位到失败步骤。 */
export function FailureList({ query, interfaceScope, onOpen, emptyText, onlyGroupId }: {
  query: string; interfaceScope: boolean; onOpen: (row: StatisticsRow) => void; emptyText: string
  /** 只看某个分组的直属案例（服务端分组条件包含下级分组，这里在一页 100 条内再筛一次）。 */
  onlyGroupId?: number
}) {
  const { page, setPage, result, error, loading, retry } = useDetails(query, onlyGroupId === undefined ? undefined : 100)
  if (error) return <LoadError message={error} retry={retry} />
  if (!result) return <SkeletonRows />
  const items = onlyGroupId === undefined ? result.items : result.items.filter((row) => row.groupId === onlyGroupId)
  if (!items.length) return <p className="px-5 pb-6 text-sm text-st-muted">{emptyText}</p>
  return <>
    <ul className={cn("divide-y divide-st-rule/70 border-t border-st-rule transition-opacity", loading && "opacity-60")}>
      {items.map((row) => <li key={row.executionId}>
        <button type="button" data-open-execution onClick={() => onOpen(row)} aria-label={`${row.caseCode} ${row.caseName}，打开执行 ${row.executionId} 的详情`}
          className="group grid w-full grid-cols-[minmax(0,1fr)_auto] gap-x-6 px-5 py-3 text-left outline-none hover:bg-st-wash/60 focus-visible:bg-st-wash focus-visible:ring-2 focus-visible:ring-ring/60 focus-visible:ring-inset">
          <span className="min-w-0">
            <span className="flex min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1">
              {interfaceScope && <ResultChip outcome={row.outcome} prefix="接口" />}
              <ResultChip outcome={row.caseStatus} prefix={interfaceScope ? "案例" : undefined} />
              <span className="font-num text-[13px] text-st-faint tabular-nums">{row.caseCode}</span>
              <span className="min-w-0 truncate text-sm font-medium text-st-ink group-hover:underline group-hover:underline-offset-4">{row.caseName}</span>
            </span>
            <span className="mt-1.5 line-clamp-2 text-[13px] leading-5 text-st-muted">
              {row.failedSortNo != null && <span className="mr-2 text-st-ink">步骤 {row.failedSortNo + 1}{row.failedCaseEnd ? "（收尾执行）" : ""}</span>}
              {row.errorMessage || (row.failedSortNo == null ? "没有记录失败步骤" : "")}
            </span>
          </span>
          <span className="flex flex-col items-end gap-1 text-right">
            <span className="font-num text-[13px] whitespace-nowrap text-st-muted tabular-nums" title={fullTime(row.finishedAt)}>{finishedLabel(row.finishedAt)}</span>
            <span className="font-num text-[13px] text-primary tabular-nums">#{row.executionId}</span>
          </span>
        </button>
      </li>)}
    </ul>
    {onlyGroupId === undefined ? <Pager result={result} page={page} setPage={setPage} loading={loading} />
      : result.total > result.pageSize && <p className="border-t border-st-rule px-5 py-2 text-xs text-st-muted">当天失败超过 {result.pageSize} 条，这里只列出前 {result.pageSize} 条中的本组直属案例；完整列表请进入该分组查看。</p>}
  </>
}

/** 没跑的案例：每行一个案例，附最后一次完整执行；点案例名查看它每天的结果，点执行编号看那次执行。 */
export function NotRunList({ query, onCase, onOpenExecution, emptyText, onlyGroupId }: {
  query: string; onCase: (row: NotRunRow) => void; onOpenExecution: (row: NotRunRow) => void; emptyText: string; onlyGroupId?: number
}) {
  const { page, setPage, result, error, loading, retry } = usePaged<NotRunPage>("/api/statistics/not-run", query, onlyGroupId === undefined ? undefined : 100)
  if (error) return <LoadError message={error} retry={retry} />
  if (!result) return <SkeletonRows />
  const items = onlyGroupId === undefined ? result.items : result.items.filter((row) => row.groupId === onlyGroupId)
  if (!items.length) return <p className="px-5 pb-6 text-sm text-st-muted">{emptyText}</p>
  return <>
    <ul className={cn("divide-y divide-st-rule/70 border-t border-st-rule transition-opacity", loading && "opacity-60")}>
      {items.map((row) => <li key={row.caseId} className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-6 px-5 py-3 hover:bg-st-wash/60">
        <button type="button" onClick={() => onCase(row)} title="查看这个案例每天的结果"
          className="flex min-w-0 items-baseline gap-2.5 rounded text-left outline-none hover:underline hover:underline-offset-4 focus-visible:ring-2 focus-visible:ring-ring/60">
          <span className="shrink-0 font-num text-[13px] text-st-faint tabular-nums">{row.caseCode}</span>
          <span className="truncate text-sm font-medium text-st-ink">{row.caseName}</span>
        </button>
        {row.lastExecutionId && row.lastFinishedAt && row.lastStatus
          ? <span className="flex items-center gap-3 text-right">
              <span className="text-xs text-st-muted">最后一次完整执行</span>
              <ResultChip outcome={row.lastStatus} />
              <span className="font-num text-[13px] whitespace-nowrap text-st-muted tabular-nums" title={fullTime(row.lastFinishedAt)}>{finishedLabel(row.lastFinishedAt)}</span>
              <button type="button" data-open-execution onClick={() => onOpenExecution(row)} aria-label={`打开执行 ${row.lastExecutionId} 的详情`}
                className="rounded text-primary outline-none hover:underline hover:underline-offset-4 focus-visible:ring-2 focus-visible:ring-ring/60"><span className="font-num text-[13px] tabular-nums">#{row.lastExecutionId}</span></button>
            </span>
          : <span className="text-xs text-st-faint">还没有完整执行过</span>}
      </li>)}
    </ul>
    {onlyGroupId === undefined ? <Pager result={result} page={page} setPage={setPage} loading={loading} />
      : result.total > result.pageSize && <p className="border-t border-st-rule px-5 py-2 text-xs text-st-muted">没跑的超过 {result.pageSize} 个，这里只列出前 {result.pageSize} 个中的本组直属案例；完整列表请进入该分组查看。</p>}
  </>
}
