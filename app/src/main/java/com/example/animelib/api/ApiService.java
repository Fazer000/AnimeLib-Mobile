package com.example.animelib.api;

import android.content.Context;

import com.example.animelib.data.DatabaseManager;
import com.example.animelib.data.entity.PlayerPreferences;
import com.example.animelib.models.*;
import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;

/**
 * Единый фасад API приложения.
 * Делегирует сетевую логику специализированным модулям:
 * - {@link ApiClientCore} — HTTP-клиент, заголовки, токены, парсинг ошибок и URL-утилиты
 * - {@link ApiCacheManager} — кэширование с TTL и дедупликацией параллельных запросов
 * - {@link AnimeCatalogApi} — тайтлы, эпизоды, поиск, Kodik, связанные тайтлы, правила
 * - {@link CommentsApi} — комментарии, закреплённые комментарии, голосование, модерация
 * - {@link BookmarksApi} — закладки и статусы просмотра
 * - {@link ShikimoriApi} — франшизы и метаданные Shikimori
 */
public class ApiService {

    // ======================== Callback Interfaces ========================

    public interface EpisodesCallback {
        void onEpisodesReceived(EpisodesListResponse response);
        void onError(String error);
    }

    public interface EpisodeDataCallback {
        void onEpisodeDataReceived(EpisodeResponse response);
        void onError(String error);
    }

    public interface KodikVideoCallback {
        void onKodikVideoReceived(KodikResponse response);
        void onError(String error);
    }

    public interface AnimeInfoCallback {
        void onAnimeInfoReceived(AnimeInfoResponse response);
        void onError(String error);
    }

    public interface RelatedTitlesCallback {
        void onRelatedTitlesReceived(RelatedTitlesResponse response);
        void onError(String error);
    }

    public interface FranchiseCallback {
        void onFranchiseReceived(FranchiseResponse response);
        void onError(String error);
    }

    public interface EpisodeCommentsCallback {
        void onCommentsReceived(CommentsResponse response);
        void onError(String error);
    }

    public interface StickyCommentsCallback {
        void onStickyCommentsReceived(List<CommentsResponse.CommentItem> stickyComments);
        void onError(String error);
    }

    public interface PostCommentCallback {
        void onSuccess(CommentsResponse.CommentItem commentItem);
        void onError(String error);
    }

    public interface VoteCommentCallback {
        void onSuccess(int newVoteValue, CommentsResponse.Votes votes);
        void onError(String error);
    }

    public interface DeleteCommentCallback {
        void onSuccess();
        void onError(String error);
    }

    public interface EditCommentCallback {
        void onSuccess(CommentsResponse.CommentItem commentItem);
        void onError(String error);
    }

    public interface IgnoreUserCallback {
        void onSuccess(String message);
        void onError(String errorMsg);
    }

    public interface SearchCallback {
        void onSearchResults(SearchResponse response);
        void onError(String error);
    }

    public interface ToastCheckCallback {
        void onToastReceived(String message, String newUrl);
        void onError(String error);
    }

    public interface BookmarkCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    public interface AnimeBookmarkCallback {
        void onBookmarkReceived(AnimeBookmarkResponse response);
        void onError(String error);
    }

    public interface BookmarksListCallback {
        void onBookmarksReceived(BookmarksListResponse response);
        void onError(String error);
    }

    public interface ArticleCallback {
        void onArticleReceived(ArticleResponse response);
        void onError(String error);
    }

    public interface ViewCallback {
        void onSuccess();
        void onError(String error);
    }

    public interface ShikimoriAnimeDetailsCallback {
        void onSuccess(String name, String russianName);
        void onError(String error);
    }

    // ======================== Sync Result Models ========================

    public static class BookmarkFetchSyncResult {
        public final boolean serverResponded;
        public final boolean networkUnavailable;
        public final int httpCode;
        public final AnimeBookmarkResponse response;

        public BookmarkFetchSyncResult(boolean serverResponded, boolean networkUnavailable, int httpCode, AnimeBookmarkResponse response) {
            this.serverResponded = serverResponded;
            this.networkUnavailable = networkUnavailable;
            this.httpCode = httpCode;
            this.response = response;
        }
    }

