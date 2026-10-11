package com.example.animelib.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.example.animelib.R;

public class SliderLabelsView extends View {

    public interface OnLabelClickListener {
        void onLabelClicked(int value);
    }

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int currentValue = 3;
    private final String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "∞"};
    private OnLabelClickListener labelClickListener;

    public SliderLabelsView(@NonNull Context context) {
        this(context, null);
    }

    public SliderLabelsView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        Typeface tf = null;
        try {
            tf = ResourcesCompat.getFont(context, R.font.open_sans_bold);
        } catch (Exception ignored) {}

        float density = getResources().getDisplayMetrics().density;

        textPaint.setColor(ContextCompat.getColor(context, R.color.accent_text_color));
        textPaint.setTextSize(11f * density);
        textPaint.setTextAlign(Paint.Align.CENTER);
        if (tf != null) textPaint.setTypeface(tf);

        selectedTextPaint.setColor(ContextCompat.getColor(context, R.color.purple_primary));
        selectedTextPaint.setTextSize(12f * density);
        selectedTextPaint.setTextAlign(Paint.Align.CENTER);
        if (tf != null) selectedTextPaint.setTypeface(tf);
    }

    public void setOnLabelClickListener(OnLabelClickListener listener) {
        this.labelClickListener = listener;
    }

    public void setCurrentValue(int value) {
        this.currentValue = value;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        int height = (int) (22f * density);
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec), height);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float thumbWidth = 5.5f * density;
        float sidePadding = thumbWidth / 2f + 12f * density; // Exactly matches ExpressiveSlider!

        float startX = sidePadding;
        float endX = getWidth() - sidePadding;
        float availableWidth = Math.max(0f, endX - startX);

        int count = labels.length;
        float y = getHeight() - 4f * density;

        int selectedIndex;
        if (currentValue < 0 || currentValue >= 11) {
            selectedIndex = count - 1; // unlimited ∞
        } else {
            selectedIndex = Math.max(0, Math.min(count - 2, currentValue - 1));
        }

        for (int i = 0; i < count; i++) {
            float fraction = (float) i / (float) (count - 1);
            float x = startX + fraction * availableWidth;
            boolean isSelected = (i == selectedIndex);
            Paint paint = isSelected ? selectedTextPaint : textPaint;
            canvas.drawText(labels[i], x, y, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (labelClickListener != null && event.getAction() == MotionEvent.ACTION_UP) {
            float density = getResources().getDisplayMetrics().density;
            float thumbWidth = 5.5f * density;
            float sidePadding = thumbWidth / 2f + 12f * density;
            float startX = sidePadding;
            float endX = getWidth() - sidePadding;
            float availableWidth = Math.max(0f, endX - startX);

            if (availableWidth > 0) {
                float touchX = event.getX();
                float fraction = (touchX - startX) / availableWidth;
                int count = labels.length;
                int index = Math.round(fraction * (count - 1));
                if (index >= 0 && index < count) {
                    int val = (index == count - 1) ? -1 : (index + 1);
                    setCurrentValue(val);
                    labelClickListener.onLabelClicked(val);
                    return true;
                }
            }
        }
        return true;
    }
}
