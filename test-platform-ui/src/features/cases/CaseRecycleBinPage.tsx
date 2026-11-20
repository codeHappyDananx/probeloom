import { useEffect, useMemo, useState } from "react"
import type { ColumnDef } from "@tanstack/react-table"
import { RotateCcw, Trash2 } from "lucide-react"
import { toast } from "sonner"
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog"
import { OverflowTooltip } from "@/components/shared/overflow-tooltip"
import { Button } from "@/components/ui/button"
import { DataTable } from "@/components/ui/data-table"
import { DataTableColumnHeader } from "@/components/ui/data-table-column-header"
import type { DataTableFeatures } from "@/components/ui/data-table-features"
import { Input } from "@/components/ui/input"
import { groupLabel } from "@/features/cases/case-utils"
import { api, formatTime } from "@/lib/platform-api"
import type { TestCase } from "@/types/platform"

export function CaseRecycleBinPage({ onRestored }: { onRestored: () => Promise<void> }) {
  const [cases, setCases] = useState<TestCase[]>([])
  const [keyword, setKeyword] = useState("")
  const [restoringId, setRestoringId] = useState<number>()
  const [deletingId, setDeletingId] = useState<number>()
  const [deletingCase, setDeletingCase] = useState<TestCase>()
  const load = async () => {
    try {
      setCases(await api<TestCase[]>("/api/cases/recycle-bin"))
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "加载回收站失败")
    }
  }
  const removePermanently = async () => {
    if (!deletingCase?.id) return
    setDeletingId(deletingCase.id)
    try {
      await api<void>(`/api/cases/${deletingCase.id}/recycle-bin`, { method: "DELETE" })
      await load()
      toast.success("案例已彻底删除")
      setDeletingCase(undefined)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "删除失败")
    } finally {
      setDeletingId(undefined)
    }
  }
  useEffect(() => { void load() }, [])
  const restore = async (testCase: TestCase) => {
    if (!testCase.id) return
    setRestoringId(testCase.id)
    try {
      await api<TestCase>(`/api/cases/${testCase.id}/restore`, { method: "POST" })
      await Promise.all([load(), onRestored()])
      toast.success("案例已恢复")
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "恢复失败")
    } finally {
      setRestoringId(undefined)
    }
  }
  const filteredCases = useMemo(() => cases.filter((item) => !keyword.trim() || `${item.caseCode || ""} ${item.name} ${item.description || ""}`.toLowerCase().includes(keyword.trim().toLowerCase())), [cases, keyword])
  const columns: ColumnDef<DataTableFeatures, TestCase>[] = [
    { accessorKey: "caseCode", meta: { label: "编号" }, header: ({ column }) => <DataTableColumnHeader column={column} title="编号" />, cell: ({ row }) => <span className="font-mono text-muted-foreground">{row.original.caseCode}</span> },
    { accessorKey: "name", meta: { label: "案例" }, header: ({ column }) => <DataTableColumnHeader column={column} title="案例" />, cell: ({ row }) => <OverflowTooltip content={[row.original.name, row.original.description || "暂无业务说明"].join("\n")} className="block max-w-80"><div><p className="truncate font-medium">{row.original.name}</p><p className="mt-1 truncate text-sm text-muted-foreground">{row.original.description || "暂无业务说明"}</p></div></OverflowTooltip> },
    { id: "group", accessorFn: (row) => row.groupName || "未分组", meta: { label: "分组" }, header: ({ column }) => <DataTableColumnHeader column={column} title="分组" />, cell: ({ row }) => <span className="text-sm text-muted-foreground">{row.original.groupId ? groupLabel({ groupCode: row.original.groupCode, name: row.original.groupName || "" }) : "未分组"}</span> },
    { id: "steps", accessorFn: (row) => row.actions.length, meta: { label: "步骤" }, header: ({ column }) => <DataTableColumnHeader column={column} title="步骤" />, cell: ({ row }) => `${row.original.actions.length} 个` },
    { accessorKey: "deletedAt", meta: { label: "删除时间" }, header: ({ column }) => <DataTableColumnHeader column={column} title="删除时间" />, cell: ({ row }) => <span className="text-sm text-muted-foreground">{formatTime(row.original.deletedAt)}</span> },
    { id: "actions", enableSorting: false, enableHiding: false, header: "", cell: ({ row }) => <div className="flex justify-end gap-2"><Button variant="outline" size="sm" disabled={restoringId === row.original.id || deletingId !== undefined} onClick={() => void restore(row.original)}><RotateCcw />{restoringId === row.original.id ? "恢复中" : "恢复"}</Button><Button variant="destructive" size="sm" disabled={restoringId !== undefined || deletingId !== undefined} onClick={() => setDeletingCase(row.original)}><Trash2 />删除</Button></div> },
  ]
  return <div className="flex h-full min-h-0 min-w-0 flex-col gap-4"><div className="shrink-0"><h1 className="text-lg font-semibold">回收站</h1><p className="mt-1 text-sm text-muted-foreground">误删案例保留完整步骤和文件，可恢复到原位置。</p></div><DataTable columns={columns} data={filteredCases} emptyMessage="回收站为空" toolbar={() => <Input aria-label="检索回收站案例" placeholder="搜索案例编号、名称或说明" value={keyword} onChange={(event) => setKeyword(event.target.value)} className="min-w-56 max-w-sm" />} /><AlertDialog open={deletingCase !== undefined} onOpenChange={(open) => { if (!open && deletingId === undefined) setDeletingCase(undefined) }}><AlertDialogContent><AlertDialogHeader><AlertDialogTitle>彻底删除案例？</AlertDialogTitle><AlertDialogDescription>{deletingCase ? `“${deletingCase.name}”及其步骤、上传文件和执行记录将被永久删除，不能恢复。` : ""}</AlertDialogDescription></AlertDialogHeader><AlertDialogFooter><AlertDialogCancel disabled={deletingId !== undefined}>取消</AlertDialogCancel><AlertDialogAction variant="destructive" disabled={deletingId !== undefined} onClick={() => void removePermanently()}><Trash2 />{deletingId === undefined ? "彻底删除" : "删除中"}</AlertDialogAction></AlertDialogFooter></AlertDialogContent></AlertDialog></div>
}
