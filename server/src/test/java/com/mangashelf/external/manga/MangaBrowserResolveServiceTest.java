package com.mangashelf.external.manga;

import com.mangashelf.config.ApiException;
import com.mangashelf.config.MangaShelfProperties;
import com.mangashelf.ingestion.PythonTaskRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MangaBrowserResolveServiceTest {

    @TempDir
    Path temporary;

    @Test
    void preservesLoginRequiredResultWhenPythonExitsWithFailure() throws Exception {
        MangaShelfProperties properties = mock(MangaShelfProperties.class, RETURNS_DEEP_STUBS);
        when(properties.processing().workRoot()).thenReturn(temporary.resolve("jobs").toString());
        PythonTaskRunner python = mock(PythonTaskRunner.class);
        doAnswer(invocation -> {
            Path directory = invocation.getArgument(0);
            Files.writeString(directory.resolve("result.json"),
                    "{\"status\":\"FAILED\",\"error_code\":\"LOGIN_REQUIRED\"}");
            throw new IOException("process exited");
        }).when(python).run(any(), any(), any());
        MangaBrowserResolveService service = new MangaBrowserResolveService(
                python, new ObjectMapper(), properties);
        ApiException error = assertThrows(ApiException.class, () -> service.resolveChapter(
                "https://www.mangacopy.com/comic/sample/chapter/test", "", ""));
        assertEquals(403, error.getStatus());
        assertEquals("COPYMANGA_LOGIN_REQUIRED", error.getCode());
        assertTrue(error.getMessage().contains("登录态"));
        try (var directories = Files.list(temporary.resolve("jobs/manga-resolve"))) {
            assertEquals(0, directories.count());
        }
    }
}
