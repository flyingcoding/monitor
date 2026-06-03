const DEFAULT_RUNTIME_HISTORY_MAX_POINTS = 720

/**
 * Convert an arbitrary value to a finite number, using fallback when conversion fails.
 *
 * @param {*} value Source value from the runtime history payload
 * @param {number} fallback Number used when value is missing or invalid
 * @returns {number} Finite numeric value
 */
function toFiniteNumber(value, fallback = 0) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : fallback
}

/**
 * Round a number to the requested decimal precision without returning a string.
 *
 * @param {number} value Numeric source value
 * @param {number} precision Decimal precision
 * @returns {number} Rounded numeric value
 */
function roundNumber(value, precision = 1) {
  const scale = 10 ** precision
  return Math.round(toFiniteNumber(value) * scale) / scale
}

/**
 * Convert a timestamp to a sortable x-axis value for LTTB area calculations.
 *
 * @param {string|number|Date} timestamp Runtime sample timestamp
 * @param {number} index Sample index used when timestamp parsing fails
 * @returns {number} Numeric x-axis value
 */
function timestampToX(timestamp, index) {
  const time = new Date(timestamp).getTime()
  return Number.isFinite(time) ? time : index
}

/**
 * Normalize backend RuntimeHistoryVO rows into the minimal fields needed by charts.
 *
 * @param {Array<object>} list Runtime history rows from the backend
 * @returns {Array<object>} Normalized rows with numeric chart values
 */
function normalizeRuntimeHistory(list) {
  if (!Array.isArray(list)) return []
  return list.map((item, index) => ({
    timestamp: item && item.timestamp ? item.timestamp : index,
    cpuUsage: roundNumber(toFiniteNumber(item && item.cpuUsage) * 100),
    memoryUsage: roundNumber(toFiniteNumber(item && item.memoryUsage) * 1024),
    networkUpload: roundNumber(item && item.networkUpload),
    networkDownload: roundNumber(item && item.networkDownload),
    diskRead: roundNumber(item && item.diskRead),
    diskWrite: roundNumber(item && item.diskWrite)
  }))
}

/**
 * Calculate triangle area used by the Largest-Triangle-Three-Buckets algorithm.
 *
 * @param {object} pointA Previously selected point
 * @param {object} pointB Candidate point
 * @param {object} pointC Average point of the next bucket
 * @returns {number} Triangle area
 */
function triangleArea(pointA, pointB, pointC) {
  return Math.abs(
    (pointA.x - pointC.x) * (pointB.y - pointA.y) -
      (pointA.x - pointB.x) * (pointC.y - pointA.y)
  )
}

/**
 * Convert a normalized row to an LTTB point.
 *
 * @param {Array<object>} rows Normalized runtime rows
 * @param {number} index Row index
 * @param {Function} valueAccessor Function returning the y-axis value
 * @returns {{x: number, y: number}} LTTB point
 */
function toLttbPoint(rows, index, valueAccessor) {
  const row = rows[index]
  return {
    x: timestampToX(row.timestamp, index),
    y: toFiniteNumber(valueAccessor(row))
  }
}

/**
 * Downsample rows with Largest-Triangle-Three-Buckets while preserving endpoints.
 *
 * @param {Array<object>} rows Normalized runtime rows
 * @param {number} threshold Maximum number of rows to return
 * @param {Function} valueAccessor Function returning the y-axis value for area calculations
 * @returns {Array<object>} Downsampled rows
 */
