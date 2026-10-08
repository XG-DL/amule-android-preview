package uk.xgdl.amuleprobe;

import android.content.Context;
import android.graphics.Color;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

final class NativeGraphView extends View {
    private final JSONObject response;
    private final int color;
    private final int muted;
    private final int border;
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    NativeGraphView(Context context, JSONObject response, int color, int muted, int border) {
        super(context);
        this.response = response;
        this.color = color;
        this.muted = muted;
        this.border = border;
    }

    @Override protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float left = 42 * density, right = getWidth() - 8 * density, top = 10 * density, bottom = getHeight() - 24 * density;
        JSONArray points = response.optJSONArray("points");
        if (points == null || points.length() == 0) {
            paint.setColor(muted); paint.setTextSize(12 * density); canvas.drawText("Waiting for graph samples", left, top + 20 * density, paint); return;
        }
        double max = 1;
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.optJSONObject(i);
            if (point != null) max = Math.max(max, point.optDouble("value"));
            if (point != null && response.optString("graph").equals("connections")) {
                max = Math.max(max, point.optDouble("active_download_count"));
                max = Math.max(max, point.optDouble("active_upload_count"));
            }
        }
        paint.setStrokeWidth(1 * density); paint.setColor(border);
        for (int row = 0; row < 4; row++) {
            float y = top + (bottom - top) * row / 3f;
            canvas.drawLine(left, y, right, y, paint);
        }
        paint.setColor(muted); paint.setTextSize(10 * density);
        canvas.drawText(graphAxis(max, response.optString("unit", "count")), 2 * density, top + 4 * density, paint);
        paint.setColor(color); paint.setStyle(android.graphics.Paint.Style.STROKE); paint.setStrokeWidth(2 * density);
        android.graphics.Path path = new android.graphics.Path();
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.optJSONObject(i); if (point == null) continue;
            float x = left + (right - left) * i / Math.max(1, points.length() - 1);
            float y = bottom - (float) (Math.max(0, point.optDouble("value")) / max) * (bottom - top);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, paint);
        if (response.optString("graph").equals("connections")) {
            drawGraphField(canvas, points, "active_download_count", Color.rgb(54, 175, 113), max, left, right, top, bottom, density);
            drawGraphField(canvas, points, "active_upload_count", Color.rgb(205, 112, 60), max, left, right, top, bottom, density);
        }
        paint.setStyle(android.graphics.Paint.Style.FILL);
        JSONObject first = points.optJSONObject(0), last = points.optJSONObject(points.length() - 1);
        String startLabel = first == null ? "" : graphTime(first.optLong("at"));
        String endLabel = last == null ? "" : graphTime(last.optLong("at"));
        canvas.drawText(startLabel, left, getHeight() - 4 * density, paint);
        canvas.drawText(endLabel, Math.max(left, right - paint.measureText(endLabel)), getHeight() - 4 * density, paint);
    }

    private void drawGraphField(android.graphics.Canvas canvas, JSONArray points, String field, int lineColor, double max,
                                float left, float right, float top, float bottom, float density) {
        paint.setColor(lineColor); paint.setStyle(android.graphics.Paint.Style.STROKE); paint.setStrokeWidth(2 * density);
        android.graphics.Path path = new android.graphics.Path(); boolean started = false;
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.optJSONObject(i); if (point == null || !point.has(field)) continue;
            float x = left + (right - left) * i / Math.max(1, points.length() - 1);
            float y = bottom - (float) (Math.max(0, point.optDouble(field)) / max) * (bottom - top);
            if (!started) { path.moveTo(x, y); started = true; } else path.lineTo(x, y);
        }
        if (started) canvas.drawPath(path, paint);
    }

    private static String graphAxis(double value, String unit) {
        return unit.equals("bytes_per_second") ? speed((long) value) : String.format(java.util.Locale.UK, "%.0f", value);
    }

    private static String graphTime(long epochSeconds) {
        if (epochSeconds <= 0) return "";
        return android.text.format.DateFormat.format("HH:mm", epochSeconds * 1000L).toString();
    }

    private static String speed(long value) {
        if (value < 1024) return value + " B/s";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double amount = value;
        int index = -1;
        do { amount /= 1024.0; index++; } while (amount >= 1024 && index < units.length - 1);
        return String.format(java.util.Locale.UK, "%.1f %s/s", amount, units[index]);
    }
}
