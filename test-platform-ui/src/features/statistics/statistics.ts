
export const outcomes = ["PASSED", "FAILED", "WARN", "INTERRUPTED", "NOT_RUN"] as const
export type Outcome = (typeof outcomes)[number]
export type Counts = Partial<Record<Outcome, number>>
export const outcomeLabels: Record<Outcome, string> = { PASSED: "成功", FAILED: "失败", WARN: "警告", INTERRUPTED: "中断", NOT_RUN: "未执行" }
export type Point = { date: string; groupId: number; outcome: Outcome; count: number }
export type IdlePoint = { date: string; groupId: number; count: number }
/** notRun / neverRun 只在案例范围（非接口范围）返回。 */
export type StatisticsOverview = { points: Point[]; notRun?: IdlePoint[]; neverRun?: Array<{ groupId: number; count: number }> }
export type NotRunRow = { caseId: number; groupId: number; caseCode: string; caseName: string; lastExecutionId?: number; lastFinishedAt?: string; lastStatus?: Outcome }
export type NotRunPage = { items: NotRunRow[]; page: number; pageSize: number; total: number }
export type InterfaceOptions = { keys: string[]; runsWithoutSnapshot: number }
export type StatisticsRow = { caseId: number; groupId?: number; caseCode: string; caseName: string; date: string; finishedAt: string; executionId: number; outcome: Outcome; caseStatus: Outcome; counts?: Counts; caseFailed?: number; failedStepId?: number; failedSortNo?: number; failedCaseEnd?: boolean; errorMessage?: string }
export type StatisticsDetails = { items: StatisticsRow[]; page: number; pageSize: number; total: number }
export type DayCounts = { date: string; counts: Counts; total: number }

/** 柱子的堆叠顺序：自下而上是有结果的部分（失败在其顶端），没跑的部分垫在最上面。 */
export const stackOrder: Outcome[] = ["PASSED", "INTERRUPTED", "WARN", "FAILED", "NOT_RUN"]
/** 结果格取“最差结果”的优先级。 */
const severity: Outcome[] = ["FAILED", "WARN", "INTERRUPTED", "NOT_RUN", "PASSED"]

const ymd = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit" })
const clock = new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" })
const DAY = 86400000
const weekdays = ["日", "一", "二", "三", "四", "五", "六"]

export const beijingToday = () => ymd.format(Date.now())

export function recentRange(days: number) {
  const now = Date.now()
  return { from: ymd.format(now - (days - 1) * DAY), to: ymd.format(now) }
}

export function dayRange(from: string, to: string) {
  const start = Date.parse(from)
  const end = Date.parse(to)
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start || (end - start) / DAY >= 366) return []
  return Array.from({ length: (end - start) / DAY + 1 }, (_, i) => new Date(start + i * DAY).toISOString().slice(0, 10))
}

export const shiftDay = (date: string, days: number) => new Date(Date.parse(date) + days * DAY).toISOString().slice(0, 10)
export const weekday = (date: string) => new Date(`${date}T00:00:00Z`).getUTCDay()
export const isWeekend = (date: string) => weekday(date) === 0 || weekday(date) === 6
export const weekdayLabel = (date: string) => `周${weekdays[weekday(date)]}`
export const monthDay = (date: string) => `${Number(date.slice(5, 7))}月${Number(date.slice(8, 10))}日`
export const shortDate = (date: string) => `${date.slice(5, 7)}/${date.slice(8, 10)}`

/** 结论句里的日期：今天、昨天，其余写月日。 */
export function spokenDay(date: string, today = beijingToday()) {
  if (date === today) return "今天"
  if (date === shiftDay(today, -1)) return "昨天"
  return date.slice(0, 4) === today.slice(0, 4) ? monthDay(date) : `${date.slice(0, 4)}年${monthDay(date)}`
}

export function rangeLabel(from: string, to: string, today = beijingToday()) {
  const sameYear = from.slice(0, 4) === to.slice(0, 4)
  const start = sameYear && from.slice(0, 4) === today.slice(0, 4) ? monthDay(from) : `${from.slice(0, 4)}年${monthDay(from)}`
  const end = to.slice(0, 7) === from.slice(0, 7) ? `${Number(to.slice(8, 10))}日` : sameYear ? monthDay(to) : `${to.slice(0, 4)}年${monthDay(to)}`
  return from === to ? start : `${start}至${end}`
}