    public static class BookmarkSaveResult {
        public final boolean success;
        public final boolean retryable;
        public final int httpCode;
        public final String message;

        public BookmarkSaveResult(boolean success, boolean retryable, int httpCode, String message) {
            this.success = success;
            this.retryable = retryable;
            this.httpCode = httpCode;
            this.message = message;
        }
    }

    public static class WatchStatusSyncResult {
        public final boolean success;
        public final boolean rateLimited;
        public final int httpCode;
        public final String message;

        public WatchStatusSyncResult(boolean success, boolean rateLimited, int httpCode, String message) {
            this.success = success;
            this.rateLimited = rateLimited;
            this.httpCode = httpCode;
            this.message = message;
        }
    }

    public static class SearchSyncResult {
        public final boolean success;
        public final boolean rateLimited;
        public final int httpCode;
        public final SearchResponse response;
        public final String message;

        public SearchSyncResult(boolean success, boolean rateLimited, int httpCode, SearchResponse response, String message) {
            this.success = success;
            this.rateLimited = rateLimited;
            this.httpCode = httpCode;
            this.response = response;
            this.message = message;
        }
    }

    public static class BookmarksPageSyncResult {
        public final boolean success;
        public final boolean rateLimited;
        public final int httpCode;
        public final BookmarksListResponse response;
        public final String message;

        public BookmarksPageSyncResult(boolean success, boolean rateLimited, int httpCode, BookmarksListResponse response, String message) {
            this.success = success;
            this.rateLimited = rateLimited;
            this.httpCode = httpCode;
            this.response = response;
            this.message = message;
        }
    }

    public static class AnimeInfoSyncResult {
        public final boolean success;
        public final boolean retryable;
        public final int httpCode;
        public final AnimeInfoResponse response;
        public final String message;

        public AnimeInfoSyncResult(boolean success, boolean retryable, int httpCode, AnimeInfoResponse response, String message) {
            this.success = success;
            this.retryable = retryable;
            this.httpCode = httpCode;
            this.response = response;
            this.message = message;
        }
    }

    // ======================== Sub-services ========================

    private final ApiClientCore core;
    private final AnimeCatalogApi catalogApi;
    private final CommentsApi commentsApi;
    private final BookmarksApi bookmarksApi;
    private final ShikimoriApi shikimoriApi;
    private final DatabaseManager databaseManager;

    public ApiService(Context context) {
        this.core = new ApiClientCore(context);
        this.catalogApi = new AnimeCatalogApi(this.core);
        this.commentsApi = new CommentsApi(this.core);
        this.bookmarksApi = new BookmarksApi(this.core);
        this.shikimoriApi = new ShikimoriApi(this.core);
        this.databaseManager = this.core.getDatabaseManager();
    }

    public ApiClientCore getCore() {
        return core;
    }

    public AnimeCatalogApi getCatalogApi() {
        return catalogApi;
    }

    public CommentsApi getCommentsApi() {
        return commentsApi;
    }

    public BookmarksApi getBookmarksApi() {
        return bookmarksApi;
    }

    public ShikimoriApi getShikimoriApi() {
        return shikimoriApi;
    }

    // ======================== Auth & Headers ========================

    public Map<String, String> getVideoRequestHeaders() {
        return core.getVideoRequestHeaders();
    }

    public static boolean isKodikStreamUrl(String url) {
        return ApiClientCore.isKodikStreamUrl(url);
    }

    public static Map<String, String> buildKodikStreamHeaders() {
        return ApiClientCore.buildKodikStreamHeaders();
    }

    public Map<String, String> getVideoRequestHeadersForUrl(String url) {
        return core.getVideoRequestHeadersForUrl(url);
    }

    public boolean isAuthorized() {
        return core.isAuthorized();
    }

    public static String extractUserIdFromToken(String token) {
        return ApiClientCore.extractUserIdFromToken(token);
    }

    public String getCurrentUserId() {
        return core.getCurrentUserId();
    }

    // ======================== Catalog, Episodes & Kodik ========================

    public ApiClientCore.RequestHandle fetchAnimeInfo(String animeSlugOrId, AnimeInfoCallback callback) {
        return catalogApi.fetchAnimeInfo(animeSlugOrId, callback);
    }

