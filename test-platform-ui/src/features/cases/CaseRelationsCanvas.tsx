import { useMemo } from "react"
import { Background, Controls, Handle, MiniMap, Position, ReactFlow, type Edge, type Node, type NodeProps } from "@xyflow/react"
import "@xyflow/react/dist/style.css"
import { FileCheck2, Folder, GitBranch } from "lucide-react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { OverflowTooltip } from "@/components/shared/overflow-tooltip"
import { groupChildren, orderedGroups } from "@/features/cases/group-tree"
import type { CaseGroup, Id, TestCase } from "@/types/platform"

type MindMapNodeData = { label: string; detail?: string; kind: "ROOT" | "GROUP" | "CASE"; caseId?: Id; caseIds: Id[]; branchColor?: string; selected?: boolean }
type MindMapFlowNode = Node<MindMapNodeData, "mindMapNode">

const branchColors = ["#4f46e5", "#0284c7", "#0f766e", "#c2410c", "#be185d", "#7c3aed"]

function nodeIcon(kind: MindMapNodeData["kind"]) {
  if (kind === "CASE") return FileCheck2
  if (kind === "GROUP") return Folder
  return GitBranch
}

function MindMapNodeCard({ data }: NodeProps<MindMapFlowNode>) {
  const Icon = nodeIcon(data.kind)
  const isRoot = data.kind === "ROOT"
  const isGroup = data.kind === "GROUP"
  return <div className={`relative cursor-pointer ${isRoot ? "w-64 rounded-2xl border-primary/30 bg-primary px-4 py-3 text-primary-foreground shadow-lg" : isGroup ? "w-64 rounded-xl border bg-accent/70 px-3 py-3 shadow-sm" : "w-60 rounded-xl border bg-card px-3 py-2.5 shadow-sm transition-colors duration-200 hover:border-primary/50"} ${data.selected ? "ring-2 ring-primary ring-offset-2" : ""}`} style={isGroup && data.branchColor ? { borderLeftColor: data.branchColor, borderLeftWidth: 4 } : undefined}>
    {data.kind !== "ROOT" && <Handle type="target" position={Position.Left} className="!size-2 !border-0 !bg-border" />}
    {data.kind !== "CASE" && <Handle type="source" position={Position.Right} className="!size-2 !border-0 !bg-border" />}
    <div className="flex items-start gap-2"><Icon className={`mt-0.5 size-4 shrink-0 ${isRoot ? "text-primary-foreground" : "text-muted-foreground"}`} /><div className="min-w-0 flex-1"><p className={`text-[11px] font-medium uppercase tracking-wide ${isRoot ? "text-primary-foreground/75" : "text-muted-foreground"}`}>{isRoot ? "案例总览" : isGroup ? "分组" : "案例"}</p><OverflowTooltip content={data.label} className="block max-w-full"><p className="mt-0.5 line-clamp-2 break-all text-sm font-semibold leading-5">{data.label}</p></OverflowTooltip>{data.detail && <OverflowTooltip content={data.detail} className="mt-1 block max-w-full"><p className={`line-clamp-1 text-xs ${isRoot ? "text-primary-foreground/75" : "text-muted-foreground"}`}>{data.detail}</p></OverflowTooltip>}</div></div>
  </div>
}

const nodeTypes = { mindMapNode: MindMapNodeCard }

function layoutMindMap(nodes: MindMapFlowNode[], edges: Edge[]) {
  const children = new Map<string, string[]>()
  for (const edge of edges) children.set(edge.source, [...(children.get(edge.source) || []), edge.target])
  const positions = new Map<string, { x: number; y: number }>()
  let nextLeaf = 0
  const place = (id: string, depth: number): number => {
    const childIds = children.get(id) || []
    const y = childIds.length === 0 ? 48 + nextLeaf++ * 104 : childIds.reduce((sum, childId) => sum + place(childId, depth + 1), 0) / childIds.length
    positions.set(id, { x: 64 + depth * 320, y })
    return y
  }
  if (nodes.some((node) => node.id === "root")) place("root", 0)
  return { nodes: nodes.map((node) => ({ ...node, position: positions.get(node.id) || { x: 64, y: 48 } })), edges }
}

