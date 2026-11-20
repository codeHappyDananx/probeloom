import type { ReactTable, RowData } from "@tanstack/react-table"
import { Settings2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { DropdownMenu, DropdownMenuCheckboxItem, DropdownMenuContent, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { cn } from "@/lib/utils"
import type { DataTableFeatures } from "@/components/ui/data-table-features"

export function DataTableViewOptions<TData extends RowData>({ table, className }: { table: ReactTable<DataTableFeatures, TData>; className?: string }) {
  const columns = table.getAllColumns().filter((column) => column.getCanHide())
  if (!columns.length) return null

  return <DropdownMenu>
    <DropdownMenuTrigger asChild>
      <Button variant="outline" size="sm" className={cn("hidden h-8 lg:flex", className)}><Settings2 />列</Button>
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end" className="w-40">
      <DropdownMenuLabel>显示列</DropdownMenuLabel>
      <DropdownMenuSeparator />
      {columns.map((column) => <DropdownMenuCheckboxItem key={column.id} checked={column.getIsVisible()} onCheckedChange={(value) => column.toggleVisibility(Boolean(value))}>{(column.columnDef.meta as { label?: string } | undefined)?.label || column.id}</DropdownMenuCheckboxItem>)}
    </DropdownMenuContent>
  </DropdownMenu>
}
