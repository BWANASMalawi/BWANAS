package net.bwanas.mobi;

/** Business offline window. A network indication alone never renews it. */
final class ReconnectPolicy {
    static final long LIMIT_MS = 24L * 60L * 60L * 1000L;
    static final long WARNING_MS = 2L * 60L * 60L * 1000L;
    static final long CLOCK_TOLERANCE_MS = 2L * 60L * 1000L;
    static long remaining(long savedWall, long savedElapsed, int savedBoot, long nowWall, long nowElapsed, int nowBoot) {
        if (savedWall <= 0 || savedElapsed < 0 || nowElapsed < 0 || nowWall < savedWall) return 0;
        long wallAge=nowWall-savedWall;
        // Elapsed time resets at reboot. Keep the original sync deadline.
        if (savedBoot >= 0 && nowBoot > savedBoot) {
            if (nowElapsed > wallAge + CLOCK_TOLERANCE_MS) return 0;
            return Math.max(0, LIMIT_MS - Math.max(wallAge, nowElapsed));
        }
        if ((savedBoot >= 0 && nowBoot >= 0 && nowBoot != savedBoot) || nowElapsed < savedElapsed) return 0;
        long elapsedAge=nowElapsed-savedElapsed;
        if(Math.abs(wallAge-elapsedAge)>CLOCK_TOLERANCE_MS)return 0;
        long age=Math.max(wallAge,elapsedAge);
        return age>=LIMIT_MS?0:LIMIT_MS-age;
    }
    private ReconnectPolicy() { }
}