    public AnimeInfoSyncResult fetchAnimeInfoSync(String animeSlugOrId) {
        return catalogApi.fetchAnimeInfoSync(animeSlugOrId);
    }

    public ApiClientCore.RequestHandle searchAnime(String query, SearchCallback callback) {
        return catalogApi.searchAnime(query, callback);
    }

    public ApiClientCore.RequestHandle searchMangaAndRanobe(String query, SearchCallback callback) {
        return catalogApi.searchMangaAndRanobe(query, callback);
    }

    public SearchSyncResult searchAnimeSync(String query) {
        return catalogApi.searchAnimeSync(query);
    }

    public ApiClientCore.RequestHandle fetchEpisodesList(String animeId, EpisodesCallback callback) {
        return catalogApi.fetchEpisodesList(animeId, callback);
    }

    public EpisodesListResponse fetchEpisodesListSync(String animeId) {
        return catalogApi.fetchEpisodesListSync(animeId);
    }

    public ApiClientCore.RequestHandle fetchEpisodeData(int episodeId, EpisodeDataCallback callback) {
        return catalogApi.fetchEpisodeData(episodeId, callback);
    }

    public EpisodeResponse fetchEpisodeDataSync(int episodeId) {
        return catalogApi.fetchEpisodeDataSync(episodeId);
    }

    public void loadAnimeFromUrl(String animeUrl, EpisodeDataCallback callback) {
        catalogApi.loadAnimeFromUrl(animeUrl, callback);
    }

    public void invalidateKodikCache(String kodikSrc) {
        catalogApi.invalidateKodikCache(kodikSrc);
    }

    public ApiClientCore.RequestHandle fetchKodikVideoLinks(String kodikSrc, KodikVideoCallback callback) {
        return catalogApi.fetchKodikVideoLinks(kodikSrc, false, callback);
    }

    public ApiClientCore.RequestHandle fetchKodikVideoLinks(String kodikSrc, boolean forceRefresh, KodikVideoCallback callback) {
        return catalogApi.fetchKodikVideoLinks(kodikSrc, forceRefresh, callback);
    }

    @Deprecated
    public ApiClientCore.RequestHandle fetchKodikVideoLinksUnsafe(String kodikSrc, KodikVideoCallback callback) {
        return fetchKodikVideoLinks(kodikSrc, callback);
    }

    public ApiClientCore.RequestHandle getRelatedTitles(String animeSlug, RelatedTitlesCallback callback) {
        return catalogApi.getRelatedTitles(animeSlug, callback);
    }

    public boolean markEpisodeViewedSync(String animeId, int playerId) {
        return catalogApi.markEpisodeViewedSync(animeId, playerId);
    }

    public void markEpisodeViewed(String animeId, int playerId, ViewCallback callback) {
        catalogApi.markEpisodeViewed(animeId, playerId, callback);
    }

    public void checkApiForToast(ToastCheckCallback callback) {
        catalogApi.checkApiForToast(callback);
    }

    public void getRulesArticle(ArticleCallback callback) {
        catalogApi.getRulesArticle(callback);
    }

    // ======================== Comments & Moderation ========================

    public static void invalidateCommentsCacheMapForEpisode(Map<String, ?> commentsCache,
                                                            Map<Long, ?> stickyCache,
                                                            long episodeId) {
        CommentsApi.invalidateCommentsCacheMapForEpisode(commentsCache, stickyCache, episodeId);
    }

    public void invalidateEpisodeCommentsCache(long episodeId) {
        commentsApi.invalidateEpisodeCommentsCache(episodeId);
    }

    public void invalidateAllCommentsCache() {
        commentsApi.invalidateAllCommentsCache();
    }

    public void fetchEpisodeComments(long episodeId, String sortType, EpisodeCommentsCallback callback) {
        commentsApi.fetchEpisodeComments(episodeId, sortType, 1, false, callback);
    }

    public void fetchEpisodeComments(long episodeId, String sortType, int page, EpisodeCommentsCallback callback) {
        commentsApi.fetchEpisodeComments(episodeId, sortType, page, false, callback);
    }

