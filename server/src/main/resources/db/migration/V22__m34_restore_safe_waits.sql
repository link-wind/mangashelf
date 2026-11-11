-- M34 修正：浏览器源等待回调到安全值（1.5~1.8s 会与站点 302 跳转竞争，
-- 导致 Execution context destroyed）。保留“页图正则/直出时跳过滚动”的优化。
UPDATE manga_source_rules
SET rules = jsonb_set(
        jsonb_set(
            jsonb_set(rules, '{browserPlan,search,wait_ms}', to_jsonb(2500)),
            '{browserPlan,detail,wait_ms}', to_jsonb(2500)),
        '{browserPlan,pages,wait_ms}', to_jsonb(2500))
WHERE id = 'baozimh';

UPDATE manga_source_rules
SET rules = jsonb_set(
        jsonb_set(
            jsonb_set(
                jsonb_set(rules, '{browserPlan,search,wait_ms}', to_jsonb(2500)),
                '{browserPlan,detail,wait_ms}', to_jsonb(2500)),
            '{browserPlan,pages,wait_ms}', to_jsonb(3000)),
        '{browserPlan,pages,scroll}', to_jsonb(0))
WHERE id = 'hipmh';
