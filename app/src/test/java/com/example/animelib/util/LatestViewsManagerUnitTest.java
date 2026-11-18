package com.example.animelib.util;

import com.example.animelib.models.AnimeInfoResponse;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import static org.junit.Assert.*;

public class LatestViewsManagerUnitTest {

    private static final Gson GSON = new Gson();

    private JsonObject createRichEntry(int id, String slugUrl, String epNum, int epId, String progressTime) {
        JsonObject entry = new JsonObject();

        JsonObject media = new JsonObject();
        media.addProperty("id", id);
        media.addProperty("name", "Kaijuu 8-gou");
        media.addProperty("rus_name", "Кайдзю номер восемь");
        media.addProperty("eng_name", "Kaiju No. 8");
        media.addProperty("slug_url", slugUrl);
        media.addProperty("slug", "kaijuu-8");
        JsonObject cover = new JsonObject();
        cover.addProperty("filename", "cover.jpg");
        cover.addProperty("default", "https://cover.imglib.info/uploads/cover/kaijuu8/cover/default.jpg");
        cover.addProperty("md", "https://cover.imglib.info/uploads/cover/kaijuu8/cover/md.jpg");
        cover.addProperty("thumbnail", "https://cover.imglib.info/uploads/cover/kaijuu8/cover/thumb.jpg");
        media.add("cover", cover);
        media.addProperty("site", 5);
        media.addProperty("model", "anime");
        entry.add("media", media);

        JsonObject item = new JsonObject();
        item.addProperty("id", epId);
        item.addProperty("number", epNum);
        entry.add("item", item);

        JsonObject progress = new JsonObject();
        progress.addProperty("current", progressTime);
        progress.addProperty("total", "24:00");
        progress.addProperty("percent", 50.0);
        entry.add("progress", progress);

        JsonObject meta = new JsonObject();
        meta.addProperty("episode", epId);
        meta.addProperty("player", "Animelib");
        entry.add("meta", meta);

        return entry;
    }

    private JsonObject createOfflineIncompleteEntry(int id, String slugUrl, String title, String epNum, int epId, String progressTime) {
        JsonObject entry = new JsonObject();

        JsonObject media = new JsonObject();
        media.addProperty("id", id);
        media.addProperty("name", title);
        media.addProperty("rus_name", title);
        media.addProperty("eng_name", "");
        media.addProperty("slug", slugUrl);
        media.addProperty("slug_url", slugUrl);
        media.add("cover", new JsonObject());
        media.addProperty("site", 5);
        media.addProperty("model", "anime");
        entry.add("media", media);

        JsonObject item = new JsonObject();
        item.addProperty("id", epId);
        item.addProperty("number", epNum);
        entry.add("item", item);

        JsonObject progress = new JsonObject();
        progress.addProperty("current", progressTime);
        progress.addProperty("total", "24:00");
        progress.addProperty("percent", 75.0);
        entry.add("progress", progress);

        JsonObject meta = new JsonObject();
        meta.addProperty("episode", epId);
        meta.addProperty("player", "Animelib");
        entry.add("meta", meta);

        return entry;
    }

    @Test
    public void testOfflineIncompleteEntryPreservesExistingPosterTitlesAndSlug() {
        JsonArray existing = new JsonArray();
        existing.add(createRichEntry(999, "999--other-anime", "1", 10, "05:00"));
        existing.add(createRichEntry(25673, "25673--kaijuu-8", "3", 3003, "12:00"));

        // Офлайн-плеер сохраняет запись без постера, без --slug и даже с заглушкой "Аниме"
        JsonObject offlineNew = createOfflineIncompleteEntry(0, "25673", "Аниме", "4", 0, "18:00");
        // Номер серии 4 (другая серия -> 0 допускается или берётся новый), проверим сценарий той же серии отдельно ниже
        JsonArray merged = LatestViewsManager.mergeIntoLatestViewsArray(existing, offlineNew, 50);

        assertEquals(2, merged.size());
        JsonObject top = merged.get(0).getAsJsonObject();
        JsonObject topMedia = top.getAsJsonObject("media");

        assertEquals(25673, topMedia.get("id").getAsInt());
        assertEquals("Kaijuu 8-gou", topMedia.get("name").getAsString());
        assertEquals("Кайдзю номер восемь", topMedia.get("rus_name").getAsString());
        assertEquals("Kaiju No. 8", topMedia.get("eng_name").getAsString());
        assertEquals("25673--kaijuu-8", topMedia.get("slug_url").getAsString());
        assertEquals("kaijuu-8", topMedia.get("slug").getAsString());
        assertTrue(LatestViewsManager.hasValidCover(topMedia));
        assertFalse(LatestViewsManager.isIncompleteMedia(topMedia));
        assertEquals("https://cover.imglib.info/uploads/cover/kaijuu8/cover/default.jpg",
                topMedia.getAsJsonObject("cover").get("default").getAsString());

        // Прогресс должен обновиться на новый из офлайн-просмотра
        assertEquals("4", top.getAsJsonObject("item").get("number").getAsString());
        assertEquals("18:00", top.getAsJsonObject("progress").get("current").getAsString());
    }

