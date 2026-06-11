package com.tradeanalyst.app;

import android.graphics.Color;
import android.util.Log;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * COORDINATOR CONTROLLER: ChartPatternManager
 * Handles JSON deserialization, coordinate mapping math, and dynamic redraw schedules.
 */
public class ChartPatternManager {

    private static final String TAG = "ChartPatternManager";
    
    // Cache the last raw parsed response to allow real-time coordinate recalculations on scroll/zoom
    private static ChartPatternResponse sLastActiveResponse = null;

    /**
     * Decodes incoming JSON payload from WebView, maps structural indices, and triggers view refresh.
     *
     * @param activity Ref to parent MainActivity.
     * @param chartView Target custom CandlestickChartView.
     * @param jsonPayload The raw string payload containing structural JSON coordinates.
     */
    public static void processPatternsJson(MainActivity activity, CandlestickChartView chartView, String jsonPayload) {
        if (activity == null || chartView == null || jsonPayload == null) {
            Log.e(TAG, "Invalid context or empty payload received in processPatternsJson.");
            return;
        }

        try {
            // Step 1: Parse the raw payload with defensive formatting sanitization
            ChartPatternResponse response = PatternJsonParser.parse(jsonPayload);
            if (response == null || response.getPatterns() == null) {
                Log.w(TAG, "Parsing complete but parsed response or pattern list was null.");
                return;
            }

            sLastActiveResponse = response;
            activity.runOnUiThread(() -> {
                chartView.setActivePatternResponse(response);
            });

            // Step 2: Convert relative indices to physical coordinates
            recalculateAndRender(activity, chartView);

            // Step 3: Optional user notification of identified patterns
            if (response.getSummary() != null && response.getSummary().getBestPattern() != null) {
                String bestPattern = response.getSummary().getBestPattern();
                double rawConfidence = response.getSummary().getConfidence();
                
                // Dynamically apply AI confidence adjustment and clamp bounds to [0.0, 100.0]
                double adjustment = response.getAiConfidenceAdjustment();
                double adjustedConfidence = Math.max(0.0, Math.min(100.0, rawConfidence + adjustment));
                
                String recommendation = response.getSummary().getRecommendation();
                
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, 
                        String.format("AI DETECTED: %s (%.0f%% Confidence) -> %s", 
                            bestPattern.toUpperCase(), adjustedConfidence, recommendation.toUpperCase()), 
                        Toast.LENGTH_LONG).show();
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Failure coordinating pattern parsing and rendering: " + e.getMessage(), e);
        }
    }

