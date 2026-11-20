import type { CaseGroup, Id } from "@/types/platform"

type GroupChildren = Map<Id, CaseGroup[]>

function compareGroups(first: CaseGroup, second: CaseGroup) {
  if (first.sortNo === undefined && second.sortNo !== undefined) return 1
  if (first.sortNo !== undefined && second.sortNo === undefined) return -1
  if (first.sortNo !== undefined && second.sortNo !== undefined && first.sortNo !== second.sortNo) return first.sortNo - second.sortNo
  return (first.groupNo || 0) - (second.groupNo || 0)
}

export function groupChildren(groups: CaseGroup[]): GroupChildren {
  const knownIds = new Set(groups.flatMap((item) => item.id === undefined ? [] : [item.id]))
  const values = new Map<Id, CaseGroup[]>()
  for (const group of groups) {
    if (group.parentId === undefined || !knownIds.has(group.parentId)) continue
    const children = values.get(group.parentId) || []
    children.push(group)
    values.set(group.parentId, children)
  }
  for (const children of values.values()) children.sort(compareGroups)
  return values
}

export function orderedGroups(groups: CaseGroup[]) {
  const children = groupChildren(groups)
  const knownIds = new Set(groups.flatMap((item) => item.id === undefined ? [] : [item.id]))
  const roots = groups.filter((item) => item.parentId === undefined || !knownIds.has(item.parentId)).sort(compareGroups)
  const values: CaseGroup[] = []
  const append = (group: CaseGroup) => {
    values.push(group)
    if (group.id !== undefined) for (const child of children.get(group.id) || []) append(child)
  }
  roots.forEach(append)
  return values
}

export function descendantGroupIds(groups: CaseGroup[], groupId?: Id) {
  if (groupId === undefined) return new Set<Id>()
  const children = groupChildren(groups)
  const values = new Set<Id>()
  const append = (id: Id) => {
    for (const child of children.get(id) || []) if (child.id !== undefined && !values.has(child.id)) {
      values.add(child.id)
      append(child.id)
    }
  }
  append(groupId)
  return values
}

export function treeRows(groups: CaseGroup[], expanded: Set<Id>, keyword: string) {
  const children = groupChildren(groups)
  const knownIds = new Set(groups.flatMap((item) => item.id === undefined ? [] : [item.id]))
  const roots = groups.filter((item) => item.parentId === undefined || !knownIds.has(item.parentId)).sort(compareGroups)
  const query = keyword.trim().toLocaleLowerCase()
  const matches = (group: CaseGroup) => !query || [group.groupCode, group.name, group.fullPath].filter(Boolean).join(" ").toLocaleLowerCase().includes(query)
  const visible = (group: CaseGroup): boolean => matches(group) || (group.id !== undefined && (children.get(group.id) || []).some(visible))
  const values: Array<{ group: CaseGroup; depth: number; hasChildren: boolean }> = []
  const append = (group: CaseGroup, depth: number) => {
    const childValues = group.id === undefined ? [] : children.get(group.id) || []
    const hasChildren = childValues.length > 0
    values.push({ group, depth, hasChildren })
    if (!hasChildren || (!query && (group.id === undefined || !expanded.has(group.id)))) return
    childValues.filter((item) => !query || visible(item)).forEach((item) => append(item, depth + 1))
  }
  roots.filter((item) => !query || visible(item)).forEach((item) => append(item, 0))
  return values
}
