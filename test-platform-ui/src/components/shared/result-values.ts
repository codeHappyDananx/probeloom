export function parseDisplayValue(value?: string): unknown {
  if (value === undefined || value === "") return ""
  try { return JSON.parse(value) } catch { return value }
}

const databaseIsoDate = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(?:Z|[+-]\d{2}:\d{2})$/

function databaseValueText(value: string | number) {
  if (typeof value === "number") return String(value)
  if (!databaseIsoDate.test(value)) return value
  const shanghaiDate = new Date(new Date(value).getTime() + 8 * 60 * 60 * 1000)
  const pad = (part: number) => String(part).padStart(2, "0")
  return `${shanghaiDate.getUTCFullYear()}-${pad(shanghaiDate.getUTCMonth() + 1)}-${pad(shanghaiDate.getUTCDate())} ${pad(shanghaiDate.getUTCHours())}:${pad(shanghaiDate.getUTCMinutes())}:${pad(shanghaiDate.getUTCSeconds())}`
}

export function displayValueText(value: unknown) {
  if (value === null) return "null"
  if (value === undefined) return "-"
  if (value === "") return "空字符串"
  if (typeof value === "string" && /^ +$/.test(value)) return `${value.length} 个空格`
  if (typeof value === "string" || typeof value === "number") return databaseValueText(value)
  if (typeof value === "boolean") return String(value)
  return JSON.stringify(value)
}

export function databaseValueEqual(expected: unknown, actual: unknown) {
  const expectedEmpty = expected === null || expected === ""
  const normalizedActual = actual
  const actualEmpty = normalizedActual === null || normalizedActual === ""
  if (expectedEmpty || actualEmpty) return expectedEmpty && actualEmpty
  if (typeof expected === "string" && typeof normalizedActual === "string" && sameDatePrecision(expected, normalizedActual)) return true
  const expectedScalar = typeof expected === "string" || typeof expected === "number"
  const actualScalar = typeof normalizedActual === "string" || typeof normalizedActual === "number"
  if (expectedScalar && actualScalar) {
    return databaseValueText(expected) === databaseValueText(normalizedActual)
  }
  return JSON.stringify(expected) === JSON.stringify(normalizedActual)
}

export function sameDatePrecision(expected: string, actual: string) {
  if (/^\d{4}$/.test(expected) && /^\d{4}[-/]\d{2}[-/]\d{2}(?:[ T].*)?$/.test(actual)) return expected === actual.slice(0, 4)
  if (/^\d{4}[-/]\d{2}[-/]\d{2}$/.test(expected) && /^\d{4}[-/]\d{2}[-/]\d{2}(?:[ T].*)?$/.test(actual)) return expected === actual.slice(0, 10)
  if (/^\d{2}:\d{2}:\d{2}$/.test(expected) && /^\d{4}[-/]\d{2}[-/]\d{2}[ T]\d{2}:\d{2}:\d{2}$/.test(actual)) return expected === actual.slice(-8)
  return false
}