    public void fetchEpisodeComments(long episodeId, String sortType, int page, boolean forceRefresh, EpisodeCommentsCallback callback) {
        commentsApi.fetchEpisodeComments(episodeId, sortType, page, forceRefresh, callback);
    }

    public ApiClientCore.RequestHandle fetchEpisodeCommentsAsync(long episodeId, String sortType, int page, boolean forceRefresh, EpisodeCommentsCallback callback) {
        return commentsApi.fetchEpisodeComments(episodeId, sortType, page, forceRefresh, true, callback);
    }

    public void fetchStickyComments(long episodeId, StickyCommentsCallback callback) {
        commentsApi.fetchStickyComments(episodeId, callback);
    }

    public ApiClientCore.RequestHandle fetchStickyCommentsAsync(long episodeId, StickyCommentsCallback callback) {
        return commentsApi.fetchStickyComments(episodeId, true, callback);
    }

    public void postComment(long episodeId, String rawCommentText, Long parentCommentId, Long rootId, int commentLevel, PostCommentCallback callback) {
        commentsApi.postComment(episodeId, rawCommentText, parentCommentId, rootId, commentLevel, callback);
    }

    public void editComment(long commentId, long episodeId, String rawCommentText, Long parentCommentId, Long rootId, int commentLevel, EditCommentCallback callback) {
        commentsApi.editComment(commentId, episodeId, rawCommentText, parentCommentId, rootId, commentLevel, callback);
    }

    public void voteComment(long commentId, int voteValue, VoteCommentCallback callback) {
        commentsApi.voteComment(commentId, voteValue, callback);
    }

    public void deleteComment(long commentId, DeleteCommentCallback callback) {
        commentsApi.deleteComment(commentId, callback);
    }

    public void ignoreUser(long sourceableId, long targetUserId, String commentText, IgnoreUserCallback callback) {
        commentsApi.ignoreUser(sourceableId, targetUserId, commentText, callback);
    }

    // ======================== Bookmarks & Watch Status ========================

    public void invalidateAnimeBookmarkCache(String mediaSlug) {
        bookmarksApi.invalidateAnimeBookmarkCache(mediaSlug);
    }

    public BookmarkSaveResult addBookmarkSync(String mediaSlug, int episodeId, int teamId,
                                              int translationType, String playerName,
                                              int episodeNumber, String currentTimecode, Object statusId) {
        return bookmarksApi.addBookmarkSync(mediaSlug, episodeId, teamId, translationType, playerName, episodeNumber, currentTimecode, statusId);
    }

    public void addBookmark(String mediaSlug, int episodeId, int teamId, int episodeNumber,
                            String currentTimecode, BookmarkCallback callback) {
        bookmarksApi.addBookmark(mediaSlug, episodeId, teamId, episodeNumber, currentTimecode, null, callback);
    }

    public void addBookmark(String mediaSlug, int episodeId, int teamId, int episodeNumber,
                            String currentTimecode, Object statusId, BookmarkCallback callback) {
        bookmarksApi.addBookmark(mediaSlug, episodeId, teamId, episodeNumber, currentTimecode, statusId, callback);
    }

    public void updateWatchStatus(String mediaSlug, Object statusId, BookmarkCallback callback) {
        bookmarksApi.updateWatchStatus(mediaSlug, statusId, callback);
    }

    public WatchStatusSyncResult updateWatchStatusSync(String mediaSlug, Object statusId) {
        return bookmarksApi.updateWatchStatusSync(mediaSlug, statusId);
    }

    public BookmarksPageSyncResult fetchBookmarksPageSync(int page, int status, String userId) {
        return bookmarksApi.fetchBookmarksPageSync(page, status, userId);
    }

    public void fetchAnimeBookmark(String mediaSlug, AnimeBookmarkCallback callback) {
        bookmarksApi.fetchAnimeBookmark(mediaSlug, callback);
    }

    public AnimeBookmarkResponse fetchAnimeBookmarkSync(String mediaSlug) {
        return bookmarksApi.fetchAnimeBookmarkSync(mediaSlug);
    }

    public BookmarkFetchSyncResult fetchAnimeBookmarkWithStatusSync(String mediaSlug, boolean forceRefresh) {
        return bookmarksApi.fetchAnimeBookmarkWithStatusSync(mediaSlug, forceRefresh);
    }

