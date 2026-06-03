import {
  DEFAULT_RUNTIME_HISTORY_MAX_POINTS,
  buildRuntimeHistoryPayload
} from './runtime-history-data'

/**
 * Build runtime chart payloads in a worker and return the result with the caller sequence.
 *
 * @param {MessageEvent} event Worker message event
 */
self.onmessage = (event) => {
  const { seq, list, maxPoints = DEFAULT_RUNTIME_HISTORY_MAX_POINTS } = event.data || {}
  try {
    self.postMessage({
      seq,
      payload: buildRuntimeHistoryPayload(list, maxPoints)
    })
  } catch (error) {
    self.postMessage({
      seq,
      error: error instanceof Error ? error.message : 'Runtime history worker failed'
    })
  }
}
