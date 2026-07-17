package com.artifex.mupdf.mini;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class ColorSpectrumView extends View {
    private Paint paint;
    private final int[] colors = {Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED};
    private OnColorSelectedListener listener;

    public interface OnColorSelectedListener {
        void onColorSelected(int color);
    }

    public ColorSpectrumView(Context context) {
        super(context);
        init();
    }

    public ColorSpectrumView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint = new Paint();
    }

    public void setOnColorSelectedListener(OnColorSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() > 0) {
            LinearGradient gradient = new LinearGradient(0, 0, getWidth(), 0, colors, null, Shader.TileMode.CLAMP);
            paint.setShader(gradient);
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
            float x = event.getX();
            float width = getWidth();
            if (width <= 0) return true;
            if (x < 0) x = 0;
            if (x > width) x = width;

            float ratio = x / width;
            int color = getColorAt(ratio);
            if (listener != null) {
                listener.onColorSelected(color);
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                performClick();
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    private int getColorAt(float ratio) {
        if (ratio <= 0) return colors[0];
        if (ratio >= 1) return colors[colors.length - 1];

        float position = ratio * (colors.length - 1);
        int index = (int) position;
        float fraction = position - index;

        int c1 = colors[index];
        int c2 = colors[index + 1];

        return Color.rgb(
                (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * fraction),
                (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * fraction),
                (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * fraction)
        );
    }
}
