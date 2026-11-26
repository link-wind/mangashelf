package com.mangashelf.identity;

import com.mangashelf.config.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

    private MailProperties props;
    private EmailVerificationRepository repo;
    private UserRepository users;
    private EmailSenderService sender;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        props = new MailProperties(true, "smtp.resend.com", 465, true, false,
                "resend", "test-key", "MangaShelf <noreply@linkwind.top>", 10, 60, 10, 20);
        repo = mock(EmailVerificationRepository.class);
        users = mock(UserRepository.class);
        sender = mock(EmailSenderService.class);
        service = new EmailVerificationService(props, repo, users, sender, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void sendsSixDigitCodeStoresOnlyHashAndVerifiesOnce() {
        when(users.existsByEmail("alice@linkwind.top")).thenReturn(false);
        when(repo.findLatestCreatedAt("alice@linkwind.top", "REGISTER")).thenReturn(Optional.empty());
        when(repo.countSinceByEmail(eq("alice@linkwind.top"), eq("REGISTER"), any())).thenReturn(0L);
        when(repo.countSinceByIp(eq("127.0.0.1"), any())).thenReturn(0L);

        var res = service.sendRegisterCode("  Alice@LinkWind.Top ", "127.0.0.1");
        assertTrue(res.sent());
        assertEquals(60, res.cooldownSeconds());
        assertEquals(10, res.expiresInMinutes());

        ArgumentCaptor<String> codeCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hashCap = ArgumentCaptor.forClass(String.class);
        verify(sender).sendRegisterVerificationCode(eq("alice@linkwind.top"), codeCap.capture(), eq(10));
        verify(repo).insert(any(), eq("alice@linkwind.top"), eq("REGISTER"),
                hashCap.capture(), eq("127.0.0.1"), eq(5), eq(NOW.plusSeconds(600)));

        String sentCode = codeCap.getValue();
        String storedHash = hashCap.getValue();
        assertTrue(sentCode.matches("^[0-9]{6}$"));
        assertNotEquals(sentCode, storedHash);
        assertEquals(64, storedHash.length());

        UUID rowId = UUID.randomUUID();
        when(repo.findLatestActive("alice@linkwind.top", "REGISTER", NOW)).thenReturn(Optional.of(
                new EmailVerificationRepository.VerificationCode(
                        rowId, "alice@linkwind.top", "REGISTER", storedHash, "127.0.0.1",
                        0, 5, NOW.plusSeconds(600), null, NOW)));

        String verified = service.verifyAndConsumeRegisterCode("ALICE@linkwind.top", sentCode);
        assertEquals("alice@linkwind.top", verified);
        verify(repo).markConsumed(rowId, NOW);
    }

    @Test
    void enforcesResendCooldownAndRejectsRegisteredEmail() {
        when(users.existsByEmail("taken@linkwind.top")).thenReturn(true);
        ApiException taken = assertThrows(ApiException.class,
                () -> service.sendRegisterCode("taken@linkwind.top", "127.0.0.1"));
        assertEquals(409, taken.getStatus());

        when(users.existsByEmail("fast@linkwind.top")).thenReturn(false);
        when(repo.findLatestCreatedAt("fast@linkwind.top", "REGISTER"))
                .thenReturn(Optional.of(NOW.minusSeconds(25)));
        ApiException limited = assertThrows(ApiException.class,
                () -> service.sendRegisterCode("fast@linkwind.top", "127.0.0.1"));
        assertEquals(429, limited.getStatus());
        assertEquals(35L, limited.getDetails().get("retryAfterSeconds"));
    }

    @Test
    void wrongCodeIncrementsAttemptsAndLocksOnFifthFailure() {
        UUID rowId = UUID.randomUUID();
        String hash = EmailVerificationService.hashCode("bob@linkwind.top", "REGISTER", "123456");
        when(repo.findLatestActive("bob@linkwind.top", "REGISTER", NOW)).thenReturn(Optional.of(
                new EmailVerificationRepository.VerificationCode(
                        rowId, "bob@linkwind.top", "REGISTER", hash, "",
                        4, 5, NOW.plusSeconds(300), null, NOW)));

        ApiException locked = assertThrows(ApiException.class,
                () -> service.verifyAndConsumeRegisterCode("bob@linkwind.top", "999999"));
        assertEquals("CODE_LOCKED", locked.getCode());
        verify(repo).incrementAttempts(rowId);
        verify(repo).markConsumed(rowId, NOW);
    }

    @Test
    void rejectsInvalidEmailAndNonSixDigitCode() {
        assertEquals("INVALID_EMAIL",
                assertThrows(ApiException.class, () -> EmailVerificationService.normalizeAndValidateEmail("not-an-email"))
                        .getCode());
        assertEquals("INVALID_CODE",
                assertThrows(ApiException.class, () -> service.verifyAndConsumeRegisterCode("a@b.com", "12ab56"))
                        .getCode());
    }
}
