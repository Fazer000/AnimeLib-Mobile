package com.example.animelib.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.example.animelib.models.EpisodeResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Вью для отображения сегментов таймлайна (оппенинги, эндинги и т.д.)
 * со скругленными краями сегментов и прозрачными разделителями (гапами).
 */
public class TimebarSegmentsView extends View {

    private final List<EpisodeResponse.TimecodeData> timecodes = new ArrayList<>();
    private long durationMs = 0;
    private long playedMs = 0;
    private long bufferedMs = 0;

    private Paint playedPaint;
    private Paint bufferedPaint;
    private Paint unplayedPaint;
    private RectF rectF;

    public TimebarSegmentsView(Context context) {
        super(context);
        init();
    }

    public TimebarSegmentsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TimebarSegmentsView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        playedPaint.setColor(Color.parseColor("#A855F7")); // Акцентный фиолетовый

        bufferedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bufferedPaint.setColor(Color.parseColor("#80FFFFFF")); // Полупрозрачный белый

        unplayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        unplayedPaint.setColor(Color.parseColor("#4DFFFFFF")); // Темно-серый трек

        rectF = new RectF();
    }

    public void setProgress(long playedMs, long bufferedMs, long durationMs) {
        this.playedMs = Math.max(0, playedMs);
        this.bufferedMs = Math.max(0, bufferedMs);
        if (durationMs > 0) {
            this.durationMs = durationMs;
        }
        invalidate();
    }

    public void setTimecodes(List<EpisodeResponse.TimecodeData> timecodes, long durationMs) {
        this.timecodes.clear();
        if (timecodes != null) {
            this.timecodes.addAll(timecodes);
            Collections.sort(this.timecodes, (t1, t2) -> Integer.compare(t1.getFrom(), t2.getFrom()));
        }
        if (durationMs > 0) {
            this.durationMs = durationMs;
        }
        invalidate();
    }

    public void setDuration(long durationMs) {
        if (this.durationMs != durationMs) {
            this.durationMs = durationMs;
            invalidate();
        }
    }

    private static class Interval {
        long fromMs;
        long toMs;

        Interval(long fromMs, long toMs) {
            this.fromMs = fromMs;
            this.toMs = toMs;
        }
    }

    private List<Interval> buildIntervals() {
        List<Interval> intervals = new ArrayList<>();
        if (durationMs <= 0) return intervals;

        long currentPointer = 0;

        for (EpisodeResponse.TimecodeData tc : timecodes) {
            long from = Math.max(0, tc.getFrom() * 1000L);
            long to = Math.min(durationMs, tc.getTo() * 1000L);

            if (to <= from || from >= durationMs) continue;

            if (from > currentPointer) {
                intervals.add(new Interval(currentPointer, from));
            }
            intervals.add(new Interval(from, to));
            currentPointer = Math.max(currentPointer, to);
        }

        if (currentPointer < durationMs) {
            intervals.add(new Interval(currentPointer, durationMs));
        }

        return intervals;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (durationMs <= 0) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float defaultPadding = 8f * density;
        float paddingLeft = getPaddingLeft() > 0 ? getPaddingLeft() : defaultPadding;
        float paddingRight = getPaddingRight() > 0 ? getPaddingRight() : defaultPadding;
        float width = getWidth() - paddingLeft - paddingRight;
        float height = getHeight();

        if (width <= 0 || height <= 0) return;

        float barHeight = 4f * density; // Высота полоски таймбара 4dp
        float centerY = height / 2f;
        float top = centerY - barHeight / 2f;
        float bottom = centerY + barHeight / 2f;
        float cornerRadius = barHeight / 2f; // Скругление концов сегмента (полный pill)
        float gapWidth = 3f * density; // Прозрачный разделитель между сегментами

        List<Interval> intervals = buildIntervals();
        int count = intervals.size();

        for (int i = 0; i < count; i++) {
            Interval interval = intervals.get(i);
            if (interval.toMs <= interval.fromMs) continue;

            float rawStartX = paddingLeft + ((float) interval.fromMs / durationMs) * width;
            float rawEndX = paddingLeft + ((float) interval.toMs / durationMs) * width;

            // Накладываем отступы разделителей (гапов) между соседними сегментами
            float left = (i == 0) ? rawStartX : (rawStartX + gapWidth / 2f);
            float right = (i == count - 1) ? rawEndX : (rawEndX - gapWidth / 2f);

            if (right <= left) continue;

            rectF.set(left, top, right, bottom);

            // 1. Отрисовка не сыгранного фона сегмента со скруглениями
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, unplayedPaint);

            // 2. Отрисовка забуферизованной части
            if (bufferedMs > interval.fromMs) {
                float bufX = paddingLeft + ((float) Math.min(bufferedMs, durationMs) / durationMs) * width;
                float clipRight = Math.min(right, bufX);
                if (clipRight > left) {
                    canvas.save();
                    canvas.clipRect(left, top, clipRight, bottom);
                    canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, bufferedPaint);
                    canvas.restore();
                }
            }

            // 3. Отрисовка сыгранной части
            if (playedMs > interval.fromMs) {
                float playX = paddingLeft + ((float) Math.min(playedMs, durationMs) / durationMs) * width;
                float clipRight = Math.min(right, playX);
                if (clipRight > left) {
                    canvas.save();
                    canvas.clipRect(left, top, clipRight, bottom);
                    canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, playedPaint);
                    canvas.restore();
                }
            }
        }
    }
}