    public void fetchBookmarksList(BookmarksListCallback callback) {
        bookmarksApi.fetchBookmarksList(callback);
    }

    // ======================== Shikimori ========================

    public static long extractShikimoriId(String shikimoriHref) {
        return ApiClientCore.extractShikimoriId(shikimoriHref);
    }

    public static FranchiseResponse getCachedFranchise(long shikimoriId) {
        return ShikimoriApi.getCachedFranchise(shikimoriId);
    }

    public static void cacheFranchise(long shikimoriId, FranchiseResponse response) {
        ShikimoriApi.cacheFranchise(shikimoriId, response);
    }

    public void getFranchise(long shikimoriId, FranchiseCallback callback) {
        shikimoriApi.getFranchise(shikimoriId, callback);
    }

    public void fetchShikimoriAnimeDetails(long animeId, ShikimoriAnimeDetailsCallback callback) {
        shikimoriApi.fetchShikimoriAnimeDetails(animeId, callback);
    }

    // ======================== URL & Timecode Helpers ========================

    public String extractAnimeId(String url) {
        return ApiClientCore.extractAnimeId(url);
    }

    public String extractAnimeSlug(String url) {
        return ApiClientCore.extractAnimeSlug(url);
    }

    public static String extractMediaSlugFromUrl(String animeUrl) {
        return ApiClientCore.extractMediaSlugFromUrl(animeUrl);
    }

    public static String formatTimecode(long milliseconds) {
        return ApiClientCore.formatTimecode(milliseconds);
    }

    // ======================== Settings & Database Delegates ========================

    public void save4KSetting(boolean enable4K) {
        databaseManager.save4KSetting(enable4K);
    }

    public boolean load4KSetting() {
        return databaseManager.load4KSetting();
    }

    public void saveAmbientLightSetting(boolean enableAmbientLight) {
        databaseManager.saveAmbientLightSetting(enableAmbientLight);
    }

    public boolean loadAmbientLightSetting() {
        return databaseManager.loadAmbientLightSetting();
    }

    public void saveVideoResizeModeSetting(int resizeMode) {
        databaseManager.saveVideoResizeModeSetting(resizeMode);
    }

    public int loadVideoResizeModeSetting() {
        return databaseManager.loadVideoResizeModeSetting();
    }

    public void saveAutoSkipSegmentsSettings(boolean opening, boolean ending, boolean splashscreen, boolean compilation) {
        databaseManager.saveAutoSkipSegmentsSettings(opening, ending, splashscreen, compilation);
    }

    public boolean loadAutoSkipOpeningSetting() {
        return databaseManager.loadAutoSkipOpeningSetting();
    }

    public boolean loadAutoSkipEndingSetting() {
        return databaseManager.loadAutoSkipEndingSetting();
    }

    public boolean loadAutoSkipSplashscreenSetting() {
        return databaseManager.loadAutoSkipSplashscreenSetting();
    }

    public boolean loadAutoSkipCompilationSetting() {
        return databaseManager.loadAutoSkipCompilationSetting();
    }

    public void saveVideoFilters(float brightness, float contrast, float saturation, float gamma, float hue) {
        databaseManager.saveVideoFilters(brightness, contrast, saturation, gamma, hue);
    }

    public float[] loadVideoFilters() {
        return databaseManager.loadVideoFilters();
    }

    public void saveSurroundSoundSetting(boolean enableSurroundSound) {
        databaseManager.saveSurroundSoundSetting(enableSurroundSound);
    }

    public boolean loadSurroundSoundSetting() {
        return databaseManager.loadSurroundSoundSetting();
    }

    public void saveSurround3DSettings(int mode, float spatialWidth, float dialogueBoost, float bassBoost, float trebleBoost) {
        databaseManager.saveSurround3DSettings(mode, spatialWidth, dialogueBoost, bassBoost, 1.0f, trebleBoost);
    }

    public void saveSurround3DSettings(int mode, float spatialWidth, float dialogueBoost, float bassBoost, float midBoost, float trebleBoost) {
        databaseManager.saveSurround3DSettings(mode, spatialWidth, dialogueBoost, bassBoost, midBoost, trebleBoost);
    }

