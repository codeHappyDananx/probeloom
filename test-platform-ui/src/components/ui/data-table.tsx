"use client"

import * as React from "react"
import { useTable, type ColumnDef, type ColumnFiltersState, type ColumnVisibilityState, type ReactTable, type RowData, type RowSelectionState, type SortingState } from "@tanstack/react-table"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { dataTableFeatures, type DataTableFeatures } from "@/components/ui/data-table-features"
import { DataTablePagination } from "@/components/ui/data-table-pagination"
import { DataTableViewOptions } from "@/components/ui/data-table-view-options"
import { OverflowTooltip } from "@/components/shared/overflow-tooltip"

type DataTableProps<TData extends RowData> = {
  columns: ColumnDef<DataTableFeatures, TData>[]
  data: TData[]
  emptyMessage: string
  header?: (table: ReactTable<DataTableFeatures, TData>) => React.ReactNode
  toolbar?: (table: ReactTable<DataTableFeatures, TData>) => React.ReactNode
  onRowClick?: (row: TData) => void
  isRowSelected?: (row: TData) => boolean
  pagination?: { page: number; pageSize: number; total: number; onPageChange: (page: number) => void }
  enableRowSelection?: boolean
}

export function DataTable<TData extends RowData>({ columns, data, emptyMessage, header, toolbar, onRowClick, isRowSelected, pagination, enableRowSelection = false }: DataTableProps<TData>) {
  const [rowSelection, setRowSelection] = React.useState<RowSelectionState>({})
  const [columnVisibility, setColumnVisibility] = React.useState<ColumnVisibilityState>({})
  const [columnFilters, setColumnFilters] = React.useState<ColumnFiltersState>([])
  const [sorting, setSorting] = React.useState<SortingState>([])
  const tableViewportRef = React.useRef<HTMLDivElement>(null)
  const table = useTable({
    features: dataTableFeatures,
    data,
    columns,
    state: { sorting, columnVisibility, rowSelection, columnFilters },
    initialState: { pagination: { pageIndex: 0, pageSize: 20 } },
    enableRowSelection,
    onRowSelectionChange: setRowSelection,
    onSortingChange: setSorting,
    onColumnFiltersChange: setColumnFilters,
    onColumnVisibilityChange: setColumnVisibility,
  })

  const rows = table.getRowModel().rows

  return <div className="flex min-h-0 min-w-0 flex-1 flex-col gap-4">
    {header?.(table)}
    {!header && (toolbar || columns.some((column) => column.enableHiding !== false)) && <div className="flex shrink-0 flex-wrap items-center gap-2">{toolbar?.(table)}<DataTableViewOptions table={table} className="ml-auto" /></div>}
    {header && toolbar && <div className="flex shrink-0 flex-wrap items-center gap-2">{toolbar(table)}</div>}
    <div ref={tableViewportRef} className="relative min-h-0 flex-1 overflow-auto rounded-md border"><Table><TableHeader className="sticky top-0 z-10 bg-background">{table.getHeaderGroups().map((headerGroup) => <TableRow key={headerGroup.id}>{headerGroup.headers.map((header) => <TableHead key={header.id} colSpan={header.colSpan}>{header.isPlaceholder ? null : <table.FlexRender header={header} />}</TableHead>)}</TableRow>)}</TableHeader><TableBody>{rows.map((row) => <TableRow key={row.id} data-state={isRowSelected?.(row.original) || row.getIsSelected() ? "selected" : undefined} className={onRowClick ? "cursor-pointer" : undefined} onClick={() => onRowClick?.(row.original)}>{row.getVisibleCells().map((cell) => { const value = cell.getValue(); const content = typeof value === "string" || typeof value === "number" ? String(value) : undefined; return <TableCell key={cell.id}><OverflowTooltip content={content} className="block max-w-full"><table.FlexRender cell={cell} /></OverflowTooltip></TableCell> })}</TableRow>)}</TableBody></Table>{!rows.length && <div className="absolute inset-x-0 bottom-0 top-10 grid place-items-center text-sm text-muted-foreground">{emptyMessage}</div>}</div>
    {pagination ? <div className="flex shrink-0 items-center justify-between gap-2 text-sm"><span className="text-muted-foreground">共 {pagination.total} 条 · 第 {pagination.page} / {Math.max(1, Math.ceil(pagination.total / pagination.pageSize))} 页</span><div className="flex gap-2"><button type="button" className="rounded-md border px-3 py-1 disabled:opacity-50" disabled={pagination.page <= 1} onClick={() => pagination.onPageChange(pagination.page - 1)}>上一页</button><button type="button" className="rounded-md border px-3 py-1 disabled:opacity-50" disabled={pagination.page * pagination.pageSize >= pagination.total} onClick={() => pagination.onPageChange(pagination.page + 1)}>下一页</button></div></div> : <DataTablePagination table={table} selectable={enableRowSelection} />}
  </div>
}
