package com.mangashelf.library;

import com.mangashelf.identity.AuthUser;
import com.mangashelf.identity.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 历史与继续观看（separate-favorites-and-history）：历史按章节/集，继续观看按目标聚合。 */
@RestController
@RequestMapping("/api/v1/me")
public class PersonalHistoryController {

    private final PersonalHistoryRepository history;

    public PersonalHistoryController(PersonalHistoryRepository history) {
        this.history = history;
    }

    @GetMapping("/history")
    public List<PersonalHistoryRepository.ActivityRow> history(
            @AuthUser CurrentUser user, @RequestParam(defaultValue = "50") int limit) {
        return history.history(user.id(), clamp(limit, 200));
    }

    @GetMapping("/continue")
    public List<PersonalHistoryRepository.ActivityRow> continueWatching(
            @AuthUser CurrentUser user, @RequestParam(defaultValue = "20") int limit) {
        return history.continueWatching(user.id(), clamp(limit, 100));
    }

    private static int clamp(int value, int max) {
        return Math.min(Math.max(value, 1), max);
    }
}
