package com.example.animelib;

import com.example.animelib.controllers.PlayerEpisodesController;
import com.example.animelib.models.EpisodesListResponse;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class EpisodeSwitchBookmarkUnitTest {

    @Test
    public void testInitialBookmarkConsumedOnceAndNeverLeaksToNextEpisode() {
        PlayerEpisodesController controller = new PlayerEpisodesController();
        AtomicInteger resetCallbackCount = new AtomicInteger(0);
        controller.initialize(null, null, null, new PlayerEpisodesController.EpisodesCallback() {
            @Override
            public void onEpisodeHeaderUpdateQuick() {}

            @Override
            public void onResetBookmarkState() {
                resetCallbackCount.incrementAndGet();
            }

            @Override
            public void onInitializeMenuWithoutAutoPlay() {}

            @Override
            public void onEpisodeChanged(EpisodesListResponse.EpisodeItem episode) {}
        });

        // 1. Вход в плеер на серию из закладки с таймкодом 12:34 (754_000 мс)
        controller.setNewEpisodeSelection(true);
        controller.setBookmarkTimecode(754_000L);

        assertTrue(controller.isNewEpisodeSelection());
        assertFalse(controller.isInitialBookmarkConsumed());
        assertEquals(754_000L, controller.getStartPosition());

        // Первый старт плеера потребляет таймкод закладки ровно один раз
        long initialStartPos = controller.consumeStartPosition();
        assertEquals(754_000L, initialStartPos);
        assertTrue(controller.isInitialBookmarkConsumed());
        assertEquals(0L, controller.getBookmarkTimecode());
        assertEquals(0L, controller.getStartPosition());

        // Повторное чтение больше никогда не возвращает старый таймкод закладки
        assertEquals(0L, controller.consumeStartPosition());

        // Попытка повторно установить таймкод из запоздалого колбэка закладки игнорируется
        controller.setBookmarkTimecode(754_000L);
        assertEquals(0L, controller.getBookmarkTimecode());

        // 2. Переключение на другой эпизод в плеере всегда стартует с 00:00
        EpisodesListResponse.EpisodeItem ep6 = new EpisodesListResponse.EpisodeItem();
        ep6.setId(1006);
        ep6.setNumber("6");

        controller.onEpisodeSelected(ep6, true);
        assertTrue(controller.hasUserSwitchedEpisode());
        assertTrue(controller.isNewEpisodeSelection());
        assertEquals(0L, controller.consumeStartPosition());
        assertTrue(resetCallbackCount.get() >= 1);
    }

    @Test
    public void testDownloadedOrImmediateEpisodeSwitchBeforeInitialBookmarkConsumedStartsFromZero() {
        PlayerEpisodesController controller = new PlayerEpisodesController();

        // Пользователь зашел в плеер с закладкой (754_000 мс), но сразу переключил на другую серию (в т.ч. скачанную)
        controller.setNewEpisodeSelection(true);
        controller.setBookmarkTimecode(754_000L);
        assertEquals(754_000L, controller.getBookmarkTimecode());

        controller.markEpisodeSwitched();

        assertTrue(controller.hasUserSwitchedEpisode());
        assertTrue(controller.isInitialBookmarkConsumed());
        assertTrue(controller.isNewEpisodeSelection());
        assertEquals(0L, controller.getBookmarkTimecode());
        assertEquals(0L, controller.consumeStartPosition());

        // Даже если после переключения прилетает старый асинхронный ответ закладки — он блокируется
        controller.setBookmarkTimecode(754_000L);
        assertEquals(0L, controller.getBookmarkTimecode());
        assertEquals(0L, controller.consumeStartPosition());
    }

    @Test
    public void testVoiceoverSwitchPreservesPositionWithinSameEpisodeButResetsOnEpisodeChange() {
        PlayerEpisodesController controller = new PlayerEpisodesController();

        // Первичный вход с закладкой
        controller.setBookmarkTimecode(300_000L);
        assertEquals(300_000L, controller.consumeStartPosition());
        controller.setNewEpisodeSelection(false);

        // Смена озвучки внутри того же эпизода на 08:20 (500_000 мс)
        controller.setSavedPlayerPosition(500_000L);
        assertFalse(controller.isNewEpisodeSelection());
        assertEquals(500_000L, controller.consumeStartPosition());
        assertEquals(0L, controller.getSavedPlayerPosition());

        // Затем пользователь сохранил позицию, но передумал и переключил серию — новая серия строго с 00:00
        controller.setSavedPlayerPosition(515_000L);
        EpisodesListResponse.EpisodeItem nextEp = new EpisodesListResponse.EpisodeItem();
        nextEp.setId(2002);
        nextEp.setNumber("2");
        controller.onEpisodeSelected(nextEp, true);

        assertEquals(0L, controller.getSavedPlayerPosition());
        assertEquals(0L, controller.consumeStartPosition());

        // При переходе к совершенно новому тайтлу (resetForNewAnime) начальная закладка снова разрешена
        controller.resetForNewAnime();
        assertFalse(controller.hasUserSwitchedEpisode());
        assertFalse(controller.isInitialBookmarkConsumed());
        controller.setBookmarkTimecode(120_000L);
        assertEquals(120_000L, controller.consumeStartPosition());
    }
}
