package com.mangashelf.external;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bangumi v0 详情解析与 RFC 5545 追番日历 .ics 生成单元测试。 */
class BangumiEnrichAndIcsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseV0SubjectDetailExtractsTagsDateRatingAndSummary() throws Exception {
        String json = """
                {
                  "id": 567896,
                  "name": "Original Name",
                  "name_cn": "中文名",
                  "summary": "详细剧情简介",
                  "date": "2026-04-03",
                  "rating": { "score": 8.2 },
                  "meta_tags": ["TV", "奇幻"],
                  "tags": [
                    { "name": "冒险", "count": 420 },
                    { "name": "奇幻", "count": 510 },
                    { "name": "热血", "count": 300 }
                  ]
                }
                """;
        var parsed = BangumiService.parseV0SubjectDetail(mapper.readTree(json));
        assertEquals("中文名", parsed.nameCn());
        assertEquals("详细剧情简介", parsed.summary());
        assertEquals(LocalDate.of(2026, 4, 3), parsed.airDate());
        assertEquals(8.2, parsed.rating());
        assertTrue(parsed.tags().contains("奇幻"));
        assertTrue(parsed.tags().contains("冒险"));
        assertTrue(parsed.tags().contains("热血"));
    }

    @Test
    void buildCalendarIcsEmitsValidWeeklyRruleEvents() {
        var entry = new BangumiService.CalendarEntry(
                1001L, "Test Anime", "测试新番, 特别篇", "", 8.1, 3,
                true, "cat-1", null, "", "更新至8集", 8, "日韩动漫");
        var unscheduled = new BangumiService.CalendarEntry(
                1002L, "No Day", "未定档", "", null, null,
                false, null, null, "", null, null, null);

        String ics = BangumiService.buildCalendarIcs(List.of(entry, unscheduled), "我的追番表");
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"));
        assertTrue(ics.contains("X-WR-CALNAME:我的追番表\r\n"));
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;BYDAY=WE\r\n"));
        assertTrue(ics.contains("SUMMARY:【新番】测试新番\\, 特别篇\r\n"));
        assertTrue(ics.contains("UID:mangashelf-bgm-1001@mangashelf.local\r\n"));
        assertTrue(!ics.contains("1002"), "无放送星期的条目不应生成循环事件");
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"));
    }
}
