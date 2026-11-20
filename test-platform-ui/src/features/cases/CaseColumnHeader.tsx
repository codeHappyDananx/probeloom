import { useState, type ReactNode } from "react"
import { ArrowDown, ArrowUp, ChevronsUpDown, Filter, RotateCcw } from "lucide-react"
import { Popover } from "radix-ui"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import type { CaseQuery, CaseSort } from "@/types/platform"

export function CaseColumnHeader({ title, column, query, active, loading, children, onChange, onApply, onClear }: { title: string; column: CaseSort; query: CaseQuery; active: boolean; loading: boolean; children: ReactNode; onChange: (query: Partial<CaseQuery>) => void; onApply: () => void; onClear: () => void }) {
  const [open, setOpen] = useState(false)
  const sorted = query.sortBy === column
  const SortIcon = sorted ? query.sortDirection === "DESC" ? ArrowDown : ArrowUp : ChevronsUpDown

  return <div role="columnheader" aria-sort={sorted ? query.sortDirection === "DESC" ? "descending" : "ascending" : "none"} className="flex min-w-0 items-center gap-0.5 border-r px-1.5 py-1.5 last:border-r-0">
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <button type="button" disabled={loading} aria-label={`${title}排序和筛选`} className={cn("flex min-h-7 min-w-0 flex-1 cursor-pointer items-center justify-between gap-1 rounded px-1.5 text-xs font-medium transition-colors hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50", (active || sorted) && "text-primary")}>
          <span className="truncate">{title}</span>
          <span className="flex shrink-0 items-center gap-1">{active && <Filter className="size-3 fill-primary/15" />}<SortIcon className="size-3.5" /></span>
        </button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content align="start" sideOffset={5} collisionPadding={12} className="z-50 w-72 max-w-[calc(100vw-1.5rem)] rounded-lg border bg-popover p-3 text-popover-foreground shadow-lg outline-none">
          <div className="mb-3 text-sm font-medium">{title}</div>
          <div className="mb-3 grid grid-cols-2 gap-2">
            {(["ASC", "DESC"] as const).map((direction) => <Button key={direction} type="button" size="sm" variant={sorted && query.sortDirection === direction ? "secondary" : "outline"} aria-pressed={sorted && query.sortDirection === direction} className="cursor-pointer" onClick={() => { onChange({ sortBy: column, sortDirection: direction }); setOpen(false) }}>{direction === "ASC" ? <ArrowUp /> : <ArrowDown />}{direction === "ASC" ? "升序" : "降序"}</Button>)}
          </div>
          <div className="space-y-3 border-t pt-3">{children}</div>
          <div className="mt-3 flex items-center justify-between border-t pt-3">
            <Button type="button" size="sm" variant="ghost" className="cursor-pointer text-muted-foreground" onClick={() => { onClear(); setOpen(false) }}><RotateCcw className="size-3.5" />重置本列</Button>
            <Button type="button" size="sm" className="cursor-pointer" onClick={() => { onApply(); setOpen(false) }}><Filter className="size-3.5" />筛选</Button>
          </div>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  </div>
}
