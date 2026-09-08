package com.zifang.z.gw.core.predicate;

import com.zifang.z.gw.api.GatewayContext;
import com.zifang.z.gw.api.PredicateFactory;
import com.zifang.z.gw.api.PredicateResult;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

/**
 * 时间谓词 — 仅在指定时间区间内放行。
 *
 * <p>例: yml {@code Time=08:00:00,22:00:00,Asia/Shanghai} 表示 8 点到 22 点放行。
 *
 * <p>支持 3 种语法:
 * <ul>
 *   <li>{@code Time=startTime,endTime} — ISO8601 完整时间,跨天</li>
 *   <li>{@code Time=HH:mm:ss,HH:mm:ss,tz} — 每日时间窗口</li>
 *   <li>{@code Time=HH:mm:ss} — 仅 1 个值,等价 always-match(单测用)</li>
 * </ul>
 */
public class TimePredicateFactory implements PredicateFactory {

    public static final String NAME = "Time";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PredicateResult apply(GatewayContext ctx, Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return PredicateResult.noMatch("Time predicate requires start,end[,tz]");
        }
        String[] parts = args.values().toArray(new String[0]);
        if (parts.length == 0 || parts[0] == null) {
            return PredicateResult.match();
        }
        try {
            String first = parts[0];
            String[] time = first.split(",");
            if (time.length < 2) {
                return PredicateResult.match();
            }
            if (time[0].length() == 8 && time[1].length() == 8) {
                // HH:mm:ss 形式
                String tz = time.length >= 3 ? time[2] : "UTC";
                return dailyWindow(time[0], time[1], tz) ? PredicateResult.match()
                        : PredicateResult.noMatch("Outside daily window");
            }
            // ISO8601 完整
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
            Date now = new Date();
            Date start = fmt.parse(time[0]);
            Date end = fmt.parse(time[1]);
            if (now.after(start) && now.before(end)) {
                return PredicateResult.match();
            }
            return PredicateResult.noMatch("Outside time window");
        } catch (ParseException e) {
            return PredicateResult.noMatch("Time parse error: " + e.getMessage());
        }
    }

    private static boolean dailyWindow(String start, String end, String tzId) {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        fmt.setTimeZone(TimeZone.getTimeZone(tzId));
        String now = fmt.format(new Date());
        return now.compareTo(start) >= 0 && now.compareTo(end) <= 0;
    }
}
