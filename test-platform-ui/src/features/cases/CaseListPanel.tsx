import { useEffect, useState } from "react"
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight, LoaderCircle, Search } from "lucide-react"
import { toast } from "sonner"
import { OverflowTooltip } from "@/components/shared/overflow-tooltip"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { CaseColumnHeader } from "@/features/cases/CaseColumnHeader"
import { GroupTreeSelect } from "@/features/cases/GroupTreeSelect"
import { formatTime } from "@/lib/platform-api"
import { cn } from "@/lib/utils"
import type { CaseGroup, CasePage, CaseQuery, CaseSort, Id, TestCase } from "@/types/platform"

const pageSizeOptions = [20, 50, 100, 200, 500]
const caseGridClass = "grid grid-cols-[minmax(0,1.1fr)_minmax(7rem,1.4fr)_9rem_5rem] items-stretch"

function LatestStatusDot({ status }: { status?: string }) {
  if (status === "PASSED") return <span title="最近执行通过" className="size-2 shrink-0 rounded-full bg-primary" />
  if (status === "WARN") return <span title="最近执行有警告" className="size-2 shrink-0 rounded-full bg-amber-500" />
  if (status === "INTERRUPTED") return <span title="最近执行中断" className="size-2 shrink-0 rounded-full bg-destructive" />
  if (status === "FAILED") return <span title="最近执行失败" className="size-2 shrink-0 rounded-full bg-destructive" />
  if (status === "RUNNING" || status === "QUEUED") return <span title="执行中" className="size-2 shrink-0 animate-pulse rounded-full bg-primary/60" />
  return <span title="未执行" className="size-2 shrink-0 rounded-full bg-muted-foreground/30" />
}

