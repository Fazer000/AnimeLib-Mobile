package com.example.animelib;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.animelib.managers.DownloadsManager;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.OfflineEpisodeMetadata;
import com.example.animelib.util.FileUtils;
import com.example.animelib.util.HlsPlaylistParser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class OfflineDownloadUtilsTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void timecodesJson_roundTripAndValidation() {
        EpisodeResponse.TimecodeData op = new EpisodeResponse.TimecodeData();
        op.setType("opening");
        op.setFrom(90);
        op.setTo(180);

        EpisodeResponse.TimecodeData ed = new EpisodeResponse.TimecodeData();
        ed.setType("ending");
        ed.setFrom(1260);
        ed.setTo(1350);

        EpisodeResponse.TimecodeData invalid = new EpisodeResponse.TimecodeData();
        invalid.setType("splashscreen");
        invalid.setFrom(50);
        invalid.setTo(50); // invalid: to <= from

        String json = OfflineEpisodeMetadata.toTimecodesJson(Arrays.asList(op, ed, invalid));
        assertNotNull(json);

        List<EpisodeResponse.TimecodeData> parsed = OfflineEpisodeMetadata.parseTimecodesJson(json);
        assertEquals(2, parsed.size());
        assertEquals("opening", parsed.get(0).getType());
        assertEquals(90, parsed.get(0).getFrom());
        assertEquals(180, parsed.get(0).getTo());
        assertEquals("ending", parsed.get(1).getType());
        assertEquals(1260, parsed.get(1).getFrom());
        assertEquals(1350, parsed.get(1).getTo());
    }

    @Test
    public void subtitlesJson_roundTripPreservesLabels() {
        List<OfflineEpisodeMetadata.DownloadedSubtitleInfo> list = new ArrayList<>();
        list.add(new OfflineEpisodeMetadata.DownloadedSubtitleInfo(
                10, "ass", "Надписи + диалоги", "ep1.ass", "/tmp/ep1_sub_0.ass", 12345L));
        list.add(new OfflineEpisodeMetadata.DownloadedSubtitleInfo(
                11, "vtt", null, null, "/tmp/ep1_sub_1.vtt", 4321L));

        String json = OfflineEpisodeMetadata.toSubtitlesJson(list);
        assertNotNull(json);

        List<OfflineEpisodeMetadata.DownloadedSubtitleInfo> parsed = OfflineEpisodeMetadata.parseSubtitlesJson(json);
        assertEquals(2, parsed.size());
        assertEquals("Надписи + диалоги", parsed.get(0).getDisplayLabel(0));
        assertEquals("ass", parsed.get(0).getFormat());
        assertEquals(12345L, parsed.get(0).getFileSize());
        assertEquals("Субтитры (VTT #2)", parsed.get(1).getDisplayLabel(1));
    }

    @Test
    public void normalizeLocalFilePath_handlesRawAndFileUris() {
        assertEquals("/storage/emulated/0/ep1.mp4",
                OfflineEpisodeMetadata.normalizeLocalFilePath("/storage/emulated/0/ep1.mp4"));
        assertEquals("/storage/emulated/0/ep1.mp4",
                OfflineEpisodeMetadata.normalizeLocalFilePath("file:///storage/emulated/0/ep1.mp4"));
        assertEquals("/storage/emulated/0/my folder/ep 1.mp4",
                OfflineEpisodeMetadata.normalizeLocalFilePath("file:///storage/emulated/0/my%20folder/ep%201.mp4"));
        assertNull(OfflineEpisodeMetadata.normalizeLocalFilePath("https://video1.cdnlibs.org/ep1.mp4"));
        assertTrue(OfflineEpisodeMetadata.isLocalMediaPath("file:///data/test.mp4"));
        assertFalse(OfflineEpisodeMetadata.isLocalMediaPath("https://example.com/test.mp4"));
    }

    @Test
    public void episodeOrderAndRussianPluralization() {
        assertTrue(OfflineEpisodeMetadata.parseEpisodeOrder("12") < OfflineEpisodeMetadata.parseEpisodeOrder("12.5"));
        assertTrue(OfflineEpisodeMetadata.parseEpisodeOrder("12.5") < OfflineEpisodeMetadata.parseEpisodeOrder("13"));
        assertEquals(2.0, OfflineEpisodeMetadata.parseEpisodeOrder("OVA 2"), 0.001);

        assertEquals("1 серия", OfflineEpisodeMetadata.formatRussianEpisodesCount(1));
        assertEquals("2 серии", OfflineEpisodeMetadata.formatRussianEpisodesCount(2));
        assertEquals("4 серии", OfflineEpisodeMetadata.formatRussianEpisodesCount(4));
        assertEquals("5 серий", OfflineEpisodeMetadata.formatRussianEpisodesCount(5));
        assertEquals("11 серий", OfflineEpisodeMetadata.formatRussianEpisodesCount(11));
        assertEquals("14 серий", OfflineEpisodeMetadata.formatRussianEpisodesCount(14));
        assertEquals("21 серия", OfflineEpisodeMetadata.formatRussianEpisodesCount(21));
        assertEquals("22 серии", OfflineEpisodeMetadata.formatRussianEpisodesCount(22));
        assertEquals("25 серий", OfflineEpisodeMetadata.formatRussianEpisodesCount(25));
    }

    @Test
    public void hlsMasterPlaylist_selectsPreferredAndFallbackQuality() {
        String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n"
                + "360/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\n"
                + "720/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\n"
                + "1080/index.m3u8\n";
        String baseUrl = "https://cdn.example.com/hls/anime/master.m3u8?token=abc/def";

        assertEquals("https://cdn.example.com/hls/anime/720/index.m3u8",
                HlsPlaylistParser.selectBestVariantUrl(master, baseUrl, "720p"));
        assertEquals("https://cdn.example.com/hls/anime/720/index.m3u8",
                HlsPlaylistParser.selectBestVariantUrl(master, baseUrl, "900"));
        assertEquals("https://cdn.example.com/hls/anime/1080/index.m3u8",
                HlsPlaylistParser.selectBestVariantUrl(master, baseUrl, "2160p"));
        assertEquals("https://cdn.example.com/hls/anime/1080/index.m3u8",
                HlsPlaylistParser.selectBestVariantUrl(master, baseUrl, null));
    }

    @Test
    public void hlsMediaPlaylist_parsesMapKeyAndDecryptsAes128() throws Exception {
        String media = "#EXTM3U\n"
                + "#EXT-X-MEDIA-SEQUENCE:5\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"keys/enc.key\"\n"
                + "#EXTINF:6.0,\n"
                + "seg0.m4s\n"
                + "#EXTINF:6.0,\n"
                + "seg1.m4s\n";

        HlsPlaylistParser.ParsedMediaPlaylist parsed =
                HlsPlaylistParser.parseMediaPlaylist(media, "https://cdn.example.com/v1/stream/index.m3u8");

        assertEquals("https://cdn.example.com/v1/stream/init.mp4", parsed.initSegmentUrl);
        assertEquals(2, parsed.segments.size());
        assertTrue(parsed.segments.get(0).isAes128Encrypted());
        assertEquals("https://cdn.example.com/v1/stream/keys/enc.key", parsed.segments.get(0).keyUrl);
        assertEquals(5, parsed.segments.get(0).mediaSequence);
        assertEquals(6, parsed.segments.get(1).mediaSequence);
        assertArrayEquals(HlsPlaylistParser.sequenceNumberToIv(5), parsed.segments.get(0).iv);

        byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] iv = parsed.segments.get(0).iv;
        byte[] plain = "Hello HLS AES-128 segment payload!".getBytes(StandardCharsets.UTF_8);

        Cipher enc = Cipher.getInstance("AES/CBC/PKCS5Padding");
        enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = enc.doFinal(plain);

        byte[] decrypted = HlsPlaylistParser.decryptAes128(encrypted, key, iv);
        assertArrayEquals(plain, decrypted);
    }

    @Test
    public void fileUtils_subtitlesDiscoveryAndCleanup() throws Exception {
        File animeDir = tempFolder.newFolder("anime_42");
        File ep1 = writeDummyFile(new File(animeDir, "ep_100_1_Team.mp4"), "video1");
        File ep1Part = writeDummyFile(new File(animeDir, "ep_100_1_Team.mp4.part"), "part");
        File ep1SubAss = writeDummyFile(new File(animeDir, "ep_100_1_Team_sub_0.ass"), "[Script Info]\n");
        File ep1SubVtt = writeDummyFile(new File(animeDir, "ep_100_1_Team_sub_1.vtt"), "WEBVTT\n");
        File ep1SubTmp = writeDummyFile(new File(animeDir, "ep_100_1_Team_sub_2.ass.tmp"), "partial");
        File ep1SegsDir = new File(animeDir, "ep_100_1_Team.mp4_segs");
        assertTrue(ep1SegsDir.mkdirs());
        writeDummyFile(new File(ep1SegsDir, "seg_00000.ts"), "tsdata");

        File ep2 = writeDummyFile(new File(animeDir, "ep_101_2_Team.mp4"), "video2");
        File ep2Sub = writeDummyFile(new File(animeDir, "ep_101_2_Team_sub_0.ass"), "[Script Info]\n");

        // Discovery works for file:// URI and skips .tmp
        List<File> found = FileUtils.findLocalSubtitleFilesForVideo(ep1.toURI().toString());
        assertEquals(2, found.size());
        assertEquals(ep1SubAss.getName(), found.get(0).getName());
        assertEquals(ep1SubVtt.getName(), found.get(1).getName());

        // Total disk size counts video + completed subtitles
        assertEquals(ep1.length() + ep1SubAss.length() + ep1SubVtt.length(),
                FileUtils.getEpisodeTotalDiskSize(ep1.getAbsolutePath()));

        // deleteDownloadTempFiles removes only .part, _segs, .tmp — keeps .mp4 and subtitles
        FileUtils.deleteDownloadTempFiles(ep1.getAbsolutePath());
        assertFalse(ep1Part.exists());
        assertFalse(ep1SegsDir.exists());
        assertFalse(ep1SubTmp.exists());
        assertTrue(ep1.exists());
        assertTrue(ep1SubAss.exists());
        assertTrue(ep1SubVtt.exists());

        // deleteDownloadedEpisodeFiles removes ep1 video + subtitles without touching ep2
        FileUtils.deleteDownloadedEpisodeFiles(ep1.getAbsolutePath());
        assertFalse(ep1.exists());
        assertFalse(ep1SubAss.exists());
        assertFalse(ep1SubVtt.exists());
        assertTrue(ep2.exists());
        assertTrue(ep2Sub.exists());
    }

    @Test
    public void animelibQualitySelection_picksExactThenClosestLower() {
        EpisodeResponse.QualityData q360 = makeQuality(360, "/360.mp4");
        EpisodeResponse.QualityData q720 = makeQuality(720, "/720.mp4");
        EpisodeResponse.QualityData q1080 = makeQuality(1080, "/1080.mp4");
        List<EpisodeResponse.QualityData> list = Arrays.asList(q360, q720, q1080);

        assertEquals(720, DownloadsManager.selectBestAnimelibQuality(list, "720").getQuality());
        assertEquals(720, DownloadsManager.selectBestAnimelibQuality(list, "720p").getQuality());
        assertEquals(360, DownloadsManager.selectBestAnimelibQuality(list, "480").getQuality());
        assertEquals(1080, DownloadsManager.selectBestAnimelibQuality(list, "2160p").getQuality());
        assertEquals(1080, DownloadsManager.selectBestAnimelibQuality(list, "").getQuality());
    }

    private static EpisodeResponse.QualityData makeQuality(int q, String href) {
        EpisodeResponse.QualityData data = new EpisodeResponse.QualityData();
        data.setQuality(q);
        data.setHref(href);
        return data;
    }

    private static File writeDummyFile(File f, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }
}