    @Test
    public void testSameMediaMatchesAndDeduplicatesWhenNumericIdMatchesSlugPrefix() {
        JsonArray existing = new JsonArray();
        existing.add(createRichEntry(25673, "25673--kaijuu-8", "3", 3003, "12:00"));
        // Повторная старая битая запись того же тайтла
        existing.add(createOfflineIncompleteEntry(0, "25673", "Anime", "2", 3002, "09:00"));

        JsonObject sameTitleUpdate = createOfflineIncompleteEntry(25673, "25673", "Кайдзю номер восемь", "3", 0, "15:30");
        JsonArray merged = LatestViewsManager.mergeIntoLatestViewsArray(existing, sameTitleUpdate, 50);

        assertEquals(1, merged.size());
        JsonObject entry = merged.get(0).getAsJsonObject();
        // Если id эпизода в офлайне был 0 для того же номера серии "3", восстанавливается реальный epId = 3003
        assertEquals(3003, entry.getAsJsonObject("item").get("id").getAsInt());
        assertEquals(3003, entry.getAsJsonObject("meta").get("episode").getAsInt());
        assertEquals("Kaiju No. 8", entry.getAsJsonObject("media").get("eng_name").getAsString());
    }

    @Test
    public void testLocalDiskPosterPathsAreRejectedAndNeverInjectedAsCover() {
        JsonObject mediaWithLocalPoster = new JsonObject();
        JsonObject localCover = new JsonObject();
        localCover.addProperty("default", "/data/user/0/com.example.animelib/files/cached_posters/25673.jpg");
        localCover.addProperty("md", "file:///storage/emulated/0/poster.jpg");
        mediaWithLocalPoster.add("cover", localCover);

        assertFalse(LatestViewsManager.isValidRemoteCoverValue("/data/user/0/com.example.animelib/files/cached_posters/25673.jpg"));
        assertFalse(LatestViewsManager.isValidRemoteCoverValue("file:///storage/emulated/0/poster.jpg"));
        assertFalse(LatestViewsManager.hasValidCover(mediaWithLocalPoster));

        JsonObject mergedMedia = LatestViewsManager.mergeMediaObjects(mediaWithLocalPoster, null);
        assertFalse(LatestViewsManager.hasValidCover(mergedMedia));
        assertEquals(0, mergedMedia.getAsJsonObject("cover").size());
    }

