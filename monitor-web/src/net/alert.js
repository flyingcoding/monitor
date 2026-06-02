import { get, post, put, del } from '@/net'
import { withQuery } from '@/net/query'

// ============ 告警规则 ============
const listAlertRules = (success, failure) => get('/api/alert/rule', success, failure)
const getAlertRule = (id, success, failure) => get(`/api/alert/rule/${id}`, success, failure)
const createAlertRule = (payload, success, failure) =>
  post('/api/alert/rule', payload, success, failure)
const updateAlertRule = (id, payload, success, failure) =>
  put(`/api/alert/rule/${id}`, payload, success, failure)
const deleteAlertRule = (id, success, failure) => del(`/api/alert/rule/${id}`, success, failure)
const silenceAlertRule = (id, minutes, success, failure) =>
  post(withQuery(`/api/alert/rule/${id}/silence`, { minutes }), {}, success, failure)

// ============ 告警历史 ============
/**
 * 列表查询；params 内字段：page,size,clientId,level,status,from,to。
 *
 * @param {object} params 查询参数
 * @param {Function} success 成功回调
 * @param {Function} failure 失败回调
 */
function listAlertHistory(params, success, failure) {
  get(withQuery('/api/alert/history', params), success, failure)
}
const getAlertHistory = (id, success, failure) =>
  get(`/api/alert/history/${id}`, success, failure)
const ackAlertHistory = (id, success, failure) =>
  post(`/api/alert/history/${id}/ack`, {}, success, failure)
const closeAlertHistory = (id, success, failure) =>
  post(`/api/alert/history/${id}/close`, {}, success, failure)

// ============ 通知通道 ============
const listChannels = (success, failure) => get('/api/notification/channel', success, failure)
const getChannel = (id, success, failure) =>
  get(`/api/notification/channel/${id}`, success, failure)
const createChannel = (payload, success, failure) =>
  post('/api/notification/channel', payload, success, failure)
const updateChannel = (id, payload, success, failure) =>
  put(`/api/notification/channel/${id}`, payload, success, failure)
const deleteChannel = (id, success, failure) =>
  del(`/api/notification/channel/${id}`, success, failure)
const testChannel = (id, success, failure) =>
  post(`/api/notification/channel/${id}/test`, {}, success, failure)

export {
  listAlertRules,
  getAlertRule,
  createAlertRule,
  updateAlertRule,
  deleteAlertRule,
  silenceAlertRule,
  listAlertHistory,
  getAlertHistory,
  ackAlertHistory,
  closeAlertHistory,
  listChannels,
  getChannel,
  createChannel,
  updateChannel,
  deleteChannel,
  testChannel
}