    public int loadSurround3DMode() {
        return databaseManager.loadSurround3DMode();
    }

    public float loadSurroundSpatialWidth() {
        return databaseManager.loadSurroundSpatialWidth();
    }

    public float loadSurroundDialogueBoost() {
        return databaseManager.loadSurroundDialogueBoost();
    }

    public float loadSurroundBassBoost() {
        return databaseManager.loadSurroundBassBoost();
    }

    public float loadSurroundMidBoost() {
        return databaseManager.loadSurroundMidBoost();
    }

    public float loadSurroundTrebleBoost() {
        return databaseManager.loadSurroundTrebleBoost();
    }

    public void saveAutoPlaySetting(boolean autoPlay) {
        databaseManager.saveAutoPlaySetting(autoPlay);
    }

    public boolean loadAutoPlaySetting() {
        return databaseManager.loadAutoPlaySetting();
    }

    public void saveLongSkipDurationSetting(int duration) {
        databaseManager.saveLongSkipDurationSetting(duration);
    }

    public int loadLongSkipDurationSetting() {
        return databaseManager.loadLongSkipDurationSetting();
    }

    public void saveThemeSetting(int themeMode) {
        databaseManager.saveThemeSetting(themeMode);
    }

    public int loadThemeSetting() {
        return databaseManager.loadThemeSetting();
    }

    public void saveDisableCommentsInPlayerSetting(boolean disable) {
        databaseManager.saveDisableCommentsInPlayerSetting(disable);
    }

    public boolean loadDisableCommentsInPlayerSetting() {
        return databaseManager.loadDisableCommentsInPlayerSetting();
    }

    public void saveCommentRepliesLimitSetting(int limit) {
        databaseManager.saveCommentRepliesLimitSetting(limit);
    }

    public int loadCommentRepliesLimitSetting() {
        return databaseManager.loadCommentRepliesLimitSetting();
    }

    public void saveSubtitlesEnabledSetting(boolean enable) {
        databaseManager.saveSubtitlesEnabledSetting(enable);
    }

    public boolean loadSubtitlesEnabledSetting() {
        return databaseManager.loadSubtitlesEnabledSetting();
    }

    public void saveSubtitleFormatSetting(String format) {
        databaseManager.saveSubtitleFormatSetting(format);
    }

    public String loadSubtitleFormatSetting() {
        return databaseManager.loadSubtitleFormatSetting();
    }

    public void saveSubtitleStyleSettings(float textSize, int textColor, int bgColor, int edgeType, int edgeColor) {
        databaseManager.saveSubtitleStyleSettings(textSize, textColor, bgColor, edgeType, edgeColor);
    }

    public float loadSubtitleTextSizeSetting() {
        return databaseManager.loadSubtitleTextSizeSetting();
    }

    public int loadSubtitleTextColorSetting() {
        return databaseManager.loadSubtitleTextColorSetting();
    }

    public int loadSubtitleBackgroundColorSetting() {
        return databaseManager.loadSubtitleBackgroundColorSetting();
    }

    public int loadSubtitleEdgeTypeSetting() {
        return databaseManager.loadSubtitleEdgeTypeSetting();
    }

    public int loadSubtitleEdgeColorSetting() {
        return databaseManager.loadSubtitleEdgeColorSetting();
    }

    public void savePlayerPreferences(String player, Integer teamId) {
        databaseManager.savePlayerPreferences(player, teamId);
    }

    public void savePlayerPreferences(String player, Integer teamId, String preferredQuality) {
        databaseManager.savePlayerPreferences(player, teamId, preferredQuality);
    }

    public void savePreferredQuality(String preferredQuality) {
        databaseManager.savePreferredQuality(preferredQuality);
    }

    public PlayerPreferences loadPlayerPreferences() {
        return databaseManager.loadPlayerPreferences();
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public OkHttpClient getHttpClient() {
        return core.getHttpClient();
    }

    public OkHttpClient getOkHttpClient() {
        return core.getHttpClient();
    }

    public Gson getGson() {
        return core.getGson();
    }

    public void shutdown() {
        core.shutdown();
    }
}
