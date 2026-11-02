package com.pymoney.ui.chart;

/** 图表配色，色盲友好且深浅交替 */
public class Palette {

    private static final int[] COLORS = {
            0xFF2E7DFF, 0xFFFF6B3D, 0xFF00A870, 0xFFB37F00,
            0xFF8E5BD9, 0xFFD6336C, 0xFF17A2B8, 0xFF6C757D,
            0xFF5CA8FF, 0xFFFFA06B, 0xFF4CC9A0, 0xFFE0B400,
            0xFFB69BE8, 0xFFE66891, 0xFF5BC2D0, 0xFF9FA5AB,
    };

    public static int color(int index) {
        return COLORS[Math.floorMod(index, COLORS.length)];
    }

    /** 把不透明度压淡，用于填充区域 */
    public static int alpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
