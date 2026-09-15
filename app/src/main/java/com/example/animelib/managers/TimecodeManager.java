package com.example.animelib.managers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;

import com.example.animelib.R;
import com.example.animelib.models.EpisodeResponse;
import com.example.animelib.views.TimebarSegmentsView;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Менеджер для обработки таймкодов и сегментов видео.
 * Делит таймбар на визуальные сегменты и управляет кнопкой пропуска.
 */
public class TimecodeManager {
    private static final String TAG = "TimecodeManager";
    
    private final Context context;
    private ExoPlayer player;
    private PlayerView playerView;
    private TimebarSegmentsView timebarSegmentsView;
    private DefaultTimeBar timeBar;
    private TextView currentSegmentBadge;
    private List<EpisodeResponse.TimecodeData> timecodes = new ArrayList<>();
    private Player.Listener playerListener;
    private TimeBar.OnScrubListener scrubListener;
    private Handler updateHandler;
    private Runnable updateRunnable;
    private MaterialButton skipSegmentButton;
    private boolean isControllerVisible = false;
    private boolean isScrubbing = false;
    
    public TimecodeManager(Context context) {
        this.context = context;
        this.updateHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * Инициализация с UI компонентами
     */
    public void initializeViews(ExoPlayer player, PlayerView playerView, MaterialButton skipSegmentButton) {
        this.player = player;
        this.playerView = playerView;
        this.skipSegmentButton = skipSegmentButton;

        if (playerView != null) {
            View controllerView = playerView.findViewById(R.id.exo_controller);
            if (controllerView != null) {
                timebarSegmentsView = controllerView.findViewById(R.id.timebarSegmentsView);
                timeBar = controllerView.findViewById(R.id.exo_progress);
                currentSegmentBadge = controllerView.findViewById(R.id.currentSegmentBadge);
            }
        }
        
        // Настраиваем обработчик нажатия кнопки пропуска
        if (skipSegmentButton != null) {
            skipSegmentButton.setOnClickListener(v -> {
                if (player != null) {
                    long currentPositionMs = player.getCurrentPosition();
                    int currentPositionSeconds = (int) (currentPositionMs / 1000);
                    
                    // Ищем активный сегмент
                    for (EpisodeResponse.TimecodeData timecode : timecodes) {
                        if (currentPositionSeconds >= timecode.getFrom() && 
                            currentPositionSeconds <= timecode.getTo()) {
                            long seekPosition = timecode.getTo() * 1000L; // Конвертируем секунды в миллисекунды
                            player.seekTo(seekPosition);
                            if (timebarSegmentsView != null) {
                                timebarSegmentsView.setProgress(seekPosition, player.getBufferedPosition(), player.getDuration());
                            }
                            Log.d(TAG, "Skipping segment: " + timecode.getType() + " from " + timecode.getFrom() + "s to " + timecode.getTo() + "s at " + seekPosition + "ms");
                            break;
                        }
                    }
                }
            });
        }

        if (timeBar != null) {
            scrubListener = new TimeBar.OnScrubListener() {
                @Override
                public void onScrubStart(@NonNull TimeBar timeBar, long position) {
                    isScrubbing = true;
                    if (timebarSegmentsView != null && player != null) {
                        timebarSegmentsView.setProgress(position, player.getBufferedPosition(), player.getDuration());
                    }
                    updateSegmentBadgeForPosition(position);
                }

                @Override
                public void onScrubMove(@NonNull TimeBar timeBar, long position) {
                    isScrubbing = true;
                    if (timebarSegmentsView != null && player != null) {
                        timebarSegmentsView.setProgress(position, player.getBufferedPosition(), player.getDuration());
                    }
                    updateSegmentBadgeForPosition(position);
                }

                @Override
                public void onScrubStop(@NonNull TimeBar timeBar, long position, boolean canceled) {
                    isScrubbing = false;
                    if (timebarSegmentsView != null && player != null) {
                        timebarSegmentsView.setProgress(position, player.getBufferedPosition(), player.getDuration());
                    }
                    updateTimecodeButtonsVisibility();
                }
            };
            timeBar.addListener(scrubListener);
        }
        
        // Добавляем слушатель для отслеживания позиции воспроизведения
        if (player != null) {
            playerListener = new Player.Listener() {
                @Override
                public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                    updateProgress();
                    updateTimecodeButtonsVisibility();
                }
                
                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    updateProgress();
                    updateTimecodeButtonsVisibility();
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    updateProgress();
                    if (state == Player.STATE_READY) {
                        updateTimecodeSegmentsOnBar();
                    }
                }

                @Override
                public void onEvents(Player player, Player.Events events) {
                    updateProgress();
                }
            };
            player.addListener(playerListener);
        }

