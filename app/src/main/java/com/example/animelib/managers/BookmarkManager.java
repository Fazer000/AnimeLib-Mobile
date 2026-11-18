package com.example.animelib.managers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.animelib.api.ApiService;
import com.example.animelib.models.AnimeBookmarkResponse;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.models.EpisodesListResponse;
import com.example.animelib.util.CustomToast;

/**
 * Менеджер для управления закладками аниме
 */
public class BookmarkManager {
    private static final String TAG = "BookmarkManager";

    private Context context;
    private final Context appContext;
    private ApiService apiService;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public BookmarkManager(Context context, ApiService apiService) {
        this.context = context;
        this.appContext = context != null && context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        this.apiService = apiService;
    }

    /**
     * Добавляет закладку для текущего эпизода
     * @param mediaSlug Слаг медиа
     * @param currentPlayer Данные текущего плеера (может быть null — тогда используются базовые метаданные)
     * @param currentEpisode Текущий эпизод
     * @param currentPosition Текущая позиция воспроизведения в миллисекундах
     * @param callback Колбэк для обновления UI
     * @param showSuccessToast Показывать ли Toast при успехе (при ошибке Toast выводится всегда)
     */
    public void addBookmark(String mediaSlug, EpisodeResponse.PlayerData currentPlayer,
                           EpisodesListResponse.EpisodeItem currentEpisode, long currentPosition,
                           BookmarkAddCallback callback, boolean showSuccessToast) {
        addBookmark(mediaSlug, currentPlayer, currentEpisode, currentPosition, null, callback, showSuccessToast);
    }

