package dev.s25.farmer;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Frozen-image selector. Its touches never pass through to the game. */
public final class PickerView extends LinearLayout {
    public interface Listener { void finished(); void cancelled(); }
    private final Frame frame;
    private final Stages.Stage stage;
    private final Store store;
    private final Listener listener;
    private final TextView heading;
    private final Board board;
    private int fieldIndex;
    private boolean done;
    private int[] rectangle;
    private final List<int[]> points = new ArrayList<>();
    private Runnable cleanup;
    public PickerView(Context context, Frame frame, Stages.Stage stage, Store store, Listener listener) {
        super(context); this.frame = frame; this.stage = stage; this.store = store; this.listener = listener;
        setOrientation(VERTICAL); setBackgroundColor(Color.rgb(17, 32, 28)); setPadding(dp(8), dp(20), dp(8), dp(18));
        heading = new TextView(context); heading.setTextColor(Color.WHITE); heading.setTextSize(13); addView(heading, new LayoutParams(-1, -2));
        board = new Board(context); addView(board, new LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(context);
        Button undo = new Button(context); undo.setText("Undo"); undo.setOnClickListener(v -> { if (pointMode() && !points.isEmpty()) points.remove(points.size() - 1); else rectangle = null; board.invalidate(); });
        Button cancel = new Button(context); cancel.setText("Cancel"); cancel.setOnClickListener(v -> { if (!done) { done = true; listener.cancelled(); } });
        Button save = new Button(context); save.setText("Save / next"); save.setOnClickListener(v -> save());
        actions.addView(undo, new LayoutParams(0, dp(48), 1)); actions.addView(cancel, new LayoutParams(0, dp(48), 1)); actions.addView(save, new LayoutParams(0, dp(48), 1));
        addView(actions); updateHeading();
    }
    public void setOnDetachCleanup(Runnable cleanup) { this.cleanup = cleanup; }
    @Override protected void onDetachedFromWindow() { if (cleanup != null) { cleanup.run(); cleanup = null; } super.onDetachedFromWindow(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private Stages.Field field() { return stage.fields.get(fieldIndex); }
    private boolean pointMode() { return field().kind.startsWith("point"); }
    private void updateHeading() { heading.setText(stage.name + " · " + (fieldIndex + 1) + "/" + stage.fields.size() + "\n" + field().instruction); }
    private void save() {
        if (done) return;
        try {
            store.selection(field(), rectangle, points, frame.bitmap);
            if (fieldIndex + 1 >= stage.fields.size()) { done = true; listener.finished(); return; }
            fieldIndex++;
            rectangle = null; points.clear(); board.start = null; updateHeading(); board.invalidate();
        } catch (Exception e) { Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private final class Board extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF destination = new RectF();
        private float scale = 1, left, top;
        private int[] start;
        Board(Context context) { super(context); setContentDescription("Game screenshot. Drag a rectangle or tap target positions."); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            scale = Math.min((float)getWidth() / frame.width(), (float)getHeight() / frame.height());
            left = (getWidth() - frame.width() * scale) / 2; top = (getHeight() - frame.height() * scale) / 2;
            paint.setStyle(Paint.Style.FILL);
            destination.set(left, top, left + frame.width() * scale, top + frame.height() * scale);
            c.drawBitmap(frame.bitmap, null, destination, paint);
            paint.setColor(Color.rgb(116, 255, 160)); paint.setStrokeWidth(dp(2)); paint.setStyle(Paint.Style.STROKE);
            if (rectangle != null) c.drawRect(left + rectangle[0] * scale, top + rectangle[1] * scale,
                left + (rectangle[0] + rectangle[2]) * scale, top + (rectangle[1] + rectangle[3]) * scale, paint);
            for (int i = 0; i < points.size(); i++) {
                int[] p = points.get(i); float x = left + p[0] * scale, y = top + p[1] * scale;
                c.drawCircle(x, y, dp(5), paint); paint.setTextSize(dp(14));
                c.drawText(Integer.toString(i + 1), x + dp(8), y, paint);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (done) return true;
            int x = Math.round((e.getX() - left) / scale), y = Math.round((e.getY() - top) / scale);
            if (e.getAction() == MotionEvent.ACTION_DOWN && (x < 0 || y < 0 || x >= frame.width() || y >= frame.height())) return false;
            x = Math.max(0, Math.min(frame.width() - 1, x)); y = Math.max(0, Math.min(frame.height() - 1, y));
            if (e.getAction() == MotionEvent.ACTION_DOWN) { start = new int[] {x, y}; if (!pointMode()) rectangle = null; }
            if (start != null && !pointMode()) rectangle = new int[] {Math.min(x, start[0]), Math.min(y, start[1]), Math.abs(x - start[0]), Math.abs(y - start[1])};
            if (e.getAction() == MotionEvent.ACTION_UP && start != null) {
                if (pointMode()) { if (field().kind.equals("point")) points.clear(); points.add(new int[] {x, y}); }
                start = null; performClick();
            }
            if (e.getAction() == MotionEvent.ACTION_CANCEL) start = null;
            invalidate(); return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
