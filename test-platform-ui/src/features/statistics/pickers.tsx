import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react"
import { Check, ChevronsUpDown, Search } from "lucide-react"
import { Popover } from "radix-ui"
import { cn } from "@/lib/utils"
import { orderedGroups } from "@/features/cases/group-tree"
import type { CaseGroup, Id, TestCase } from "@/types/platform"
import { errorSummary, interfaceInfo, interfaceKinds, type InterfaceOptions } from "./statistics"

export type PickerOption = {
  value: string
  title: string
  /** 编号、功能号等，用窄体数字字体显示在标题后面 */
  code?: string
  detail?: string
  /** 只在搜索时显示的说明，例如分组的完整路径 */
  searchDetail?: string
  aside?: string
  section?: string
  depth?: number
  search: string
}

const LIMIT = 300

/** 带搜索的下拉选择器：输入框过滤，上下键移动，回车选中。 */
export function SearchPicker({ label, value, display, placeholder, options, onChange, searchPlaceholder, noun, status, className, panelClassName, open: controlledOpen, onOpenChange }: {
  label: string; value: string; display?: ReactNode; placeholder: string; options: PickerOption[]; onChange: (value: string) => void
  searchPlaceholder: string; noun: string; status?: string; className?: string; panelClassName?: string
  open?: boolean; onOpenChange?: (open: boolean) => void
}) {
  const [ownOpen, setOwnOpen] = useState(false)
  const open = controlledOpen ?? ownOpen
  const [query, setQuery] = useState("")
  const [active, setActive] = useState(0)
  const list = useRef<HTMLDivElement>(null)
  const id = useId()
  const searching = query.trim().toLocaleLowerCase()
  const matches = useMemo(() => {
    const tokens = searching.split(/\s+/).filter(Boolean)
    return tokens.length ? options.filter((option) => tokens.every((token) => option.search.includes(token))) : options
  }, [options, searching])
  const shown = matches.slice(0, LIMIT)

  // 关闭时清空搜索词，这样无论从触发按钮还是外部按钮打开，都从完整列表开始
  const setOpen = (next: boolean) => {
    if (next) setActive(Math.max(0, options.findIndex((option) => option.value === value)))
    else setQuery("")
    setOwnOpen(next)
    onOpenChange?.(next)
  }
  useEffect(() => {
    if (open) list.current?.querySelector(`[data-index="${active}"]`)?.scrollIntoView({ block: "nearest" })
  }, [active, open])

  const choose = (option?: PickerOption) => {
    if (!option) return
    onChange(option.value)
    setOpen(false)
  }
  const keys = (event: KeyboardEvent<HTMLInputElement>) => {
    const last = shown.length - 1
    const moves: Record<string, number> = { ArrowDown: active + 1, ArrowUp: active - 1, PageDown: active + 8, PageUp: active - 8 }
    if (event.key in moves) setActive(Math.min(Math.max(moves[event.key], 0), Math.max(last, 0)))
    else if (event.key === "Enter") choose(shown[active])
    else return
    event.preventDefault()
  }

  const current = options.find((option) => option.value === value)
  let lastSection: string | undefined
  return <Popover.Root open={open} onOpenChange={setOpen}>
    <Popover.Trigger asChild>
      <button type="button" role="combobox" aria-expanded={open} aria-haspopup="listbox" aria-label={`${label}：${current?.title ?? placeholder}`}
        className={cn("group inline-flex h-9 min-w-0 items-center gap-2 rounded-lg border border-st-rule bg-white pr-2 pl-3 text-left text-sm shadow-[0_1px_1px_rgba(26,34,50,0.04)] transition-colors outline-none hover:border-st-line focus-visible:ring-2 focus-visible:ring-ring/60 data-[state=open]:border-st-ink", className)}>
        <span className="shrink-0 text-xs text-st-muted">{label}</span>
        <span className="min-w-0 flex-1 truncate font-medium text-st-ink">{display ?? current?.title ?? <span className="font-normal text-st-faint">{placeholder}</span>}</span>
        <ChevronsUpDown aria-hidden="true" className="size-3.5 shrink-0 text-st-faint group-hover:text-st-muted" />
      </button>
    </Popover.Trigger>
    <Popover.Portal>
      <Popover.Content align="start" sideOffset={6} collisionPadding={12}
        className={cn("st-pop st-layer z-50 flex max-h-[min(30rem,var(--radix-popover-content-available-height))] w-[24rem] max-w-[calc(100vw-1.5rem)] flex-col overflow-hidden rounded-xl border border-st-rule bg-white text-st-ink shadow-[0_18px_40px_-16px_rgba(26,34,50,0.32),0_2px_6px_rgba(26,34,50,0.06)] outline-none", panelClassName)}>
        <label className="flex shrink-0 items-center gap-2 border-b border-st-rule px-3">
          <Search aria-hidden="true" className="size-4 text-st-faint" />
          <input autoFocus value={query} onChange={(event) => { setQuery(event.target.value); setActive(0) }} onKeyDown={keys} placeholder={searchPlaceholder}
            role="combobox" aria-expanded="true" aria-controls={`${id}-list`} aria-autocomplete="list" aria-activedescendant={shown[active] ? `${id}-${active}` : undefined} aria-label={`搜索${label}`}
            className="h-11 min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-st-faint" />
        </label>
        {status ? <p className="px-4 py-6 text-center text-sm text-st-muted">{status}</p>
          : <div ref={list} id={`${id}-list`} role="listbox" aria-label={label} className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-1.5">
            {!shown.length && <p className="px-3 py-6 text-center text-sm text-st-muted">没有匹配“{query.trim()}”的{noun}</p>}
            {shown.map((option, index) => {
              const header = option.section !== undefined && option.section !== lastSection ? option.section : undefined
              lastSection = option.section
              const selected = option.value === value
              return <div key={option.value} role="presentation">
                {header && <div role="presentation" className={cn("px-2.5 pt-2 pb-1 text-xs text-st-faint", index > 0 && "mt-1 border-t border-st-rule pt-2.5")}>{header}</div>}
                <div id={`${id}-${index}`} data-index={index} role="option" aria-selected={selected} onPointerMove={() => setActive(index)} onClick={() => choose(option)}
                  className={cn("flex cursor-pointer items-center gap-2 rounded-md py-1.5 pr-2 text-sm", index === active && "bg-st-wash")}
                  style={{ paddingLeft: 10 + (searching ? 0 : option.depth ?? 0) * 16 }}>
                  <div className="min-w-0 flex-1">
                    <div className="flex min-w-0 items-baseline gap-2">
                      <span className={cn("truncate", selected ? "font-semibold" : "text-st-ink")}>{option.title}</span>
                      {option.code && <span className="min-w-0 truncate font-num text-[13px] text-st-muted">{option.code}</span>}
                    </div>
                    {(searching ? option.searchDetail ?? option.detail : option.detail) && <div className="truncate text-xs leading-5 text-st-faint">{searching ? option.searchDetail ?? option.detail : option.detail}</div>}
                  </div>
                  {option.aside && <span className="shrink-0 font-num text-xs text-st-faint tabular-nums">{option.aside}</span>}
                  <Check aria-hidden="true" className={cn("size-4 shrink-0 text-primary", !selected && "invisible")} />
                </div>
              </div>
            })}
          </div>}
        {!status && <div className="shrink-0 border-t border-st-rule px-3 py-1.5 text-xs text-st-faint">
          {searching ? `匹配 ${matches.length} 个${noun}` : `共 ${options.length} 个${noun}`}{matches.length > LIMIT ? `，只列出前 ${LIMIT} 个，继续输入可缩小范围` : ""}
        </div>}
      </Popover.Content>
    </Popover.Portal>
  </Popover.Root>
}