    /**
     * Re-evaluates coordinate mathematics relative to current zoom/pan state of the chart.
     * Must be called on scroll, pinch-zoom, or data refresh to prevent drawing drift.
     *
     * @param activity Ref to MainActivity.
     * @param chartView Target CandlestickChartView.
     */
    public static void recalculateAndRender(MainActivity activity, CandlestickChartView chartView) {
        if (activity == null || chartView == null || sLastActiveResponse == null) {
            return;
        }

        List<Candlestick> candles = chartView.getCandles();
        if (candles == null || candles.isEmpty()) {
            return;
        }

        // Get viewport parameters
        float candleWidth = chartView.getCandleWidth();
        float offsetX = chartView.getOffsetX();
        double minPrice = chartView.getViewportMinPrice();
        double maxPrice = chartView.getViewportMaxPrice();
        int canvasHeight = chartView.getHeight();

        // Get the historical lookback count used during the API extraction
        int lastLookback = sLastActiveResponse.getLookback();
        if (lastLookback <= 0) {
            lastLookback = 10;
        }

        int absoluteStartIndex = Math.max(0, candles.size() - lastLookback);
        List<PatternDrawingModel> translatedPatterns = new ArrayList<>();

        for (ChartPattern pattern : sLastActiveResponse.getPatterns()) {
            List<PatternDrawingModel.CanvasPoint> translatedPoints = new ArrayList<>();
            
            for (ChartPattern.Point pt : pattern.getPoints()) {
                // Resolved index based on unique timestamp (Temporal Stabilization)
                int resolvedIndex = getIndexByTimestamp(candles, pt.getTimestamp(), pt.getIndex(), absoluteStartIndex);
                float physicalX = (resolvedIndex * candleWidth) + (candleWidth / 2f) - offsetX;

                // Y-Axis mapping: Physical Y = H - (R * H)
                double relativePrice = (pt.getPrice() - minPrice) / (maxPrice - minPrice);
                float physicalY = (float) (canvasHeight - (relativePrice * canvasHeight));

                translatedPoints.add(new PatternDrawingModel.CanvasPoint(physicalX, physicalY, pt.getTimestamp()));
            }

            // Map target level line Y coordinate
            float physicalTargetY = -1;
            if (pattern.getTarget() > 0) {
                double relativeTargetPrice = (pattern.getTarget() - minPrice) / (maxPrice - minPrice);
                physicalTargetY = (float) (canvasHeight - (relativeTargetPrice * canvasHeight));
            }

            // Map stop-loss level line Y coordinate
            float physicalStopLossY = -1;
            if (pattern.getStopLoss() > 0) {
                double relativeStopLossPrice = (pattern.getStopLoss() - minPrice) / (maxPrice - minPrice);
                physicalStopLossY = (float) (canvasHeight - (relativeStopLossPrice * canvasHeight));
            }

            // Map exact Neckline Points if defined mathematically (Phase 6)
            List<PatternDrawingModel.CanvasPoint> translatedNeckline = new ArrayList<>();
            if (pattern.getNecklinePoints() != null) {
                for (ChartPattern.Point pt : pattern.getNecklinePoints()) {
                    int resolvedIndex = getIndexByTimestamp(candles, pt.getTimestamp(), pt.getIndex(), absoluteStartIndex);
                    float physicalX = (resolvedIndex * candleWidth) + (candleWidth / 2f) - offsetX;
                    double relativePrice = (pt.getPrice() - minPrice) / (maxPrice - minPrice);
                    float physicalY = (float) (canvasHeight - (relativePrice * canvasHeight));
                    translatedNeckline.add(new PatternDrawingModel.CanvasPoint(physicalX, physicalY, pt.getTimestamp()));
                }
            }

            // Map Breakout Point if confirmed
            float breakoutX = -1;
            float breakoutY = -1;
            int resolvedBreakoutIndex = -1;
            if (pattern.getBreakoutTimestamp() > 0) {
                resolvedBreakoutIndex = getIndexByTimestamp(candles, pattern.getBreakoutTimestamp(), pattern.getBreakoutIndex(), absoluteStartIndex);
            } else if (pattern.getBreakoutIndex() >= 0) {
                resolvedBreakoutIndex = absoluteStartIndex + pattern.getBreakoutIndex();
            }

            if (resolvedBreakoutIndex >= 0 && resolvedBreakoutIndex < candles.size()) {
                breakoutX = (resolvedBreakoutIndex * candleWidth) + (candleWidth / 2f) - offsetX;
                double relativePrice = (pattern.getBreakoutPrice() - minPrice) / (maxPrice - minPrice);
                breakoutY = (float) (canvasHeight - (relativePrice * canvasHeight));
            }

            // Map Retest Zone bounds
            float retestZoneTopY = -1;
            float retestZoneBottomY = -1;
            if (pattern.getRetestZoneTop() > 0 && pattern.getRetestZoneBottom() > 0) {
                double relativeTopPrice = (pattern.getRetestZoneTop() - minPrice) / (maxPrice - minPrice);
                retestZoneTopY = (float) (canvasHeight - (relativeTopPrice * canvasHeight));

                double relativeBottomPrice = (pattern.getRetestZoneBottom() - minPrice) / (maxPrice - minPrice);
                retestZoneBottomY = (float) (canvasHeight - (relativeBottomPrice * canvasHeight));
            }

            // Map Projection Area bounds
            float projectionStartX = -1;
            float projectionEndX = -1;
            float projectionTargetY = -1;
            if (pattern.getProjectionStartIndex() >= 0 && pattern.getProjectionEndIndex() >= 0) {
                int startAbsolute = absoluteStartIndex + pattern.getProjectionStartIndex();
                int endAbsolute = absoluteStartIndex + pattern.getProjectionEndIndex();
                projectionStartX = (startAbsolute * candleWidth) + (candleWidth / 2f) - offsetX;
                projectionEndX = (endAbsolute * candleWidth) + (candleWidth / 2f) - offsetX;

                double relativePrice = (pattern.getProjectionTargetPrice() - minPrice) / (maxPrice - minPrice);
                projectionTargetY = (float) (canvasHeight - (relativePrice * canvasHeight));
            }

            // Dynamically apply AI confidence adjustment and clamp bounds to [0.0, 100.0]
            double baseConfidence = pattern.getConfidence();
            double adjustment = sLastActiveResponse.getAiConfidenceAdjustment();
            double adjustedConfidence = Math.max(0.0, Math.min(100.0, baseConfidence + adjustment));

            PatternDrawingModel dynamicModel = new PatternDrawingModel(
                pattern.getType(),
                adjustedConfidence,
                pattern.getBias(),
                pattern.getStartIndex(),
                pattern.getEndIndex(),
                translatedPoints,
                physicalTargetY,
                physicalStopLossY,
                pattern.getTarget(),
                pattern.getStopLoss(),
                pattern.getExplanation()
            );

            // Set state and geometric dimensions onto drawing model
            dynamicModel.setState(pattern.getState() != null ? pattern.getState() : "STATE_FORMING");
            dynamicModel.setNecklinePoints(translatedNeckline);
            dynamicModel.setBreakoutCoordinates(breakoutX, breakoutY);
            dynamicModel.setRetestZoneCoordinates(retestZoneTopY, retestZoneBottomY);
            dynamicModel.setProjectionCoordinates(projectionStartX, projectionEndX, projectionTargetY);

            translatedPatterns.add(dynamicModel);
        }

        // Apply translated models directly to the view cache and invalidate
        activity.runOnUiThread(() -> {
            chartView.setPatterns(translatedPatterns);
        });
    }

    /**
     * Helper to lookup exact candlestick index by unique timestamp (Temporal Stabilization)
     */
    private static int getIndexByTimestamp(List<Candlestick> candles, long timestamp, int fallbackIndex, int absoluteStartIndex) {
        if (timestamp > 0) {
            for (int i = 0; i < candles.size(); i++) {
                if (candles.get(i).timestamp == timestamp) {
                    return i;
                }
            }
        }
        return absoluteStartIndex + fallbackIndex; // Fallback to original relative calculation
    }

    /**
     * Clear active cached drawing parameters from view and manager context.
     *
     * @param chartView Target custom chart view.
     */
    public static void clearActivePatterns(CandlestickChartView chartView) {
        sLastActiveResponse = null;
        if (chartView != null) {
            chartView.setPatterns(null);
            chartView.setActivePatternResponse(null);
        }
    }
}