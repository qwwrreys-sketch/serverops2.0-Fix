package com.serverops.fabric.sidemenu.client;

import com.serverops.fabric.client.gui.Theme;
import com.serverops.fabric.sidemenu.MenuConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Time & date bar at the TOP-CENTER of the screen:
 * <pre>   March 2007     11:18 PM     After School   </pre>
 * Left to right: RP month + year, 12-hour clock with AM/PM, and the phase of the day in gold bold.
 * It sits on a dark, semi-transparent bar whose ends fade out (like the reference picture).
 *
 * <p>Everything is read from the client's own world clock (the server syncs it), so no packet is needed and it
 * always shows the same time as the sky. Calendar settings live in {@link MenuConfig}; the day phases in
 * {@link #PHASES} below.
 */
public final class TimeHud {
    private TimeHud() {}

    public static final int Y = 4;
    public static final int HEIGHT = 18;
    private static final int PADDING = 20;   // space left/right of the text inside the bar
    private static final int GAP = 16;       // space between the three parts
    private static final int FADE = 14;      // width of the faded ends
    private static final int BAR_COLOR = 0x0B0B0D;
    private static final int BAR_ALPHA = 0xB8;

    /**
     * Optional serif font like the reference picture. Vanilla Minecraft has no serif font, so by default the
     * normal font is used. To get a serif look: put a .ttf in assets/serverops_menu/font/, add
     * assets/serverops_menu/font/serif.json with {"providers":[{"type":"ttf","file":"serverops_menu:your.ttf",
     * "shift":[0,0],"size":11.0,"oversample":4.0}]} and set this to new ResourceLocation("serverops_menu", "serif").
     */
    private static final ResourceLocation SERIF = null;

    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December"};

    /** Day phases: {first hour (0-23), label}. Sorted by hour; the LAST entry also covers the hours before the first. */
    private static final Object[][] PHASES = {
            {5, "Morning"},        // 05:00 - 11:59
            {12, "Daytime"},       // 12:00 - 14:59
            {15, "After School"},  // 15:00 - 17:59
            {18, "Evening"},       // 18:00 - 20:59
            {21, "Night"},         // 21:00 - 04:59
    };

    // ---------------------------------------------------------------- the data

    /** Everything the bar shows, derived from Minecraft's day time. */
    private record Info(String month, String clock, String ampm, String phase) {}

    private static Info info(long dayTime) {
        // Minecraft: dayTime 0 = 06:00, 6000 = noon, 12000 = 18:00, 18000 = midnight.
        long t = Math.floorMod(dayTime, 24000L);
        int hour = (int) ((t / 1000 + 6) % 24);
        int minute = (int) ((t % 1000) * 60 / 1000);

        // The calendar day changes at MIDNIGHT (dayTime 18000), not at 06:00.
        long dayIndex = Math.floorDiv(dayTime + 6000L, 24000L);
        long monthIndex = Math.floorDiv(dayIndex, (long) MenuConfig.DAYS_PER_MONTH);
        if (MenuConfig.CALENDAR_LOOPS) monthIndex = Math.floorMod(monthIndex, 12L);
        long absMonth = (MenuConfig.CALENDAR_START_MONTH - 1) + monthIndex;
        int month = (int) Math.floorMod(absMonth, 12L);
        long year = MenuConfig.CALENDAR_START_YEAR + Math.floorDiv(absMonth, 12L);

        int h12 = hour % 12 == 0 ? 12 : hour % 12;
        String phase = (String) PHASES[PHASES.length - 1][1];
        for (Object[] p : PHASES) if (hour >= (Integer) p[0]) phase = (String) p[1];

        return new Info(MONTHS[month] + " " + year, h12 + ":" + (minute < 10 ? "0" : "") + minute,
                hour < 12 ? "AM" : "PM", phase);
    }

    // ---------------------------------------------------------------- layout (also used by other overlays)

    private static boolean shown(Minecraft mc) {
        return MenuConfig.SHOW_TIME_HUD && mc.screen == null && mc.level != null && mc.player != null
                && !mc.options.hideGui;
    }

    private static Component text(String s, boolean bold) {
        var c = Component.literal(s);
        if (SERIF != null) c = c.withStyle(st -> st.withFont(SERIF));
        return bold ? c.withStyle(ChatFormatting.BOLD) : c;
    }

    private static int barWidth(Info i) {
        var f = Theme.font();
        int time = f.width(i.clock()) + 3 + (int) (f.width(i.ampm()) * 0.8f);
        return f.width(text(i.month(), false)) + GAP + time + GAP + f.width(text(i.phase(), true)) + 2 * PADDING;
    }

    /**
     * If the bar is on screen AND reaches into the area right of {@code fromX}, returns the y below which other
     * overlays must start; otherwise 0. The notification cards use this to move down instead of overlapping.
     */
    public static int bottomIfReaching(Minecraft mc, int fromX) {
        if (!shown(mc)) return 0;
        int w = barWidth(info(mc.level.getDayTime()));
        int right = (mc.getWindow().getGuiScaledWidth() + w) / 2;
        return right > fromX ? Y + HEIGHT : 0;
    }

    /** Is the bar currently drawn? (the Freecam banner sits below it when it is) */
    public static boolean isShown(Minecraft mc) {
        return shown(mc);
    }

    // ---------------------------------------------------------------- drawing

    public static void render(GuiGraphics g, Minecraft mc) {
        if (!shown(mc)) return;
        Info i = info(mc.level.getDayTime());
        var f = Theme.font();

        int w = barWidth(i);
        int x = (mc.getWindow().getGuiScaledWidth() - w) / 2;

        // dark translucent bar with faded ends
        int solid = (BAR_ALPHA << 24) | BAR_COLOR;
        g.fill(x + FADE, Y, x + w - FADE, Y + HEIGHT, solid);
        for (int k = 0; k < FADE; k++) {
            int a = BAR_ALPHA * (k + 1) / (FADE + 1);
            int c = (a << 24) | BAR_COLOR;
            g.fill(x + k, Y, x + k + 1, Y + HEIGHT, c);
            g.fill(x + w - 1 - k, Y, x + w - k, Y + HEIGHT, c);
        }

        int ty = Y + (HEIGHT - 8) / 2 + 1;
        int cx = x + PADDING;

        // 1) "March 2007"
        g.drawString(f, text(i.month(), false), cx, ty, Theme.BEIGE, false);
        cx += f.width(text(i.month(), false)) + GAP;

        // 2) "11:18" + small "PM"
        g.drawString(f, i.clock(), cx, ty, 0xFFFFFFFF, false);
        cx += f.width(i.clock()) + 3;
        g.pose().pushPose();
        g.pose().translate(cx, ty + 1.5f, 0);
        g.pose().scale(0.8f, 0.8f, 1f);
        g.drawString(f, i.ampm(), 0, 0, Theme.MUTED, false);
        g.pose().popPose();
        cx += (int) (f.width(i.ampm()) * 0.8f) + GAP;

        // 3) "After School" in gold
        g.drawString(f, text(i.phase(), true), cx, ty, Theme.GOLD, false);
    }
}