function buildMindMap(groups: CaseGroup[], cases: TestCase[], selectedCaseId?: Id) {
  const nodes = new Map<string, MindMapFlowNode>()
  const edges: Edge[] = []
  const children = groupChildren(groups)
  const knownGroupIds = new Set(groups.flatMap((group) => group.id === undefined ? [] : [group.id]))
  const caseValues = cases.filter((testCase): testCase is TestCase & { id: Id } => testCase.id !== undefined)
  const casesByGroup = new Map<Id, (TestCase & { id: Id })[]>()
  for (const testCase of caseValues) if (testCase.groupId !== undefined) casesByGroup.set(testCase.groupId, [...(casesByGroup.get(testCase.groupId) || []), testCase])

  const addNode = (id: string, data: MindMapNodeData) => nodes.set(id, { id, type: "mindMapNode", data, position: { x: 0, y: 0 } })
  const addEdge = (source: string, target: string, color: string) => edges.push({ id: `${source}->${target}`, source, target, type: "smoothstep", style: { stroke: color, strokeWidth: 2 } })
  const addCase = (testCase: TestCase & { id: Id }, parentId: string, color: string) => {
    const id = `case:${testCase.id}`
    addNode(id, { label: testCase.name, detail: testCase.caseCode || "未编号", kind: "CASE", caseId: testCase.id, caseIds: [testCase.id], branchColor: color, selected: testCase.id === selectedCaseId })
    addEdge(parentId, id, color)
    return testCase.id
  }
  const addGroup = (group: CaseGroup & { id: Id }, parentId: string, color: string): Id[] => {
    const id = `group:${group.id}`
    addNode(id, { label: group.name, detail: group.fullPath || group.groupCode, kind: "GROUP", caseIds: [], branchColor: color })
    addEdge(parentId, id, color)
    const caseIds: Id[] = []
    for (const child of children.get(group.id) || []) if (child.id !== undefined) caseIds.push(...addGroup(child as CaseGroup & { id: Id }, id, color))
    for (const testCase of casesByGroup.get(group.id) || []) caseIds.push(addCase(testCase, id, color))
    const node = nodes.get(id)
    if (node) node.data = { ...node.data, caseIds, selected: caseIds.includes(selectedCaseId || -1) }
    return caseIds
  }

  addNode("root", { label: "案例关系", detail: `${groups.length} 个分组 · ${caseValues.length} 个案例`, kind: "ROOT", caseIds: caseValues.map((testCase) => testCase.id), selected: selectedCaseId === undefined })
  let branchIndex = 0
  const rootGroups = orderedGroups(groups).filter((group) => group.id !== undefined && (group.parentId === undefined || !knownGroupIds.has(group.parentId))) as Array<CaseGroup & { id: Id }>
  for (const group of rootGroups) addGroup(group, "root", branchColors[branchIndex++ % branchColors.length])
  const ungroupedCases = caseValues.filter((testCase) => testCase.groupId === undefined)
  if (ungroupedCases.length) {
    const color = branchColors[branchIndex++ % branchColors.length]
    const id = "group:unassigned"
    addNode(id, { label: "未分组", detail: "未归属具体分组", kind: "GROUP", caseIds: ungroupedCases.map((testCase) => testCase.id), branchColor: color, selected: ungroupedCases.some((testCase) => testCase.id === selectedCaseId) })
    addEdge("root", id, color)
    for (const testCase of ungroupedCases) addCase(testCase, id, color)
  }
  return layoutMindMap([...nodes.values()], edges)
}

export function CaseRelationsCanvas({ groups, cases, selectedCaseId, onSelectCase }: { groups: CaseGroup[]; cases: TestCase[]; selectedCaseId?: Id; onSelectCase: (caseId?: Id) => void }) {
  const graph = useMemo(() => buildMindMap(groups, cases, selectedCaseId), [cases, groups, selectedCaseId])
  return <Card className="flex h-full min-h-0 min-w-0 flex-col overflow-hidden"><CardHeader className="shrink-0 gap-1"><CardTitle className="text-base">案例关系思维导图</CardTitle><CardDescription>按分组层级展示案例，点击案例节点查看右侧信息和步骤。</CardDescription></CardHeader><CardContent className="min-h-0 flex-1 p-0"><ReactFlow<MindMapFlowNode> nodes={graph.nodes} edges={graph.edges} nodeTypes={nodeTypes} fitView fitViewOptions={{ padding: 0.16 }} minZoom={0.2} maxZoom={1.4} nodesDraggable={false} nodesConnectable={false} onNodeClick={(_event, node) => { if (node.data.kind === "CASE") onSelectCase(node.data.caseId) }}><Background gap={24} size={1} /><MiniMap pannable zoomable nodeColor="var(--muted-foreground)" /><Controls /></ReactFlow></CardContent></Card>
}