export function CaseListPanel({ casePage, groups, selectedCaseId, loading, checkedCases, selectionLocked, onCheckedCasesChange, onSelect, onQuery, onPageChange, onPageSizeChange }: { casePage: CasePage; groups: CaseGroup[]; selectedCaseId?: Id; loading: boolean; checkedCases: TestCase[]; selectionLocked: boolean; onCheckedCasesChange: (cases: TestCase[]) => void; onSelect: (id: Id) => void; onQuery: (query: CaseQuery) => void; onPageChange: (page: number) => void; onPageSizeChange: (pageSize: number) => void }) {
  const [keyword, setKeyword] = useState("")
  const [groupId, setGroupId] = useState<Id>()
  const [enabled, setEnabled] = useState("ALL")
  const [tableQuery, setTableQuery] = useState<Partial<CaseQuery>>({})
  const [pageSizeInput, setPageSizeInput] = useState(String(casePage.pageSize))
  const [customPageSize, setCustomPageSize] = useState(!pageSizeOptions.includes(casePage.pageSize))
  const pageCount = Math.max(Math.ceil(casePage.total / casePage.pageSize), 1)
  const checkedIds = new Set(checkedCases.map((item) => item.id))
  const selectableCases = casePage.items.filter((item) => item.id !== undefined)
  const allPageChecked = selectableCases.length > 0 && selectableCases.every((item) => checkedIds.has(item.id))
  const somePageChecked = selectableCases.some((item) => checkedIds.has(item.id))

  useEffect(() => {
    setPageSizeInput(String(casePage.pageSize))
    setCustomPageSize(!pageSizeOptions.includes(casePage.pageSize))
  }, [casePage.pageSize])

  const query: CaseQuery = { keyword: keyword.trim(), groupId, enabled: enabled === "ALL" ? undefined : enabled === "ENABLED", ...tableQuery }
  const submit = (patch: Partial<CaseQuery> = {}) => {
    const next = { ...query, ...patch }
    if (next.executedFrom && next.executedTo && next.executedFrom > next.executedTo) {
      toast.error("执行开始日期不能晚于结束日期")
      return
    }
    setTableQuery({ caseCode: next.caseCode, caseName: next.caseName, executedFrom: next.executedFrom, executedTo: next.executedTo, latestStatus: next.latestStatus, sortBy: next.sortBy, sortDirection: next.sortDirection })
    onQuery(next)
  }
  const resetColumn = (column: CaseSort, patch: Partial<CaseQuery>) => submit({ ...patch, ...(tableQuery.sortBy === column ? { sortBy: undefined, sortDirection: undefined } : {}) })
  const commitPageSize = () => {
    const value = Number(pageSizeInput)
    if (!Number.isInteger(value) || value < 1 || value > 500) {
      setPageSizeInput(String(casePage.pageSize))
      toast.error("每页条数必须是 1 到 500 的整数")
      return
    }
    if (value !== casePage.pageSize) onPageSizeChange(value)
  }

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col gap-3">
      <form className="flex shrink-0 flex-wrap items-center gap-2" onSubmit={(event) => { event.preventDefault(); submit() }}>
        <Input aria-label="检索案例" placeholder="搜索案例编号、名称或说明" value={keyword} onChange={(event) => setKeyword(event.target.value)} className="min-w-40 flex-1" />
        <GroupTreeSelect groups={groups} value={groupId} onValueChange={setGroupId} />
        <Select value={enabled} onValueChange={setEnabled}>
          <SelectTrigger className="w-28"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">全部状态</SelectItem>
            <SelectItem value="ENABLED">已启用</SelectItem>
            <SelectItem value="DISABLED">已停用</SelectItem>
          </SelectContent>
        </Select>
        <Button type="submit" variant="outline" disabled={loading}><Search />查询</Button>
      </form>
      <div className="relative min-h-0 min-w-0 flex-1 rounded-md border bg-background">
        <ScrollArea className={cn("h-full", loading && "opacity-60")}>
          <div className="grid">
            <div className="sticky top-0 z-10 flex border-b bg-muted text-left text-xs text-muted-foreground">
              <span className="flex w-9 shrink-0 items-center justify-center border-r"><Checkbox aria-label="全选当前页案例" checked={allPageChecked ? true : somePageChecked ? "indeterminate" : false} disabled={loading || selectionLocked || !selectableCases.length} onCheckedChange={(checked) => onCheckedCasesChange(checked === true ? [...checkedCases, ...selectableCases.filter((item) => !checkedIds.has(item.id))] : checkedCases.filter((item) => !selectableCases.some((pageItem) => pageItem.id === item.id)))} /></span>
              <div className={cn(caseGridClass, "min-w-0 flex-1")}>
              <CaseColumnHeader title="案例编号 / 名称" column={tableQuery.sortBy === "NAME" ? "NAME" : "CASE_CODE"} query={query} active={!!(tableQuery.caseCode || tableQuery.caseName)} loading={loading} onChange={submit} onApply={() => submit()} onClear={() => { resetColumn(tableQuery.sortBy === "NAME" ? "NAME" : "CASE_CODE", { caseCode: undefined, caseName: undefined }) }}>
                <label className="grid gap-1.5 text-xs text-muted-foreground">案例编号<Input aria-label="筛选案例编号" placeholder="输入编号关键词" value={tableQuery.caseCode || ""} onChange={(event) => setTableQuery({ ...tableQuery, caseCode: event.target.value })} onKeyDown={(event) => { if (event.key === "Enter") submit() }} /></label>
                <label className="grid gap-1.5 text-xs text-muted-foreground">案例名称<Input aria-label="筛选案例名称" placeholder="输入名称关键词" value={tableQuery.caseName || ""} onChange={(event) => setTableQuery({ ...tableQuery, caseName: event.target.value })} onKeyDown={(event) => { if (event.key === "Enter") submit() }} /></label>
                <label className="grid gap-1.5 text-xs text-muted-foreground">排序依据<Select value={tableQuery.sortBy === "NAME" ? "NAME" : "CASE_CODE"} onValueChange={(value) => submit({ sortBy: value as CaseSort, sortDirection: tableQuery.sortDirection || "ASC" })}><SelectTrigger aria-label="案例排序依据" className="w-full"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="CASE_CODE">案例编号</SelectItem><SelectItem value="NAME">案例名称</SelectItem></SelectContent></Select></label>
              </CaseColumnHeader>
              <CaseColumnHeader title="分组" column="GROUP" query={query} active={groupId !== undefined} loading={loading} onChange={submit} onApply={() => submit()} onClear={() => { setGroupId(undefined); resetColumn("GROUP", { groupId: undefined }) }}>
                <div className="grid gap-1.5"><span className="text-xs text-muted-foreground">分组范围（含子分组）</span><GroupTreeSelect groups={groups} value={groupId} onValueChange={setGroupId} /></div>
              </CaseColumnHeader>
              <CaseColumnHeader title="最近执行" column="LATEST_EXECUTION" query={query} active={!!(tableQuery.executedFrom || tableQuery.executedTo)} loading={loading} onChange={submit} onApply={() => submit()} onClear={() => resetColumn("LATEST_EXECUTION", { executedFrom: undefined, executedTo: undefined })}>
                <label className="grid gap-1.5 text-xs text-muted-foreground">开始日期<Input aria-label="执行开始日期" type="date" value={tableQuery.executedFrom || ""} onChange={(event) => setTableQuery({ ...tableQuery, executedFrom: event.target.value })} /></label>
                <label className="grid gap-1.5 text-xs text-muted-foreground">结束日期<Input aria-label="执行结束日期" type="date" value={tableQuery.executedTo || ""} onChange={(event) => setTableQuery({ ...tableQuery, executedTo: event.target.value })} /></label>
              </CaseColumnHeader>
              <CaseColumnHeader title="状态" column="STATUS" query={query} active={!!tableQuery.latestStatus} loading={loading} onChange={submit} onApply={() => submit()} onClear={() => resetColumn("STATUS", { latestStatus: undefined })}>
                <label className="grid gap-1.5 text-xs text-muted-foreground">最近执行状态<Select value={tableQuery.latestStatus || "ALL"} onValueChange={(value) => setTableQuery({ ...tableQuery, latestStatus: value === "ALL" ? undefined : value as CaseQuery["latestStatus"] })}><SelectTrigger aria-label="筛选最近执行状态" className="w-full"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="ALL">全部状态</SelectItem><SelectItem value="PASSED">执行通过</SelectItem><SelectItem value="WARN">执行警告</SelectItem><SelectItem value="FAILED">执行失败</SelectItem><SelectItem value="INTERRUPTED">已中断</SelectItem><SelectItem value="RUNNING">执行中</SelectItem><SelectItem value="QUEUED">排队中</SelectItem><SelectItem value="UNEXECUTED">未执行</SelectItem></SelectContent></Select></label>
              </CaseColumnHeader>
              </div>
            </div>
            {casePage.items.map((item) => {
              const latestTime = item.latestExecutionAt
              const latestStatus = item.latestExecutionStatus
              const selected = item.id !== undefined && item.id === selectedCaseId
              return (
                <div key={item.id} className="flex border-b last:border-b-0">
                  <span className="flex w-9 shrink-0 items-center justify-center border-r"><Checkbox aria-label={`选择案例 ${item.caseCode} ${item.name}`} checked={checkedIds.has(item.id)} disabled={selectionLocked || item.id === undefined} onCheckedChange={(checked) => onCheckedCasesChange(checked === true ? [...checkedCases, item] : checkedCases.filter((value) => value.id !== item.id))} /></span>
                <button
                  type="button"
                  onClick={() => item.id !== undefined && onSelect(item.id)}
                  className={cn(
                    "min-w-0 flex-1 text-left transition-colors hover:bg-accent/50",
                    selected && "bg-accent/60 shadow-[inset_2px_0_0_var(--primary)]",
                  )}
                >
                  <div className={caseGridClass}>
                    <span className="flex min-w-0 flex-col justify-center border-r px-3 py-2.5">
                      <span className="flex min-w-0 items-center gap-2 font-mono text-xs text-muted-foreground">
                        <span className="truncate">{item.caseCode || "未分组"}</span>
                        {!item.enabled && <span className="shrink-0 rounded-sm border px-1 text-[10px] leading-4">停用</span>}
                      </span>
                      <OverflowTooltip content={[item.name, item.description || "暂无业务说明"].join("\n")} className="mt-1 block max-w-full">
                        <p className="truncate text-sm font-medium">{item.name}</p>
                      </OverflowTooltip>
                    </span>
                    <span className="flex min-w-0 items-center truncate border-r px-3 py-2.5 text-left text-xs text-muted-foreground" title={item.groupName || "未分组"}>
                      {item.groupCode ? `${item.groupCode} · ` : ""}{item.groupName || "未分组"}
                    </span>
                    <span className="flex min-w-0 items-center truncate border-r px-3 py-2.5 text-left text-xs text-muted-foreground">{latestTime ? formatTime(latestTime) : "未执行"}</span>
                    <span className="flex items-center justify-center px-2 py-2.5"><LatestStatusDot status={latestStatus} /></span>
                  </div>
                </button>
                </div>
              )
            })}
            {!casePage.items.length && <div className="grid place-items-center px-4 py-16 text-sm text-muted-foreground">没有匹配的案例</div>}
          </div>
        </ScrollArea>
        {loading && <div className="absolute inset-0 grid place-items-center bg-background/35"><LoaderCircle className="size-5 animate-spin text-primary" /></div>}
      </div>
      <div className="flex shrink-0 flex-wrap items-center justify-between gap-3 px-1 text-sm">
        <div className="flex items-center gap-2"><span className="text-muted-foreground">共 {casePage.total} 条 · 已选 {checkedCases.length} 条</span><Button variant="ghost" size="sm" disabled={selectionLocked || !checkedCases.length} onClick={() => onCheckedCasesChange([])}>清空</Button></div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-2 text-muted-foreground">
            <span>每页</span>
            <Select value={customPageSize ? "CUSTOM" : String(casePage.pageSize)} onValueChange={(value) => {
              if (value === "CUSTOM") {
                setCustomPageSize(true)
                setPageSizeInput(String(casePage.pageSize))
              } else {
                setCustomPageSize(false)
                onPageSizeChange(Number(value))
              }
            }} disabled={loading}>
              <SelectTrigger id="case-page-size" className="h-8 w-24" aria-label="每页条数"><SelectValue /></SelectTrigger>
              <SelectContent>
                {pageSizeOptions.map((pageSize) => <SelectItem key={pageSize} value={String(pageSize)}>{pageSize}</SelectItem>)}
                <SelectItem value="CUSTOM">自定义</SelectItem>
              </SelectContent>
            </Select>
            {customPageSize && <Input id="case-page-size-custom" type="text" inputMode="numeric" value={pageSizeInput} onChange={(event) => setPageSizeInput(event.target.value)} onBlur={commitPageSize} onKeyDown={(event) => { if (event.key === "Enter") { event.preventDefault(); commitPageSize() } }} className="h-8 w-20 text-center" aria-label="自定义每页条数" disabled={loading} />}
            <span>条</span>
          </div>
          <span className="min-w-28 text-center font-medium">第 {casePage.page} / {pageCount} 页</span>
          <div className="flex items-center gap-1">
            <Button variant="outline" size="icon-sm" className="hidden lg:flex" title="首页" onClick={() => onPageChange(1)} disabled={loading || casePage.page <= 1}><ChevronsLeft /></Button>
            <Button variant="outline" size="icon-sm" title="上一页" onClick={() => onPageChange(casePage.page - 1)} disabled={loading || casePage.page <= 1}><ChevronLeft /></Button>
            <Button variant="outline" size="icon-sm" title="下一页" onClick={() => onPageChange(casePage.page + 1)} disabled={loading || casePage.page >= pageCount}><ChevronRight /></Button>
            <Button variant="outline" size="icon-sm" className="hidden lg:flex" title="末页" onClick={() => onPageChange(pageCount)} disabled={loading || casePage.page >= pageCount}><ChevronsRight /></Button>
          </div>
        </div>
      </div>
    </div>
  )
}
