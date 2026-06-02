import { ElMessage } from 'element-plus'

/**
 * 提交表格启停开关变更；Element Plus switch 会先改本地值，失败时统一回滚。
 *
 * @param {{row: {enabled: boolean}, update: Function}} options 切换参数
 */
function submitEnabledToggle({ row, update }) {
  update(
    () => {
      ElMessage.success(row.enabled ? '已启用' : '已禁用')
    },
    () => {
      row.enabled = !row.enabled
    }
  )
}

export { submitEnabledToggle }