const ALL = ""
const UNGROUPED = "0"

/** 分组选择：树形缩进，显示子树内案例数，搜索时展示完整路径。 */
export function GroupPicker({ groups, cases, value, onChange }: { groups: CaseGroup[]; cases: TestCase[]; value?: Id; onChange: (value?: Id) => void }) {
  const options = useMemo(() => {
    const ordered = orderedGroups(groups)
    const parent = new Map(ordered.map((group) => [group.id, group.parentId]))
    const totals = new Map<Id, number>()
    let ungrouped = 0
    for (const item of cases) {
      if (item.groupId === undefined || item.groupId === null) { ungrouped++; continue }
      for (let id: Id | undefined = item.groupId; id !== undefined && id !== null; id = parent.get(id) ?? undefined) totals.set(id, (totals.get(id) ?? 0) + 1)
    }
    const depth = (group: CaseGroup) => { let level = 0; for (let id = group.parentId; id !== undefined && id !== null; id = parent.get(id) ?? undefined) level++; return level }
    const values: PickerOption[] = [{ value: ALL, title: "全部分组", aside: cases.length ? `${cases.length} 例` : undefined, search: "全部分组" }]
    for (const group of ordered) {
      if (group.id === undefined) continue
      const path = group.fullPath || group.name
      values.push({ value: String(group.id), title: group.name, searchDetail: path !== group.name ? path : undefined, depth: depth(group), aside: totals.get(group.id) ? `${totals.get(group.id)} 例` : undefined, search: [group.name, path, group.groupCode].filter(Boolean).join(" ").toLocaleLowerCase() })
    }
    values.push({ value: UNGROUPED, title: "未分组", aside: ungrouped ? `${ungrouped} 例` : undefined, search: "未分组" })
    return values
  }, [groups, cases])
  return <SearchPicker label="分组" noun="分组" placeholder="全部分组" searchPlaceholder="按分组名称或路径搜索" options={options} className="min-w-40 flex-1 basis-44 sm:max-w-60"
    value={value === undefined ? ALL : String(value)} onChange={(next) => onChange(next === ALL ? undefined : Number(next))} />
}

