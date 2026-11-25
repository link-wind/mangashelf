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
    void parseV0SubjectDetailExtractsTagsDateRatingSummaryStudioPlatformAndTime() throws Exception {
        String json = """
                {
                  "id": 567896,
                  "name": "ブラッククローバー",
                  "name_cn": "中文名",
                  "platform": "TV",
                  "summary": "详细剧情简介",
                  "date": "2026-04-03",
                  "rating": { "score": 8.2 },
                  "meta_tags": ["TV", "奇幻"],
                  "tags": [
                    { "name": "冒险", "count": 420 },
                    { "name": "奇幻", "count": 510 },
                    { "name": "热血", "count": 300 }
                  ],
                  "infobox": [
                    { "key": "动画制作", "value": "MAPPA" },
                    { "key": "放送时间", "value": "每周四 24:30" }
                  ]
                }
                """;
        var parsed = BangumiService.parseV0SubjectDetail(mapper.readTree(json));
        assertEquals("中文名", parsed.nameCn());
        assertEquals("详细剧情简介", parsed.summary());
        assertEquals(LocalDate.of(2026, 4, 3), parsed.airDate());
        assertEquals(8.2, parsed.rating());
        assertEquals("MAPPA", parsed.studio());
        assertEquals("TV", parsed.mediaType());
        assertEquals("23:30", parsed.airTime());
        assertTrue(parsed.tags().contains("奇幻"));
        assertTrue(parsed.tags().contains("冒险"));
        assertTrue(parsed.tags().contains("热血"));
    }

    @Test
    void parseTimeFromTextHandlesJstLateNightAndChinaTimes() {
        assertEquals("00:00", BangumiService.parseTimeFromText("25:00", true));
        assertEquals("00:30", BangumiService.parseTimeFromText("25:30", false));
        assertEquals("22:00", BangumiService.parseTimeFromText("日本 23:00", false));
        assertEquals("10:00", BangumiService.parseTimeFromText("中国大陆 10:00", true));
        assertEquals("18:00", BangumiService.parseTimeFromText("18:00", false));
    }

    @Test
    void parseBangumiDataAirTimesConvertsUtcBeginToShanghaiHhmm() throws Exception {
        String json = """
                {
                  "items": [
                    {
                      "title": "药屋少女的呢喃 第三季",
                      "begin": "2026-10-02T14:00:00.000Z",
                      "sites": [{ "site": "bangumi", "id": "568244" }]
                    },
                    {
                      "title": "深夜番",
                      "begin": "2026-10-03T16:30:00.000Z",
                      "sites": [{ "site": "bangumi", "id": "554779" }]
                    }
                  ]
                }
                """;
        var map = BangumiService.parseBangumiDataAirTimes(mapper.readTree(json));
        assertEquals("22:00", map.get(568244L));
        assertEquals("00:30", map.get(554779L));
    }

    @Test
    void buildCalendarIcsEmitsValidWeeklyRruleEvents() {
        var entry = new BangumiService.CalendarEntry(
                1001L, "Test Anime", "测试新番, 特别篇", "", 8.1, 3,
                true, "cat-1", null, "", "更新至8集", 8, "日韩动漫",
                LocalDate.of(2026, 10, 7), "23:30", "MAPPA", "TV");
        var unscheduled = new BangumiService.CalendarEntry(
                1002L, "No Day", "未定档", "", null, null,
                false, null, null, "", null, null, null,
                null, null, "", "");

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
