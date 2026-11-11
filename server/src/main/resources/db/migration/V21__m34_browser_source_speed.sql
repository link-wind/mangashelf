-- M34：浏览器源等待参数下调（实测页面数据在 1.5~2s 内就绪）：
-- 包子页图可从 HTML 正则一次取全，无需滚动；嬉皮 data-src 已直出，无需滚动。
UPDATE manga_source_rules
SET rules = jsonb_set(
        jsonb_set(
            jsonb_set(rules, '{browserPlan,search,wait_ms}', to_jsonb(1800)),
            '{browserPlan,detail,wait_ms}', to_jsonb(1800)),
        '{browserPlan,pages,wait_ms}', to_jsonb(1500))
WHERE id = 'baozimh';

UPDATE manga_source_rules
SET rules = jsonb_set(
        jsonb_set(
            jsonb_set(
                jsonb_set(rules, '{browserPlan,search,wait_ms}', to_jsonb(1800)),
                '{browserPlan,detail,wait_ms}', to_jsonb(1800)),
            '{browserPlan,pages,wait_ms}', to_jsonb(2000)),
        '{browserPlan,pages,scroll}', to_jsonb(0))
WHERE id = 'hipmh';