/** 完成时间：同年省略年份。 */
export function finishedLabel(value: string, today = beijingToday()) {
  const parts = Object.fromEntries(clock.formatToParts(new Date(value)).map((part) => [part.type, part.value]))
  const date = `${parts.month}-${parts.day}`
  return `${parts.year === today.slice(0, 4) ? date : `${parts.year}-${date}`} ${parts.hour}:${parts.minute}`
}
export const fullTime = (value: string) => clock.format(new Date(value))

export function countPoints(points: Point[]) {
  const counts: Counts = {}
  for (const point of points) counts[point.outcome] = (counts[point.outcome] ?? 0) + point.count
  return counts
}

export const sumCounts = (counts: Counts) => outcomes.reduce((sum, outcome) => sum + (counts[outcome] ?? 0), 0)

/** 把“没跑的案例”并入结果点，统一按 NOT_RUN 参与柱子、结果格和合计。 */
export const withNotRun = (points: Point[], notRun: IdlePoint[] = []) =>
  notRun.length ? [...points, ...notRun.map((item): Point => ({ ...item, outcome: "NOT_RUN" }))] : points

/** 有结果的数量（不含没跑）。 */
export const resultCount = (counts: Counts) => sumCounts(counts) - (counts.NOT_RUN ?? 0)

export function dailyCounts(days: string[], points: Point[]): DayCounts[] {
  const byDay = new Map<string, Counts>()
  for (const point of points) {
    const counts = byDay.get(point.date) ?? {}
    counts[point.outcome] = (counts[point.outcome] ?? 0) + point.count
    byDay.set(point.date, counts)
  }
  return days.map((date) => {
    const counts = byDay.get(date) ?? {}
    return { date, counts, total: sumCounts(counts) }
  })
}

export const worstOutcome = (counts: Counts) => severity.find((outcome) => (counts[outcome] ?? 0) > 0)

/** 通过率 = 成功 ÷ 有结果的总数（“未执行”不算结果）。 */
export function passRate(counts: Counts) {
  const total = sumCounts(counts) - (counts.NOT_RUN ?? 0)
  return total ? (counts.PASSED ?? 0) / total : undefined
}

export const percent = (value?: number) => value === undefined ? "—" : `${(Math.floor(value * 1000) / 10).toFixed(value === 1 ? 0 : 1)}%`

export type InterfaceInfo = { key: string; kind: "HTTP" | "UPLOAD"; code: string; name?: string; service?: string; host: string }

/** 接口标识使用执行时冻结的连接、方法和路径。 */
export function interfaceInfo(key: string): InterfaceInfo {
  const [type, scope, name, id] = JSON.parse(key) as string[]
  if (type === "HTTP") return { key, kind: "HTTP", code: `${name} ${id}`, host: scope }
  return { key, kind: "UPLOAD", code: name, host: scope }
}

/** 只读查询失败时自动重试一次，消化连接被数据库或服务重启断开这类偶发错误。 */
export async function retryOnce<T>(load: () => Promise<T>, signal: AbortSignal) {
  try {
    return await load()
  } catch (error) {
    if (signal.aborted) throw error
    await new Promise((resolve) => setTimeout(resolve, 800))
    if (signal.aborted) throw error
    return load()
  }
}

/** 后端报错常带整段 SQL：只保留 SQL 之后的原因，完整内容放进“错误详情”。 */
export function errorSummary(message: string) {
  const reason = message.replace(/^[\w.]*Callback; (?:bad )?SQL \[[\s\S]*?\]; /, "").replace(/; nested exception is [\s\S]*$/, "").trim()
  return reason.length > 160 ? `${reason.slice(0, 160)}…` : reason
}

export const interfaceKinds: Record<InterfaceInfo["kind"], string> = { HTTP: "HTTP 接口", UPLOAD: "文件上传" }

/** 各结果的实色样式，用于色块、柱段和分布条。 */
export const swatchClass: Record<Outcome, string> = {
  PASSED: "bg-st-pass",
  FAILED: "bg-st-fail",
  WARN: "bg-st-warn",
  INTERRUPTED: "bg-st-halt",
  NOT_RUN: "st-hatch",
}

/** 日期刻度：天数少时每天都标，多了按固定间隔从最后一天往前标。 */
export function labelledDays(count: number) {
  if (count <= 14) return () => true
  const every = Math.ceil(count / 10)
  return (index: number) => (count - 1 - index) % every === 0
}