function sampleByLttb(rows, threshold, valueAccessor) {
  if (!Array.isArray(rows) || rows.length === 0) return []
  const pointLimit = Math.floor(toFiniteNumber(threshold, DEFAULT_RUNTIME_HISTORY_MAX_POINTS))
  if (pointLimit <= 0) return []
  if (rows.length <= pointLimit) return rows.slice()
  if (pointLimit === 1) return [rows[0]]
  if (pointLimit === 2) return [rows[0], rows[rows.length - 1]]

  const sampled = [rows[0]]
  const bucketSize = (rows.length - 2) / (pointLimit - 2)
  let selectedIndex = 0

  for (let bucketIndex = 0; bucketIndex < pointLimit - 2; bucketIndex++) {
    const nextBucketStart = Math.floor((bucketIndex + 1) * bucketSize) + 1
    const nextBucketEnd = Math.min(Math.floor((bucketIndex + 2) * bucketSize) + 1, rows.length)
    const nextBucketLength = Math.max(nextBucketEnd - nextBucketStart, 1)
    let averageX = 0
    let averageY = 0

    for (let index = nextBucketStart; index < nextBucketEnd; index++) {
      const point = toLttbPoint(rows, index, valueAccessor)
      averageX += point.x
      averageY += point.y
    }

    if (nextBucketStart >= nextBucketEnd) {
      const fallbackPoint = toLttbPoint(rows, Math.min(nextBucketStart, rows.length - 1), valueAccessor)
      averageX = fallbackPoint.x
      averageY = fallbackPoint.y
    } else {
      averageX /= nextBucketLength
      averageY /= nextBucketLength
    }

    const pointA = toLttbPoint(rows, selectedIndex, valueAccessor)
    const pointC = { x: averageX, y: averageY }
    const bucketStart = Math.floor(bucketIndex * bucketSize) + 1
    const bucketEnd = Math.min(Math.floor((bucketIndex + 1) * bucketSize) + 1, rows.length - 1)
    let maxArea = -1
    let nextSelectedIndex = bucketStart

    for (let index = bucketStart; index < bucketEnd; index++) {
      const pointB = toLttbPoint(rows, index, valueAccessor)
      const area = triangleArea(pointA, pointB, pointC)
      if (area > maxArea) {
        maxArea = area
        nextSelectedIndex = index
      }
    }

    sampled.push(rows[nextSelectedIndex])
    selectedIndex = nextSelectedIndex
  }

  sampled.push(rows[rows.length - 1])
  return sampled
}

/**
 * Build a single chart payload from sampled rows and selected value fields.
 *
 * @param {Array<object>} rows Sampled normalized rows
 * @param {Array<string>} fields Series field names
 * @returns {{labels: Array<*>, series: Array<Array<number>>}} Chart payload
 */
function buildChartPayload(rows, fields) {
  return {
    labels: rows.map((row) => row.timestamp),
    series: fields.map((field) => rows.map((row) => row[field]))
  }
}

/**
 * Normalize and downsample runtime history into all RuntimeHistory.vue chart payloads.
 *
 * @param {Array<object>} list Runtime history rows from the backend
 * @param {number} maxPoints Maximum points per chart
 * @returns {object} Chart payloads for CPU, memory, network, and disk panels
 */
function buildRuntimeHistoryPayload(list, maxPoints = DEFAULT_RUNTIME_HISTORY_MAX_POINTS) {
  const normalized = normalizeRuntimeHistory(list)
  const pointLimit = Math.floor(toFiniteNumber(maxPoints, DEFAULT_RUNTIME_HISTORY_MAX_POINTS))

  const cpuRows = sampleByLttb(normalized, pointLimit, (row) => row.cpuUsage)
  const memoryRows = sampleByLttb(normalized, pointLimit, (row) => row.memoryUsage)
  const networkRows = sampleByLttb(normalized, pointLimit, (row) =>
    Math.max(Math.abs(row.networkUpload), Math.abs(row.networkDownload))
  )
  const diskRows = sampleByLttb(normalized, pointLimit, (row) =>
    Math.max(Math.abs(row.diskRead), Math.abs(row.diskWrite))
  )

  return {
    sourceLength: normalized.length,
    maxPoints: pointLimit,
    cpu: buildChartPayload(cpuRows, ['cpuUsage']),
    memory: buildChartPayload(memoryRows, ['memoryUsage']),
    network: buildChartPayload(networkRows, ['networkUpload', 'networkDownload']),
    disk: buildChartPayload(diskRows, ['diskRead', 'diskWrite'])
  }
}

export {
  DEFAULT_RUNTIME_HISTORY_MAX_POINTS,
  buildRuntimeHistoryPayload,
  normalizeRuntimeHistory,
  sampleByLttb
}
