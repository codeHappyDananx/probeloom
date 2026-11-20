import { useEffect, useMemo, useState } from "react"
import { ChevronDown, ChevronRight, CirclePlay, Pencil, Plus, Search, Trash2 } from "lucide-react"
import { toast } from "sonner"
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { GroupEditorDialog, GroupRunDialog } from "@/features/cases/CaseManagementDialogs"
import { groupLabel } from "@/features/cases/case-utils"
import { descendantGroupIds, treeRows } from "@/features/cases/group-tree"
import { api } from "@/lib/platform-api"
import type { Bootstrap, CaseGroup, Id, TestCase } from "@/types/platform"


export function GroupManagementPage({ data, reload }: { data: Bootstrap; reload: () => Promise<void> }) {
  const [editorOpen, setEditorOpen] = useState(false)
  const [runningGroupId, setRunningGroupId] = useState<Id>()
  const [editingGroup, setEditingGroup] = useState<CaseGroup>()
  const [editorParentId, setEditorParentId] = useState<Id>()
  const [deletingGroup, setDeletingGroup] = useState<CaseGroup>()
  const [deleteGroupCases, setDeleteGroupCases] = useState(false)
  const [removingGroup, setRemovingGroup] = useState(false)
  const [expanded, setExpanded] = useState<Set<Id>>(new Set())
  const [keyword, setKeyword] = useState("")
  const [cases, setCases] = useState<TestCase[]>(data.cases)
  const runningCaseIds = useMemo(() => {
    if (runningGroupId === undefined) return []
    const groupIds = descendantGroupIds(data.groups, runningGroupId)
    groupIds.add(runningGroupId)
    return data.groups.filter((group) => group.id !== undefined && groupIds.has(group.id)).flatMap((group) => group.caseIds)
  }, [data.groups, runningGroupId])
  const runningGroup = data.groups.find((item) => item.id === runningGroupId)
  const rows = useMemo(() => treeRows(data.groups, expanded, keyword), [data.groups, expanded, keyword])
  useEffect(() => {
    setExpanded((current) => current.size ? current : new Set(data.groups.filter((item) => item.id !== undefined && item.parentId === undefined).map((item) => item.id!)))
  }, [data.groups])
  useEffect(() => {
    if (!editorOpen) return
    if (data.cases.length) {
      setCases(data.cases)
      return
    }
    void api<TestCase[]>("/api/cases/summaries").then(setCases).catch((error) => toast.error(error instanceof Error ? error.message : "加载案例目录失败"))
  }, [data.cases, editorOpen])
  const edit = (group?: CaseGroup, parentId?: Id) => { setEditingGroup(group); setEditorParentId(parentId); setEditorOpen(true) }
  const remove = async () => {
    if (!deletingGroup?.id) return
    setRemovingGroup(true)
    try {
      await api(`/api/groups/${deletingGroup.id}?deleteCases=${deleteGroupCases}`, { method: "DELETE" })
      setDeletingGroup(undefined)
      setDeleteGroupCases(false)
      await reload()
      toast.success("分组已删除")
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "删除失败")
    } finally {
      setRemovingGroup(false)
    }
  }
  const run = async (environmentIds: Record<string, Id>, parallelism: number) => {
    if (!runningGroupId) return
    try {
      await api<Id[]>(`/api/groups/${runningGroupId}/run`, { method: "POST", body: JSON.stringify({ environmentIds, parallelism }) })
      await reload()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "执行失败")
      throw error
    }
  }
  const toggle = (id: Id) => setExpanded((current) => {
    const next = new Set(current)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  })
  const allGroupIds = data.groups.flatMap((item) => item.id === undefined ? [] : [item.id])

  return <div className="flex min-h-0 min-w-0 flex-1 flex-col gap-4">
    <div className="flex shrink-0 flex-wrap items-end justify-between gap-3"><div><h1 className="text-lg font-semibold">分组管理</h1><p className="mt-1 text-sm text-muted-foreground">维护案例归属与回归执行范围。</p></div><div className="flex gap-2"><Button onClick={() => edit()}><Plus />新建根分组</Button></div></div>
    <div className="flex shrink-0 flex-wrap items-center gap-2"><InputGroup className="min-w-64 max-w-md flex-1"><InputGroupInput aria-label="检索案例分组" placeholder="搜索分组名称或编号" value={keyword} onChange={(event) => setKeyword(event.target.value)} /><InputGroupAddon><Search /></InputGroupAddon></InputGroup><Button type="button" variant="outline" size="sm" onClick={() => setExpanded(new Set(allGroupIds))}>全部展开</Button><Button type="button" variant="outline" size="sm" onClick={() => setExpanded(new Set())}>全部收起</Button></div>
    <div className="min-h-0 flex-1 overflow-auto rounded-md border"><Table className="min-w-[58rem]"><TableHeader className="sticky top-0 z-10 bg-background"><TableRow><TableHead>分组</TableHead><TableHead>编号</TableHead><TableHead>完整路径</TableHead><TableHead className="text-right">直属案例</TableHead><TableHead className="text-right">子树案例</TableHead><TableHead /></TableRow></TableHeader><TableBody>{rows.map(({ group, depth, hasChildren }) => <TableRow key={group.id}><TableCell><div className="flex min-w-64 items-center gap-1" style={{ paddingLeft: `${depth * 20}px` }}><Button type="button" variant="ghost" size="icon-sm" className="size-6" aria-label={hasChildren ? `${expanded.has(group.id!) || keyword ? "收起" : "展开"}${group.name}` : `${group.name}没有子分组`} disabled={!hasChildren} onClick={() => group.id !== undefined && toggle(group.id)}>{hasChildren && (expanded.has(group.id!) || keyword ? <ChevronDown /> : <ChevronRight />)}</Button><span className="truncate font-medium">{group.name}</span></div></TableCell><TableCell><span className="font-mono text-muted-foreground">{group.groupCode}</span></TableCell><TableCell><span className="text-sm text-muted-foreground">{group.fullPath || group.name}</span></TableCell><TableCell className="text-right">{group.caseIds.length}</TableCell><TableCell className="text-right">{group.subtreeCaseCount ?? group.caseIds.length}</TableCell><TableCell><div className="flex justify-end gap-1"><Button variant="ghost" size="icon-sm" title="新增子分组" aria-label="新增子分组" onClick={() => edit(undefined, group.id)}><Plus /></Button><Button variant="ghost" size="icon-sm" title="编辑分组" aria-label="编辑分组" onClick={() => edit(group)}><Pencil /></Button><Button variant="ghost" size="icon-sm" title="执行分组" aria-label="执行分组" disabled={!group.subtreeCaseCount} onClick={() => setRunningGroupId(group.id)}><CirclePlay /></Button><Button variant="ghost" size="icon-sm" title="删除分组" aria-label="删除分组" disabled={hasChildren} onClick={() => setDeletingGroup(group)}><Trash2 /></Button></div></TableCell></TableRow>)}{!rows.length && <TableRow><TableCell colSpan={6} className="h-36 text-center text-sm text-muted-foreground">{data.groups.length ? "没有匹配的分组" : "还没有分组"}</TableCell></TableRow>}</TableBody></Table></div>
    <GroupEditorDialog open={editorOpen} group={editingGroup} defaultParentId={editorParentId} groups={data.groups} cases={cases} onOpenChange={(open) => { setEditorOpen(open); if (!open) { setEditingGroup(undefined); setEditorParentId(undefined) } }} onSaved={async () => { await reload() }} />
    <GroupRunDialog maxParallelism={data.executionParallelism} open={runningGroupId !== undefined} group={runningGroup} environments={data.environments} caseIds={runningCaseIds} onOpenChange={(open) => { if (!open) setRunningGroupId(undefined) }} onRun={run} />
    <AlertDialog open={deletingGroup !== undefined} onOpenChange={(open) => { if (!open && !removingGroup) { setDeletingGroup(undefined); setDeleteGroupCases(false) } }}><AlertDialogContent><AlertDialogHeader><AlertDialogTitle>删除分组</AlertDialogTitle><AlertDialogDescription>{deletingGroup ? `${groupLabel(deletingGroup)} 的定时计划将一并删除。${deleteGroupCases ? "直属案例将移入回收站，可恢复为未分组案例。" : "直属案例会转为未分组。"}已有执行记录保留。` : ""}</AlertDialogDescription></AlertDialogHeader><label className="flex items-center gap-2 text-sm"><Checkbox checked={deleteGroupCases} disabled={removingGroup} onCheckedChange={(checked) => setDeleteGroupCases(checked === true)} />同时删除直属案例（{deletingGroup?.caseIds.length || 0} 条）</label><AlertDialogFooter><AlertDialogCancel disabled={removingGroup}>取消</AlertDialogCancel><AlertDialogAction variant="destructive" disabled={removingGroup} onClick={(event) => { event.preventDefault(); void remove() }}>{removingGroup ? "删除中" : "删除"}</AlertDialogAction></AlertDialogFooter></AlertDialogContent></AlertDialog>
  </div>
}
