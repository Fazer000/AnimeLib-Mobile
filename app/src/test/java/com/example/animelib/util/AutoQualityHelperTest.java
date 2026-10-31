package com.example.animelib.util;

import com.example.animelib.adapters.QualityAdapter;
import com.example.animelib.controllers.PlayerDownloadController;
import com.example.animelib.data.entity.DownloadedEpisodeEntity;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AutoQualityHelperTest {

    @Test
    public void testSanitizeSavedOnlineQualityPreventsDownloadedPollution() {
        assertEquals("Авто", AutoQualityHelper.sanitizeSavedOnlineQuality(null));
        assertEquals("Авто", AutoQualityHelper.sanitizeSavedOnlineQuality(""));
        assertEquals("Авто", AutoQualityHelper.sanitizeSavedOnlineQuality("   "));
        assertEquals("Авто", AutoQualityHelper.sanitizeSavedOnlineQuality("auto"));
        assertEquals("Авто", AutoQualityHelper.sanitizeSavedOnlineQuality("Авто"));
        assertEquals("Авто", AutoQualityHelper.sanitizeSavedOnlineQuality("Скачанный файл (1080p)"));
        assertEquals("Авто", AutoQualityHelper.sanitizeSavedOnlineQuality("Скачанный файл"));
        assertEquals("1080p", AutoQualityHelper.sanitizeSavedOnlineQuality("1080p"));
        assertEquals("1080p", AutoQualityHelper.sanitizeSavedOnlineQuality("1080"));
        assertEquals("720p", AutoQualityHelper.sanitizeSavedOnlineQuality("720p"));
        assertEquals("2160p", AutoQualityHelper.sanitizeSavedOnlineQuality("4K"));
        assertEquals("2160p", AutoQualityHelper.sanitizeSavedOnlineQuality("2160p"));
    }

    @Test
    public void testResolveClosestManualQualityPicksNearestLowerWhenTargetMissing() {
        List<String> max720 = Arrays.asList("Авто", "720p", "480p", "360p");
        assertEquals("720p", AutoQualityHelper.resolveClosestManualQuality(max720, "1080p"));
        assertEquals("720p", AutoQualityHelper.resolveBestQuality(null, max720, "1080p", 0));

        List<String> max1080 = Arrays.asList("1080p", "720p", "480p");
        assertEquals("1080p", AutoQualityHelper.resolveClosestManualQuality(max1080, "2160p"));
        assertEquals("720p", AutoQualityHelper.resolveClosestManualQuality(max1080, "720p"));

        List<String> min480 = Arrays.asList("1080p", "720p", "480p");
        assertEquals("480p", AutoQualityHelper.resolveClosestManualQuality(min480, "360p"));
    }

    @Test
    public void testStrictResolutionBadgeNeverReturnsAutoOrDownloadedText() {
        assertEquals("", FlexibleBottomSheetUtils.getStrictResolutionBadge(null));
        assertEquals("", FlexibleBottomSheetUtils.getStrictResolutionBadge(""));
        assertEquals("", FlexibleBottomSheetUtils.getStrictResolutionBadge("Авто"));
        assertEquals("", FlexibleBottomSheetUtils.getStrictResolutionBadge("auto"));
        assertEquals("", FlexibleBottomSheetUtils.getStrictResolutionBadge("Скачанный файл"));

        assertEquals("4K", FlexibleBottomSheetUtils.getStrictResolutionBadge("2160p"));
        assertEquals("4K", FlexibleBottomSheetUtils.getStrictResolutionBadge("4K"));
        assertEquals("2K", FlexibleBottomSheetUtils.getStrictResolutionBadge("1440p"));
        assertEquals("FHD", FlexibleBottomSheetUtils.getStrictResolutionBadge("1080p"));
        assertEquals("FHD", FlexibleBottomSheetUtils.getStrictResolutionBadge("Скачанный файл (1080p)"));
        assertEquals("HD", FlexibleBottomSheetUtils.getStrictResolutionBadge("720p"));
        assertEquals("HD", FlexibleBottomSheetUtils.getStrictResolutionBadge("Скачанный файл (720p)"));
        assertEquals("SD", FlexibleBottomSheetUtils.getStrictResolutionBadge("480p"));
        assertEquals("SD", FlexibleBottomSheetUtils.getStrictResolutionBadge("360p"));

        assertEquals("FHD", FloatingBottomSheetUtils.getStrictResolutionBadge("1080p"));
        assertEquals("", FloatingBottomSheetUtils.getStrictResolutionBadge("Авто"));
    }

    @Test
    public void testFormatVideoDimensionsToQualityHandlesStandardAndUltrawideFrames() {
        assertNull(AutoQualityHelper.formatVideoDimensionsToQuality(0, 0));
        assertEquals("2160p", AutoQualityHelper.formatVideoDimensionsToQuality(3840, 2160));
        assertEquals("1440p", AutoQualityHelper.formatVideoDimensionsToQuality(2560, 1440));
        assertEquals("1080p", AutoQualityHelper.formatVideoDimensionsToQuality(1920, 1080));
        assertEquals("1080p", AutoQualityHelper.formatVideoDimensionsToQuality(1920, 804));
        assertEquals("720p", AutoQualityHelper.formatVideoDimensionsToQuality(1280, 720));
        assertEquals("480p", AutoQualityHelper.formatVideoDimensionsToQuality(854, 480));
        assertEquals("360p", AutoQualityHelper.formatVideoDimensionsToQuality(640, 360));
    }

    @Test
    public void testQualityAdapterSelectionHighlightsDownloadedAutoAndClosestLower() {
        List<String> withDownloadedAndMax720 = Arrays.asList(
                "Скачанный файл (1080p)",
                "Авто",
                "720p",
                "480p",
                "360p"
        );

        QualityAdapter downloadedSelected = new QualityAdapter(
                withDownloadedAndMax720, "Скачанный файл (1080p)", "1080p", q -> {});
        assertEquals(0, downloadedSelected.getSelectedIndex());

        QualityAdapter autoSelected = new QualityAdapter(
                withDownloadedAndMax720, "Авто", "720p", q -> {});
        assertEquals(1, autoSelected.getSelectedIndex());

        QualityAdapter db1080FallbackTo720 = new QualityAdapter(
                withDownloadedAndMax720, "1080p", "720p", q -> {});
        assertEquals(2, db1080FallbackTo720.getSelectedIndex());

        QualityAdapter exact480 = new QualityAdapter(
                withDownloadedAndMax720, "480p", "480p", q -> {});
        assertEquals(3, exact480.getSelectedIndex());
    }

    @Test
    public void testFormatDownloadedOptionLabelAndMatchQuality() {
        DownloadedEpisodeEntity entity = new DownloadedEpisodeEntity(
                "1_1_team", "1", "Anime", 1, "1", "Ep 1", "Team", "animelib", "/tmp/ep1.mp4", 1024L, 1000L, "1080"
        );
        assertEquals("Скачанный файл (1080p)", PlayerDownloadController.formatDownloadedOptionLabel(entity));

        assertTrue(AutoQualityHelper.matchQuality("Авто", "auto"));
        assertTrue(AutoQualityHelper.matchQuality("1080p", "1080"));
        assertFalse(AutoQualityHelper.matchQuality("Авто", "1080p"));
        assertFalse(AutoQualityHelper.matchQuality("Скачанный файл (1080p)", "1080p"));
        assertEquals("720p", AutoQualityHelper.resolveBestQuality(null, Collections.singletonList("720p"), "Авто", 0));
    }
}
