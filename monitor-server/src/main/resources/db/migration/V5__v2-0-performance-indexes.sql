-- v2.0 performance index audit.
-- Existing migrations already provide:
--   alert_history(rule_id, fired_at), alert_history(client_id, status)
--   probe_history(task_id, executed_at)
--   api_token(account_id), api_token(token_hash)
--   account_oidc_binding(provider_name, subject), account_oidc_binding(account_id)
-- The remaining verified lookup gap is client token authentication.

ALTER TABLE `client`
  ADD INDEX `idx_client_token` (`token`);
