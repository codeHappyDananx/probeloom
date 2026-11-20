import { useMemo, useRef, useState, type ComponentProps, type KeyboardEvent } from "react"
import { Check, ChevronDown, ChevronRight, Folder, FolderOpen, Folders } from "lucide-react"
import { Popover } from "radix-ui"
import { Button } from "@/components/ui/button"
import { treeRows } from "@/features/cases/group-tree"
import { cn } from "@/lib/utils"
import type { CaseGroup, Id } from "@/types/platform"

type GroupTreeSelectProps = {
  groups: CaseGroup[]
  value?: Id
  onValueChange: (value?: Id) => void
  emptyLabel?: string
  required?: boolean
} & Pick<ComponentProps<typeof Button>, "id" | "className" | "aria-invalid" | "onBlur">

export function GroupTreeSelect({ groups, value, onValueChange, emptyLabel = "全部分组", required = false, className, ...triggerProps }: GroupTreeSelectProps) {
  const [open, setOpen] = useState(false)
  const [expanded, setExpanded] = useState<Set<Id>>(new Set())
  const [focusedId, setFocusedId] = useState<Id>()
  const treeRef = useRef<HTMLDivElement>(null)
  const groupById = useMemo(() => new Map(groups.filter((group) => group.id !== undefined).map((group) => [group.id!, group])), [groups])
  const rows = useMemo(() => treeRows(groups, expanded, "").filter(({ group }) => group.id !== undefined), [groups, expanded])
  const selected = value === undefined ? undefined : groupById.get(value)

  const changeOpen = (next: boolean) => {
    if (next) {
      setExpanded((current) => {
        const ids = new Set(current)
        let parentId = selected?.parentId
        while (parentId !== undefined) {
          ids.add(parentId)
          parentId = groupById.get(parentId)?.parentId
        }
        return ids
      })
      setFocusedId(value ?? (required ? rows[0]?.group.id : undefined))
    }
    setOpen(next)
  }
  const toggle = (id: Id) => setExpanded((current) => {
    const next = new Set(current)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  })
  const select = (id?: Id) => {
    onValueChange(id)
    setOpen(false)
  }
  const focus = (id?: Id) => treeRef.current?.querySelector<HTMLElement>(`[data-group-id="${id ?? "ALL"}"]`)?.focus()
  const navigate = (event: KeyboardEvent<HTMLButtonElement>, id?: Id, hasChildren = false) => {
    const items = Array.from(treeRef.current!.querySelectorAll<HTMLElement>('[role="treeitem"]'))
    const index = items.indexOf(event.currentTarget)
    switch (event.key) {
      case "ArrowDown": items[Math.min(index + 1, items.length - 1)].focus(); break
      case "ArrowUp": items[Math.max(index - 1, 0)].focus(); break
      case "Home": items[0].focus(); break
      case "End": items[items.length - 1].focus(); break
      case "ArrowRight":
        if (id !== undefined && hasChildren) {
          if (expanded.has(id)) items[index + 1]?.focus()
          else toggle(id)
        }
        break
      case "ArrowLeft":
        if (id !== undefined && hasChildren && expanded.has(id)) toggle(id)
        else if (id !== undefined && groupById.get(id)?.parentId !== undefined) focus(groupById.get(id)!.parentId)
        break
      default: return
    }
    event.preventDefault()
  }
  const rowClass = "group relative flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1 text-left text-sm outline-none transition-colors duration-150 hover:bg-accent/60 focus-visible:bg-accent/60 focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring"

  return <Popover.Root modal open={open} onOpenChange={changeOpen}>
    <Popover.Trigger asChild>
      <Button {...triggerProps} type="button" variant="outline" aria-label="选择案例分组" className={cn("w-44 min-w-0 justify-between font-normal", open && "border-primary/50 ring-2 ring-primary/10", className)} title={selected?.fullPath || selected?.name}>
        <Folder className="size-4 text-primary" />
        <span className="min-w-0 flex-1 truncate text-left">{selected?.name || (required ? "请选择分组" : emptyLabel)}</span>
        <ChevronDown className={cn("size-4 text-muted-foreground transition-transform duration-150 motion-reduce:transition-none", open && "rotate-180")} />
      </Button>
    </Popover.Trigger>
    <Popover.Portal>
      <Popover.Content align="start" sideOffset={6} collisionPadding={12} aria-label="选择案例分组" className="z-50 flex max-h-[min(30rem,var(--radix-popover-content-available-height))] w-[23rem] max-w-[calc(100vw-1.5rem)] flex-col overflow-hidden rounded-lg border bg-popover text-popover-foreground shadow-lg outline-none" onOpenAutoFocus={(event) => { event.preventDefault(); focus(value ?? (required ? rows[0]?.group.id : undefined)) }}>
        <div className="flex shrink-0 items-center gap-2 border-b bg-muted/40 px-3 py-2.5">
          <Folders className="size-4 text-primary" />
          <span className="text-sm font-medium">选择分组</span>
          <span className="ml-auto text-xs text-muted-foreground">点击箭头展开 / 收起</span>
        </div>
        <div ref={treeRef} role="tree" aria-label="案例分组" className="min-h-0 overflow-y-auto overscroll-contain p-1.5">
          {!required && <><button type="button" role="treeitem" aria-level={1} aria-selected={value === undefined} data-group-id="ALL" tabIndex={focusedId === undefined ? 0 : -1} onFocus={() => setFocusedId(undefined)} onKeyDown={(event) => navigate(event)} onClick={() => select()} className={cn(rowClass, "mb-1 min-h-9", value === undefined && "bg-primary/10 font-medium text-primary")}>
            <Folders className="mx-1 size-4 shrink-0 text-primary" />
            <span className="flex-1">{emptyLabel}</span>
            {value === undefined && <Check className="mr-1 size-4 shrink-0" />}
          </button>
          <div role="none" className="mb-1 border-t" /></>}
          {rows.map(({ group, depth, hasChildren }) => {
            const id = group.id!
            const isExpanded = expanded.has(id)
            const isSelected = value === id
            const Icon = hasChildren && isExpanded ? FolderOpen : Folder
            return <button key={id} type="button" role="treeitem" aria-level={depth + 1} aria-selected={isSelected} aria-expanded={hasChildren ? isExpanded : undefined} data-group-id={id} tabIndex={focusedId === id ? 0 : -1} onFocus={() => setFocusedId(id)} onKeyDown={(event) => navigate(event, id, hasChildren)} onClick={() => select(id)} title={group.fullPath || group.name} className={cn(rowClass, "min-h-9", isSelected && "bg-primary/10 font-medium text-primary shadow-[inset_2px_0_0_var(--primary)]")} style={{ paddingLeft: 4 + depth * 20 }}>
              {Array.from({ length: depth }, (_, index) => <span key={index} aria-hidden="true" className="pointer-events-none absolute inset-y-0 border-l border-border/80" style={{ left: 17 + index * 20 }} />)}
              {depth > 0 && <span aria-hidden="true" className="pointer-events-none absolute top-1/2 w-2.5 border-t border-border/80" style={{ left: 17 + (depth - 1) * 20 }} />}
              <span aria-hidden="true" className={cn("relative flex size-7 shrink-0 items-center justify-center rounded-sm", hasChildren && "text-muted-foreground hover:bg-primary/10 hover:text-primary")} onClick={hasChildren ? (event) => { event.stopPropagation(); toggle(id) } : undefined}>
                {hasChildren && <ChevronRight className={cn("size-3.5 transition-transform duration-150 motion-reduce:transition-none", isExpanded && "rotate-90")} />}
              </span>
              <Icon aria-hidden="true" className={cn("size-4 shrink-0", hasChildren || isSelected ? "text-primary" : "text-muted-foreground")} />
              <span className="min-w-0 flex-1 truncate">{group.name}</span>
              {group.groupCode && <span className="shrink-0 font-mono text-[11px] text-muted-foreground">{group.groupCode}</span>}
              {isSelected && <Check aria-hidden="true" className="size-4 shrink-0 text-primary" />}
            </button>
          })}
        </div>
      </Popover.Content>
    </Popover.Portal>
  </Popover.Root>
}