/** 接口选择按 HTTP 和文件上传归类。 */
export function InterfacePicker({ interfaces, error, value, onChange, open, onOpenChange }: {
  interfaces?: InterfaceOptions; error?: string; value: string; onChange: (value: string) => void; open?: boolean; onOpenChange?: (open: boolean) => void
}) {
  const options = useMemo(() => {
    const kinds = Object.keys(interfaceKinds)
    return (interfaces?.keys ?? []).map((key) => interfaceInfo(key)).sort((a, b) => kinds.indexOf(a.kind) - kinds.indexOf(b.kind) || a.code.localeCompare(b.code)).map((info): PickerOption => ({
      value: info.key,
      title: info.name ?? info.code,
      code: info.name ? info.code : undefined,
      detail: info.service ? `${info.service}，${info.host}` : `连接 ${info.host}`,
      section: interfaceKinds[info.kind],
      search: [info.code, info.name, info.service, info.host].filter(Boolean).join(" ").toLocaleLowerCase(),
    }))
  }, [interfaces])
  const status = error ? `接口列表读取失败：${errorSummary(error)}` : interfaces === undefined ? "正在读取这个范围内调用过的接口…" : !interfaces.keys.length ? "这个日期和分组范围内，没有调用过接口的完整执行" : undefined
  return <SearchPicker label="接口" noun="接口" placeholder="选择接口" searchPlaceholder="按请求方法、路径或连接搜索" options={options} status={status}
    value={value} onChange={onChange} open={open} onOpenChange={onOpenChange} className="min-w-48 flex-[2_1_14rem] sm:max-w-[24rem]" panelClassName="w-[30rem]" />
}

/** 案例选择：按分组归类，可按编号或名称搜索。 */
export function CasePicker({ cases, groups, value, onChange, open, onOpenChange }: {
  cases: TestCase[]; groups: CaseGroup[]; value?: Id; onChange: (value?: Id) => void; open?: boolean; onOpenChange?: (open: boolean) => void
}) {
  const options = useMemo(() => {
    const order = new Map(orderedGroups(groups).map((group, index) => [group.id, index]))
    const path = new Map(groups.map((group) => [group.id, group.fullPath || group.name]))
    return [...cases].filter((item) => item.id !== undefined)
      .sort((a, b) => (order.get(a.groupId) ?? 1e9) - (order.get(b.groupId) ?? 1e9) || (a.caseNo ?? 0) - (b.caseNo ?? 0))
      .map((item): PickerOption => {
        const section = item.groupId === undefined || item.groupId === null ? "未分组" : path.get(item.groupId) ?? item.groupName ?? "未分组"
        return { value: String(item.id), title: item.name, aside: item.caseCode, section, search: [item.caseCode, item.name, section].filter(Boolean).join(" ").toLocaleLowerCase() }
      })
  }, [cases, groups])
  return <SearchPicker label="案例" noun="案例" placeholder="选择案例" searchPlaceholder="按案例编号或名称搜索" options={options}
    status={cases.length ? undefined : "这个分组下没有案例"} value={value === undefined ? "" : String(value)} onChange={(next) => onChange(next ? Number(next) : undefined)}
    open={open} onOpenChange={onOpenChange} className="min-w-48 flex-[2_1_14rem] sm:max-w-[24rem]" panelClassName="w-[28rem]" />
}
