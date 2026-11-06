package com.serverops.fabric.sidemenu;

/** Small switches for the side menu. Change a value and rebuild. */
public final class MenuConfig {
    private MenuConfig() {}

    /** Open the Character tab by itself when a player who has no character yet joins. */
    public static final boolean AUTO_OPEN_CHARACTER_FOR_NEW_PLAYERS = true;

    /** Draw the little "<" tab on the left edge of the screen while playing (the menu key opens it). */
    public static final boolean SHOW_HUD_TAB = true;

    /**
     * false = every player may use Freecam.
     * true  = only players the existing admin panel lets in (OP / panel role) may use it.
     */
    public static final boolean FREECAM_STAFF_ONLY = false;

    /** Freecam cannot fly further than this many blocks away from the player's body. */
    public static final double FREECAM_MAX_DISTANCE = 64.0;

    /** Blocks per tick (20 ticks = 1 second). Sprint multiplies it. */
    public static final double FREECAM_SPEED = 0.45;
    public static final double FREECAM_SPRINT_MULTIPLIER = 3.0;

    // ---------------------------------------------------------------- Time & Date HUD (top-center)

    /** Show the bar with "Month Year  -  11:18 PM  -  After School" at the top-center of the screen. */
    public static final boolean SHOW_TIME_HUD = true;

    /**
     * The calendar of the roleplay world. One Minecraft day (20 real minutes) = one RP day, and a month lasts
     * {@link #DAYS_PER_MONTH} Minecraft days. The calendar starts on the 1st of CALENDAR_START_MONTH of
     * CALENDAR_START_YEAR, i.e. September 2009 = the beginning of the 2009/2010 school year, so the year on the
     * HUD matches the lore year (CharacterRules.LORE_YEAR = 2010).
     */
    public static final int CALENDAR_START_YEAR = 2009;
    public static final int CALENDAR_START_MONTH = 9;   // 1 = January ... 12 = December
    public static final int DAYS_PER_MONTH = 10;

    /**
     * true  = after August 2010 the calendar jumps back to September 2009, so the HUD NEVER shows a year outside
     *         the 2009-2010 lore window (the server stays "in" the same school year forever).
     * false = the calendar keeps counting forward (2011, 2012, ...).
     */
    public static final boolean CALENDAR_LOOPS = true;
}
