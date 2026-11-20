import { useEffect, useId, useState } from "react"
import type { ReactTable, RowData } from "@tanstack/react-table"
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import type { DataTableFeatures } from "@/components/ui/data-table-features"
import { Input } from "@/components/ui/input"

const pageSizeOptions = [20, 50, 100, 200, 500]

export function DataTablePagination<TData extends RowData>({ table, selectable = false }: { table: ReactTable<DataTableFeatures, TData>; selectable?: boolean }) {
  const pageSizeListId = useId()
  const [pageSizeInput, setPageSizeInput] = useState(String(table.state.pagination.pageSize))
  useEffect(() => setPageSizeInput(String(table.state.pagination.pageSize)), [table.state.pagination.pageSize])
  const commitPageSize = () => {
    const pageSize = Number(pageSizeInput)
    if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 500) {
      setPageSizeInput(String(table.state.pagination.pageSize))
      return
    }
    if (pageSize !== table.state.pagination.pageSize) {
      table.setPageSize(pageSize)
      table.setPageIndex(0)
    }
  }
  return <div className="flex shrink-0 flex-wrap items-center justify-between gap-3 px-2">
    <div className="flex-1 text-sm text-muted-foreground">{selectable ? `${table.getFilteredSelectedRowModel().rows.length} / ${table.getFilteredRowModel().rows.length} 条已选择` : `共 ${table.getFilteredRowModel().rows.length} 条`}</div>
    <div className="flex flex-wrap items-center gap-4 lg:gap-6">
      <label className="flex items-center gap-2 text-sm text-muted-foreground" htmlFor={`${pageSizeListId}-input`}>
        <span>每页</span>
        <Input id={`${pageSizeListId}-input`} type="number" min={1} max={500} list={pageSizeListId} value={pageSizeInput} onChange={(event) => setPageSizeInput(event.target.value)} onBlur={commitPageSize} onKeyDown={(event) => { if (event.key === "Enter") { event.preventDefault(); commitPageSize() } }} className="h-8 w-20 text-center" aria-label="每页条数" />
        <datalist id={pageSizeListId}>{pageSizeOptions.map((pageSize) => <option key={pageSize} value={pageSize} />)}</datalist>
        <span>条</span>
      </label>
      <div className="w-28 text-center text-sm font-medium">第 {table.state.pagination.pageIndex + 1} / {Math.max(table.getPageCount(), 1)} 页</div>
      <div className="flex items-center gap-2">
        <Button variant="outline" size="icon-sm" className="hidden lg:flex" title="首页" onClick={() => table.setPageIndex(0)} disabled={!table.getCanPreviousPage()}><ChevronsLeft /></Button>
        <Button variant="outline" size="icon-sm" title="上一页" onClick={() => table.previousPage()} disabled={!table.getCanPreviousPage()}><ChevronLeft /></Button>
        <Button variant="outline" size="icon-sm" title="下一页" onClick={() => table.nextPage()} disabled={!table.getCanNextPage()}><ChevronRight /></Button>
        <Button variant="outline" size="icon-sm" className="hidden lg:flex" title="末页" onClick={() => table.setPageIndex(Math.max(table.getPageCount() - 1, 0))} disabled={!table.getCanNextPage()}><ChevronsRight /></Button>
      </div>
    </div>
  </div>
}