        updateTimecodeSegmentsOnBar();
        startPeriodicUpdate();
    }
    
    public void updateProgress() {
        if (player != null && timebarSegmentsView != null && !isScrubbing) {
            timebarSegmentsView.setProgress(player.getCurrentPosition(), player.getBufferedPosition(), player.getDuration());
        }
    }
    
    /**
     * Устанавливает таймкоды из PlayerData
     */
    public void setTimecodes(EpisodeResponse.PlayerData playerData) {
        if (playerData != null && playerData.getTimecode() != null) {
            this.timecodes = playerData.getTimecode();
            Log.d(TAG, "Timecodes set: " + timecodes.size() + " items");
            updateTimecodeButtons();
        } else {
            this.timecodes.clear();
            hideTimecodeButtons();
            Log.d(TAG, "No timecodes found");
        }
        updateTimecodeSegmentsOnBar();
    }

    /**
     * Обновляет отображение сегментов на самом таймбаре и устанавливает маркера
     */
    public void updateTimecodeSegmentsOnBar() {
        if (timebarSegmentsView != null) {
            long duration = (player != null && player.getDuration() > 0) ? player.getDuration() : 0;
            timebarSegmentsView.setTimecodes(timecodes, duration);
            if (player != null && duration > 0) {
                timebarSegmentsView.setProgress(player.getCurrentPosition(), player.getBufferedPosition(), duration);
            }
        }

        if (timeBar != null) {
            timeBar.setAdGroupTimesMs(null, null, 0);
        }
    }
    
    /**
     * Обновляет видимость кнопки пропуска сегмента и бейджа текущего сегмента
     */
    private void updateTimecodeButtonsVisibility() {
        if (player == null || timecodes.isEmpty()) {
            if (currentSegmentBadge != null) currentSegmentBadge.setVisibility(View.GONE);
            if (skipSegmentButton != null) skipSegmentButton.setVisibility(View.GONE);
            return;
        }

        if (isScrubbing) return;
        
        long currentPositionMs = player.getCurrentPosition();
        int currentPositionSeconds = (int) (currentPositionMs / 1000);
        
        // Ищем активный сегмент
        EpisodeResponse.TimecodeData activeSegment = null;
        for (EpisodeResponse.TimecodeData timecode : timecodes) {
            if (currentPositionSeconds >= timecode.getFrom() && 
                currentPositionSeconds <= timecode.getTo()) {
                activeSegment = timecode;
                break;
            }
        }
        
        if (activeSegment != null) {
            // Обновляем бейдж текущего сегмента
            if (currentSegmentBadge != null) {
                currentSegmentBadge.setText("• " + getSegmentDisplayName(activeSegment.getType()));
                if (isControllerVisible) {
                    currentSegmentBadge.setVisibility(View.VISIBLE);
                } else {
                    currentSegmentBadge.setVisibility(View.GONE);
                }
            }

            if (skipSegmentButton != null) {
                String buttonText = getSkipButtonText(activeSegment);
                skipSegmentButton.setText(buttonText);
                
                if (isControllerVisible) {
                    if (skipSegmentButton.getVisibility() != View.VISIBLE) {
                        skipSegmentButton.setVisibility(View.VISIBLE);
                        skipSegmentButton.setAlpha(0f);
                        skipSegmentButton.animate().alpha(1f).setDuration(200).start();
                    } else if (skipSegmentButton.getAlpha() < 1f) {
                        skipSegmentButton.setAlpha(1f);
                    }
                } else {
                    if (skipSegmentButton.getVisibility() == View.VISIBLE) {
                        skipSegmentButton.animate().alpha(0f).setDuration(150)
                                .withEndAction(() -> skipSegmentButton.setVisibility(View.GONE))
                                .start();
                    } else {
                        skipSegmentButton.setVisibility(View.GONE);
                    }
                }
            }
        } else {
            if (skipSegmentButton != null) {
                skipSegmentButton.setVisibility(View.GONE);
            }
            if (currentSegmentBadge != null) {
                currentSegmentBadge.setVisibility(View.GONE);
            }
        }
    }

    private void updateSegmentBadgeForPosition(long positionMs) {
        if (currentSegmentBadge == null || timecodes.isEmpty()) return;

        int posSeconds = (int) (positionMs / 1000);
        EpisodeResponse.TimecodeData targetSegment = null;
        for (EpisodeResponse.TimecodeData tc : timecodes) {
            if (posSeconds >= tc.getFrom() && posSeconds <= tc.getTo()) {
                targetSegment = tc;
                break;
            }
        }

        if (targetSegment != null) {
            currentSegmentBadge.setText("• " + getSegmentDisplayName(targetSegment.getType()) + " (" + formatTime(targetSegment.getFrom()) + " - " + formatTime(targetSegment.getTo()) + ")");
            currentSegmentBadge.setVisibility(View.VISIBLE);
        } else {
            currentSegmentBadge.setVisibility(View.GONE);
        }
    }
    
    /**
     * Устанавливает видимость контроллера (вызывается извне)
     */
    public void setControllerVisibility(boolean visible) {
        this.isControllerVisible = visible;
        updateTimecodeButtonsVisibility();
    }
    
    /**
     * Запускает периодическое обновление видимости кнопок
     */
    private void startPeriodicUpdate() {
        stopPeriodicUpdate(); // Останавливаем предыдущий, если есть
        
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateProgress();
                updateTimecodeButtonsVisibility();
                if (updateHandler != null) {
                    boolean playing = player != null && player.isPlaying();
                    updateHandler.postDelayed(this, playing ? 100 : 200);
                }
            }
        };
        updateHandler.post(updateRunnable);
    }
    
    /**
     * Останавливает периодическое обновление
     */
    private void stopPeriodicUpdate() {
        if (updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
            updateRunnable = null;
        }
    }
    
    /**
     * Обновляет кнопки таймкодов
     */
    private void updateTimecodeButtons() {
        if (timecodes.isEmpty()) {
            if (skipSegmentButton != null) {
                skipSegmentButton.setVisibility(View.GONE);
            }
            return;
        }
        
        // Кнопка уже создана в разметке, просто скрываем её
        if (skipSegmentButton != null) {
            skipSegmentButton.setVisibility(View.GONE);
        }
        Log.d(TAG, "Skip segment button ready");
    }
    
    private String getSegmentDisplayName(String type) {
        if (type == null) return "Сегмент";
        switch (type.toLowerCase()) {
            case "opening":
                return "Оппенинг";
            case "ending":
                return "Эндинг";
            case "splashscreen":
                return "Заставка";
            case "compilation":
                return "Компиляция";
            default:
                return type;
        }
    }

    /**
     * Возвращает текст для кнопки пропуска сегмента
     */
    private String getSkipButtonText(EpisodeResponse.TimecodeData timecode) {
        String type = timecode.getType();
        
        switch (type.toLowerCase()) {
            case "opening":
                return "Пропустить оппенинг";
            case "ending":
                return "Пропустить эндинг";
            case "splashscreen":
                return "Пропустить заставку";
            case "compilation":
                return "Пропустить компиляцию";
            default:
                return "Пропустить " + type;
        }
    }
    
    /**
     * Форматирует время в формат MM:SS
     */
    private String formatTime(int seconds) {
        int minutes = seconds / 60;
        int remainingSeconds = seconds % 60;
        return String.format("%d:%02d", minutes, remainingSeconds);
    }
    
    /**
     * Скрывает кнопки таймкодов
     */
    public void hideTimecodeButtons() {
        if (skipSegmentButton != null) {
            skipSegmentButton.setVisibility(View.GONE);
        }
    }
    
    /**
     * Показывает кнопки таймкодов
     */
    public void showTimecodeButtons() {
        if (skipSegmentButton != null && !timecodes.isEmpty()) {
            skipSegmentButton.setVisibility(View.VISIBLE);
        }
    }
    
    /**
     * Проверяет, есть ли таймкоды
     */
    public boolean hasTimecodes() {
        return !timecodes.isEmpty();
    }
    
    /**
     * Возвращает список таймкодов
     */
    public List<EpisodeResponse.TimecodeData> getTimecodes() {
        return timecodes;
    }
    
    /**
     * Очистка ресурсов
     */
    public void cleanup() {
        stopPeriodicUpdate();
        
        if (player != null && playerListener != null) {
            player.removeListener(playerListener);
        }
        
        if (skipSegmentButton != null) {
            skipSegmentButton.setVisibility(View.GONE);
        }
        
        timecodes.clear();
        player = null;
        playerListener = null;
        updateHandler = null;
        updateRunnable = null;
        skipSegmentButton = null;
        
        Log.d(TAG, "TimecodeManager cleaned up");
    }
}
