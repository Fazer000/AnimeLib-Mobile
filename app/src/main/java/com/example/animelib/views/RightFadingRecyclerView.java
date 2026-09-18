package com.example.animelib.views;

import android.content.Context;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

public class RightFadingRecyclerView extends RecyclerView {

    public RightFadingRecyclerView(@NonNull Context context) {
        super(context);
    }

    public RightFadingRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public RightFadingRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected float getLeftFadingEdgeStrength() {
        return 0f;
    }
}
