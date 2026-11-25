# external-animations Specification Delta

## ADDED Requirements

### Requirement: Bangumi v0 条目详情元数据补齐与追番日历 .ics 导出

系统 MUST 支持通过 Bangumi v0 条目详情接口礼貌限速补齐 `bangumi_subjects` 缺失的 `tags`、`air_date` 与 `summary`，使动态题材与年份筛选可用；系统 MUST 提供标准 RFC 5545 `GET /api/v1/calendar/ics` 端点，支持公开本季或登录态仅导出个人追番（`onlyMine=true`）的每周循环日历事件。

#### Scenario: 导出本季追番日历 .ics
- **WHEN** 客户端请求 `GET /api/v1/calendar/ics`
- **THEN** 返回 `Content-Type: text/calendar; charset=utf-8`，包含 `BEGIN:VCALENDAR` 与按放送星期生成的 `RRULE:FREQ=WEEKLY;BYDAY=...` 事件