    public void addBookmark(String mediaSlug, @Nullable EpisodeResponse.PlayerData currentPlayer,
                           EpisodesListResponse.EpisodeItem currentEpisode, long currentPosition,
                           Object statusId,
                           BookmarkAddCallback callback, boolean showSuccessToast) {

        if (currentEpisode == null || currentEpisode.getId() <= 0) {
            showErrorToast("Не удалось добавить закладку: эпизод не выбран");
            if (callback != null) {
                callback.onBookmarkError("Current episode is null");
            }
            return;
        }

        final Context effectiveCtx = this.appContext != null ? this.appContext : this.context;
        if (effectiveCtx == null) {
            if (callback != null) {
                callback.onBookmarkError("Context is not available");
            }
            return;
        }

        BookmarkSyncDispatcher dispatcher = BookmarkSyncDispatcher.getInstance(effectiveCtx);

        boolean authorized = (apiService != null && apiService.isAuthorized()) || dispatcher.isAuthorized();
        if (!authorized) {
            Log.w(TAG, "User is not authorized, cannot add bookmark");
            if (showSuccessToast) {
                showErrorToast("Без авторизации поставить закладку нельзя");
            }
            if (callback != null) {
                callback.onBookmarkError("Not authorized");
            }
            return;
        }

        if (mediaSlug == null || mediaSlug.trim().isEmpty()) {
            showErrorToast("Не удалось добавить закладку: не определено аниме");
            if (callback != null) {
                callback.onBookmarkError("Media slug is empty");
            }
            return;
        }

        final String cleanSlug = mediaSlug.trim();
        final String timecode = ApiService.formatTimecode(Math.max(0L, currentPosition));
        final int episodeId = currentEpisode.getId();

        int teamId = 0;
        int translationType = 2;
        String playerName = "Animelib";

        if (currentPlayer != null) {
            if (currentPlayer.getTeam() != null && currentPlayer.getTeam().getId() > 0) {
                teamId = currentPlayer.getTeam().getId();
            }
            if (currentPlayer.getTranslationType() != null && currentPlayer.getTranslationType().getId() > 0) {
                translationType = currentPlayer.getTranslationType().getId();
            }
            if (currentPlayer.getPlayer() != null && !currentPlayer.getPlayer().trim().isEmpty()) {
                playerName = currentPlayer.getPlayer().trim();
            }
        } else {
            // Если currentPlayer не успел инициализироваться, пробуем взять метаданные из кэша существующей закладки
            AnimeBookmarkResponse cached = bookmarkCache.get(cleanSlug);
            if (cached != null && cached.getData() != null && cached.getData().getMeta() != null) {
                AnimeBookmarkResponse.MetaData cachedMeta = cached.getData().getMeta();
                if (cachedMeta.getTeam() > 0) teamId = cachedMeta.getTeam();
                if (cachedMeta.getTranslationType() > 0) translationType = cachedMeta.getTranslationType();
                if (cachedMeta.getPlayer() != null && !cachedMeta.getPlayer().isEmpty()) {
                    playerName = cachedMeta.getPlayer();
                }
            }
        }

        Object effectiveStatusId = statusId;
        if (effectiveStatusId == null) {
            Integer cachedStatus = getCachedWatchStatus(cleanSlug);
            if (cachedStatus != null && cachedStatus > 0) {
                effectiveStatusId = cachedStatus;
            }
        }

        int episodeNumber = 1;
        if (currentEpisode.getNumber() != null) {
            try {
                episodeNumber = Integer.parseInt(currentEpisode.getNumber().trim());
            } catch (NumberFormatException e) {
                Log.w(TAG, "Non-integer episode number: " + currentEpisode.getNumber() + ", extracting digits");
                String digits = currentEpisode.getNumber().replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    try {
                        episodeNumber = Integer.parseInt(digits);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        Log.d(TAG, "Dispatching bookmark - mediaSlug: " + cleanSlug +
                   ", episodeId: " + episodeId +
                   ", teamId: " + teamId +
                   ", translationType: " + translationType +
                   ", player: " + playerName +
                   ", episodeNumber: " + episodeNumber +
                   ", timecode: " + timecode +
                   ", statusId: " + effectiveStatusId);

        BookmarkSyncDispatcher.BookmarkRequest request = new BookmarkSyncDispatcher.BookmarkRequest(
                cleanSlug,
                episodeId,
                teamId,
                translationType,
                playerName,
                episodeNumber,
                timecode,
                effectiveStatusId,
                showSuccessToast,
                new BookmarkAddCallback() {
                    @Override
                    public void onBookmarkAdded(int savedEpisodeId) {
                        invalidateCache(cleanSlug);
                        if (callback != null) {
                            callback.onBookmarkAdded(savedEpisodeId);
                        }
                    }

                    @Override
                    public void onBookmarkError(String error) {
                        if (callback != null) {
                            callback.onBookmarkError(error);
                        }
                    }
                }
        );

        dispatcher.enqueueBookmark(request);
    }

    private final java.util.Map<String, AnimeBookmarkResponse> bookmarkCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Nullable
    public Integer getCachedWatchStatus(@Nullable String mediaSlug) {
        if (mediaSlug == null || mediaSlug.trim().isEmpty()) return null;
        AnimeBookmarkResponse cached = bookmarkCache.get(mediaSlug.trim());
        if (cached != null && cached.getData() != null && cached.getData().getStatus() > 0) {
            return cached.getData().getStatus();
        }
        return null;
    }

    public void invalidateCache(String mediaSlug) {
        if (mediaSlug != null) {
            bookmarkCache.remove(mediaSlug);
            bookmarkCache.remove(mediaSlug.trim());
        }
        if (apiService != null) {
            apiService.invalidateAnimeBookmarkCache(mediaSlug);
        }
        Context effectiveCtx = this.appContext != null ? this.appContext : this.context;
        if (effectiveCtx != null) {
            BookmarkSyncDispatcher.getInstance(effectiveCtx).invalidateSavedSignature(mediaSlug);
        }
    }

    /**
     * Получает закладку аниме
     * @param mediaSlug Слаг медиа
     * @param callback Колбэк для результата
     */
    public void fetchAnimeBookmark(String mediaSlug, AnimeBookmarkCallback callback) {
        final String normalizedSlug = mediaSlug != null ? mediaSlug.trim() : null;
        if (normalizedSlug == null || normalizedSlug.isEmpty()) {
            if (callback != null) {
                callback.onError("Empty mediaSlug");
            }
            return;
        }

        if (bookmarkCache.containsKey(normalizedSlug)) {
            AnimeBookmarkResponse cached = bookmarkCache.get(normalizedSlug);
            if (cached != null) {
                Log.d(TAG, "Returning cached anime bookmark for: " + normalizedSlug);
                if (callback != null) {
                    callback.onBookmarkReceived(cached);
                }
                return;
            }
        }

        if (apiService == null || !apiService.isAuthorized()) {
            if (callback != null) {
                callback.onError("Not authorized or ApiService unavailable");
            }
            return;
        }

        Log.d(TAG, "Fetching anime bookmark for mediaSlug: " + normalizedSlug);
        apiService.fetchAnimeBookmark(normalizedSlug, new ApiService.AnimeBookmarkCallback() {
            @Override
            public void onBookmarkReceived(AnimeBookmarkResponse response) {
                if (response != null) {
                    bookmarkCache.put(normalizedSlug, response);
                }
                if (callback != null) {
                    callback.onBookmarkReceived(response);
                }
            }

            @Override
            public void onError(String error) {
                Log.d(TAG, "Bookmark fetch finished without active bookmark: " + error);
                if (callback != null) {
                    callback.onError(error);
                }
            }
        });
    }

    /**
     * Извлекает media_slug из URL аниме
     */
    public String extractMediaSlugFromUrl(String animeUrl) {
        return ApiService.extractMediaSlugFromUrl(animeUrl);
    }

    /**
     * Проверяет, указывает ли онлайн-закладка на конкретный эпизод (а не просто на статус тайтла).
     */
    public static boolean hasExplicitEpisodeReference(@Nullable AnimeBookmarkResponse.BookmarkData bookmark) {
        if (bookmark == null) return false;
        if (bookmark.getItemId() > 0) return true;
        if (bookmark.getItem() != null) {
            if (bookmark.getItem().getId() > 0) return true;
            String itemNum = bookmark.getItem().getNumber();
            if (itemNum != null && !itemNum.trim().isEmpty()) return true;
        }
        return parseTimecodeToMillisecondsStatic(bookmark.getProgress()) > 0L;
    }

    public static long parseTimecodeToMillisecondsStatic(@Nullable String timecode) {
        if (timecode == null || timecode.trim().isEmpty()) {
            return 0L;
        }
        try {
            String timeStr = timecode.trim();
            String[] parts = timeStr.split(":");
            if (parts.length == 2) {
                int minutes = Integer.parseInt(parts[0].trim());
                int seconds = Integer.parseInt(parts[1].trim());
                return Math.max(0L, (minutes * 60L + seconds) * 1000L);
            } else if (parts.length == 3) {
                int hours = Integer.parseInt(parts[0].trim());
                int minutes = Integer.parseInt(parts[1].trim());
                int seconds = Integer.parseInt(parts[2].trim());
                return Math.max(0L, (hours * 3600L + minutes * 60L + seconds) * 1000L);
            } else if (parts.length == 1) {
                double val = Double.parseDouble(parts[0].trim());
                if (val <= 0) return 0L;
                return val > 100000 ? (long) val : (long) (val * 1000.0);
            }
        } catch (NumberFormatException ignored) {
        }
        return 0L;
    }

    /**
     * Находит эпизод из списка, соответствующий онлайн-закладке.
     * Для закладок только со статусом (без item_id, item и progress) возвращает null,
     * чтобы воспроизведение начиналось с первого (0-го в JSON) эпизода.
     */
    @Nullable
    public static EpisodesListResponse.EpisodeItem resolveBookmarkedEpisode(
            @Nullable AnimeBookmarkResponse.BookmarkData bookmark,
            @Nullable java.util.List<EpisodesListResponse.EpisodeItem> episodesList) {
        if (bookmark == null || episodesList == null || episodesList.isEmpty()) {
            return null;
        }
        if (!hasExplicitEpisodeReference(bookmark)) {
            return null;
        }

        int bookmarkedEpisodeId = bookmark.getItemId();
        if (bookmarkedEpisodeId <= 0 && bookmark.getItem() != null) {
            bookmarkedEpisodeId = bookmark.getItem().getId();
        }

        if (bookmarkedEpisodeId > 0) {
            for (EpisodesListResponse.EpisodeItem episode : episodesList) {
                if (episode != null && episode.getId() == bookmarkedEpisodeId) {
                    return episode;
                }
            }
        }

        String targetNumber = null;
        if (bookmark.getItem() != null && bookmark.getItem().getNumber() != null
                && !bookmark.getItem().getNumber().trim().isEmpty()) {
            targetNumber = bookmark.getItem().getNumber().trim();
        } else if (bookmark.getMeta() != null && bookmark.getMeta().getItemNumber() > 0) {
            targetNumber = String.valueOf(bookmark.getMeta().getItemNumber());
        }

        if (targetNumber != null) {
            for (EpisodesListResponse.EpisodeItem episode : episodesList) {
                if (episode != null && episode.getNumber() != null
                        && targetNumber.equals(episode.getNumber().trim())) {
                    return episode;
                }
            }
        }

        return null;
    }

    /**
     * Находит эпизод из списка, соответствующий локальной закладке из БД.
     */
    @Nullable
    public static EpisodesListResponse.EpisodeItem resolveOfflineBookmarkedEpisode(
            @Nullable com.example.animelib.data.entity.OfflineBookmarkEntity offlineBookmark,
            @Nullable java.util.List<EpisodesListResponse.EpisodeItem> episodesList) {
        if (offlineBookmark == null || episodesList == null || episodesList.isEmpty()) {
            return null;
        }

        int epId = offlineBookmark.getEpisodeId();
        if (epId > 0) {
            for (EpisodesListResponse.EpisodeItem episode : episodesList) {
                if (episode != null && episode.getId() == epId) {
                    return episode;
                }
            }
        }

        String epNumber = offlineBookmark.getEpisodeNumber();
        if (epNumber != null && !epNumber.trim().isEmpty()) {
            String trimmedTarget = epNumber.trim();
            for (EpisodesListResponse.EpisodeItem episode : episodesList) {
                if (episode != null && episode.getNumber() != null
                        && trimmedTarget.equals(episode.getNumber().trim())) {
                    return episode;
                }
            }
        }

        return null;
    }

    /**
     * Проверяет, относится ли закладка к конкретному эпизоду.
     */
    public static boolean isBookmarkForEpisode(
            @Nullable AnimeBookmarkResponse.BookmarkData bookmarkData,
            @Nullable EpisodesListResponse.EpisodeItem episode) {
        if (bookmarkData == null || episode == null || !hasExplicitEpisodeReference(bookmarkData)) {
            return false;
        }
        int bookmarkEpisodeId = bookmarkData.getItemId();
        if (bookmarkEpisodeId <= 0 && bookmarkData.getItem() != null) {
            bookmarkEpisodeId = bookmarkData.getItem().getId();
        }
        if (bookmarkEpisodeId > 0) {
            return episode.getId() == bookmarkEpisodeId;
        }
        String episodeNumber = episode.getNumber() != null ? episode.getNumber().trim() : null;
        if (episodeNumber == null || episodeNumber.isEmpty()) {
            return false;
        }
        if (bookmarkData.getItem() != null && bookmarkData.getItem().getNumber() != null
                && !bookmarkData.getItem().getNumber().trim().isEmpty()) {
            return episodeNumber.equals(bookmarkData.getItem().getNumber().trim());
        }
        return bookmarkData.getMeta() != null
                && bookmarkData.getMeta().getItemNumber() > 0
                && episodeNumber.equals(String.valueOf(bookmarkData.getMeta().getItemNumber()));
    }

    /**
     * Проверяет, есть ли закладка для указанного эпизода
     */
    public boolean hasBookmarkForEpisode(AnimeBookmarkResponse.BookmarkData bookmarkData, int episodeId) {
        if (bookmarkData == null || episodeId <= 0) return false;
        int bookmarkEpId = bookmarkData.getItemId();
        if (bookmarkEpId <= 0 && bookmarkData.getItem() != null) {
            bookmarkEpId = bookmarkData.getItem().getId();
        }
        return bookmarkEpId > 0 && bookmarkEpId == episodeId;
    }

    /**
     * Получает прогресс закладки для указанного эпизода
     */
    public String getBookmarkProgress(AnimeBookmarkResponse.BookmarkData bookmarkData, int episodeId) {
        if (hasBookmarkForEpisode(bookmarkData, episodeId)) {
            return bookmarkData.getProgress();
        }
        return null;
    }

    private void showErrorToast(String message) {
        final Context ctx = this.context != null ? this.context : this.appContext;
        if (ctx == null) return;
        mainHandler.post(() -> CustomToast.showWarning(ctx, message));
    }

    /**
     * Получает последний эпизод из закладки для указанного аниме
     */
    public void getLastEpisodeFromBookmark(String mediaSlug, java.util.List<EpisodesListResponse.EpisodeItem> episodesList,
                                         LastEpisodeCallback callback) {
        if (episodesList == null || episodesList.isEmpty() || mediaSlug == null || mediaSlug.trim().isEmpty()
                || apiService == null || !apiService.isAuthorized()) {
            if (callback != null) {
                callback.onNoBookmarkFound();
            }
            return;
        }

        Log.d(TAG, "Getting last episode from bookmark for mediaSlug: " + mediaSlug);

        fetchAnimeBookmark(mediaSlug, new AnimeBookmarkCallback() {
            @Override
            public void onBookmarkReceived(AnimeBookmarkResponse response) {
                if (response != null && response.getData() != null) {
                    AnimeBookmarkResponse.BookmarkData bookmark = response.getData();
                    EpisodesListResponse.EpisodeItem foundEpisode = resolveBookmarkedEpisode(bookmark, episodesList);

                    if (foundEpisode != null) {
                        Log.d(TAG, "Found bookmarked episode: " + foundEpisode.getNumber() +
                                   ", progress: " + bookmark.getProgress());
                        callback.onLastEpisodeFound(foundEpisode, bookmark.getProgress());
                    } else {
                        Log.d(TAG, "Bookmarked episode not found in episodes list (or status-only bookmark)");
                        callback.onNoBookmarkFound();
                    }
                } else {
                    Log.d(TAG, "No bookmark data received");
                    callback.onNoBookmarkFound();
                }
            }

            @Override
            public void onError(String error) {
                Log.d(TAG, "No bookmark resolved: " + error);
                callback.onNoBookmarkFound();
            }
        });
    }

    /**
     * Интерфейс для получения закладки аниме
     */
    public interface AnimeBookmarkCallback {
        void onBookmarkReceived(AnimeBookmarkResponse response);
        void onError(String error);
    }

    /**
     * Парсит таймкод в формате "MM:SS" или "HH:MM:SS" в миллисекунды
     */
    public long parseTimecodeToMilliseconds(String timecode) {
        if (timecode == null || timecode.trim().isEmpty()) {
            return 0;
        }

        try {
            String timeStr = timecode.trim();
            String[] parts = timeStr.split(":");
            if (parts.length == 2) {
                int minutes = Integer.parseInt(parts[0].trim());
                int seconds = Integer.parseInt(parts[1].trim());
                return (minutes * 60L + seconds) * 1000L;
            } else if (parts.length == 3) {
                int hours = Integer.parseInt(parts[0].trim());
                int minutes = Integer.parseInt(parts[1].trim());
                int seconds = Integer.parseInt(parts[2].trim());
                return (hours * 3600L + minutes * 60L + seconds) * 1000L;
            } else if (parts.length == 1) {
                double val = Double.parseDouble(parts[0].trim());
                if (val > 100000) {
                    return (long) val;
                } else {
                    return (long) (val * 1000.0);
                }
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed to parse timecode: " + timecode, e);
        }

        return 0;
    }

    /**
     * Очистка ссылок на Activity (appContext сохраняется для фоновой доставки тостов/колбэков)
     */
    public void cleanup() {
        apiService = null;
        context = null;
    }

    /**
     * Интерфейс для получения последнего эпизода из закладки
     */
    public interface LastEpisodeCallback {
        void onLastEpisodeFound(EpisodesListResponse.EpisodeItem episode, String progress);
        void onNoBookmarkFound();
    }

    /**
     * Интерфейс для обновления UI после добавления закладки
     */
    public interface BookmarkAddCallback {
        void onBookmarkAdded(int episodeId);
        void onBookmarkError(String error);
    }
}