    @Test
    public void testApiEnrichmentPopulatesPosterTitlesAndSlugWithoutClobberingProgress() {
        JsonArray stored = new JsonArray();
        JsonObject offlineEntry = createOfflineIncompleteEntry(25673, "25673", "Аниме", "7", 7007, "19:45");
        stored.add(offlineEntry);

        assertTrue(LatestViewsManager.isIncompleteMedia(offlineEntry.getAsJsonObject("media")));

        String apiJson = "{"
                + "\"data\":{"
                + "  \"id\":25673,"
                + "  \"name\":\"Sousou no Frieren\","
                + "  \"rus_name\":\"Провожающая в последний путь Фрирен\","
                + "  \"eng_name\":\"Frieren: Beyond Journey's End\","
                + "  \"slug_url\":\"25673--sousou-no-frieren\","
                + "  \"cover\":{"
                + "    \"filename\":\"frieren.jpg\","
                + "    \"thumbnail\":\"https://cover.imglib.info/uploads/frieren/thumb.jpg\","
                + "    \"default\":\"https://cover.imglib.info/uploads/frieren/default.jpg\","
                + "    \"md\":\"https://cover.imglib.info/uploads/frieren/md.jpg\""
                + "  }"
                + "}"
                + "}";
        AnimeInfoResponse resp = GSON.fromJson(apiJson, AnimeInfoResponse.class);
        JsonObject richMedia = LatestViewsManager.buildMediaObjectFromAnimeInfo(resp.getData());

        boolean applied = LatestViewsManager.applyEnrichedMediaToArray(
                stored,
                offlineEntry.getAsJsonObject("media"),
                richMedia
        );

        assertTrue(applied);
        JsonObject enrichedEntry = stored.get(0).getAsJsonObject();
        JsonObject enrichedMedia = enrichedEntry.getAsJsonObject("media");

        assertFalse(LatestViewsManager.isIncompleteMedia(enrichedMedia));
        assertEquals("Sousou no Frieren", enrichedMedia.get("name").getAsString());
        assertEquals("Провожающая в последний путь Фрирен", enrichedMedia.get("rus_name").getAsString());
        assertEquals("Frieren: Beyond Journey's End", enrichedMedia.get("eng_name").getAsString());
        assertEquals("25673--sousou-no-frieren", enrichedMedia.get("slug_url").getAsString());
        assertEquals("sousou-no-frieren", enrichedMedia.get("slug").getAsString());
        assertEquals("https://cover.imglib.info/uploads/frieren/default.jpg",
                enrichedMedia.getAsJsonObject("cover").get("default").getAsString());

        // Прогресс просмотра должен остаться нетронутым
        assertEquals("7", enrichedEntry.getAsJsonObject("item").get("number").getAsString());
        assertEquals(7007, enrichedEntry.getAsJsonObject("item").get("id").getAsInt());
        assertEquals("19:45", enrichedEntry.getAsJsonObject("progress").get("current").getAsString());
    }

    @Test
    public void testMergeArrayFromJsRespectsDeletionsButKeepsEnrichedMetadata() {
        JsonArray nativeExisting = new JsonArray();
        nativeExisting.add(createRichEntry(25673, "25673--kaijuu-8", "5", 3005, "21:00"));
        nativeExisting.add(createRichEntry(100, "100--deleted-on-site", "1", 1001, "02:00"));

        // Сайт прислал список после удаления второго тайтла, но для 25673 в localStorage была старая неполная запись
        JsonArray jsIncoming = new JsonArray();
        jsIncoming.add(createOfflineIncompleteEntry(25673, "25673", "Аниме", "5", 3005, "21:10"));

        JsonArray result = LatestViewsManager.mergeArrayFromJs(nativeExisting, jsIncoming, 50);
        assertEquals(1, result.size());

        JsonObject merged = result.get(0).getAsJsonObject();
        JsonObject media = merged.getAsJsonObject("media");
        assertTrue(LatestViewsManager.hasValidCover(media));
        assertEquals("Кайдзю номер восемь", media.get("rus_name").getAsString());
        assertEquals("25673--kaijuu-8", media.get("slug_url").getAsString());
        assertEquals("21:10", merged.getAsJsonObject("progress").get("current").getAsString());
    }

    @Test
    public void testPlaceholderTitleDetection() {
        assertTrue(LatestViewsManager.isPlaceholderTitle(null));
        assertTrue(LatestViewsManager.isPlaceholderTitle(""));
        assertTrue(LatestViewsManager.isPlaceholderTitle("Anime"));
        assertTrue(LatestViewsManager.isPlaceholderTitle("Аниме"));
        assertTrue(LatestViewsManager.isPlaceholderTitle("Загрузка..."));
        assertTrue(LatestViewsManager.isPlaceholderTitle("Аниме #25673"));
        assertTrue(LatestViewsManager.isPlaceholderTitle("—"));

        assertFalse(LatestViewsManager.isPlaceholderTitle("Кайдзю номер восемь"));
        assertFalse(LatestViewsManager.isPlaceholderTitle("Naruto"));
    }

    @Test
    public void testWebViewMergeScriptContainsSafeMergeHelpers() {
        String script = LatestViewsManager.buildWebViewMergeScript("[{\"media\":{\"id\":25673}}]");
        assertTrue(script.contains("mergeMedia"));
        assertTrue(script.contains("hasValidCover"));
        assertTrue(script.contains("isPlaceholderTitle"));
        assertTrue(script.contains("extractNumericId"));
        assertNotNull(JsonParser.parseString("[{\"media\":{\"id\":25673}}]"));
    }
}
