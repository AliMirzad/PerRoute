package app.perroute.subscription;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SubscriptionInsight(
        String title,
        SubscriptionUserInfo userInfo,
        int updateIntervalHours,
        long firstSeenAt,
        long lastAttemptAt,
        long lastSuccessAt,
        String lastError,
        int consecutiveFailures,
        int serverCount,
        int duplicatesWithin,
        int duplicatesElsewhere,
        int sharedEndpoints,
        List<SubscriptionChange> history
) {
    public static final int HISTORY = 8;

    public SubscriptionInsight {
        title = title == null ? "" : title;
        userInfo = userInfo == null ? SubscriptionUserInfo.NONE : userInfo;
        lastError = lastError == null ? "" : lastError;
        history = List.copyOf(history == null ? List.of() : history);
    }

    public static SubscriptionInsight empty(long now) {
        return new SubscriptionInsight("", SubscriptionUserInfo.NONE, 0, now, 0, 0, "", 0, 0, 0, 0, 0, List.of());
    }

    public SubscriptionChange lastChange() { return history.isEmpty() ? null : history.get(history.size() - 1); }

    public SubscriptionInsight succeeded(long at, String title, SubscriptionUserInfo info, int intervalHours, int servers,
                                         int within, int elsewhere, int shared, SubscriptionChange change) {
        List<SubscriptionChange> next = new ArrayList<>(history);
        if (change != null) next.add(change);
        while (next.size() > HISTORY) next.remove(0);
        return new SubscriptionInsight(title == null || title.isBlank() ? this.title : title, info != null && info.known() ? info : userInfo,
                intervalHours > 0 ? intervalHours : updateIntervalHours, firstSeenAt == 0 ? at : firstSeenAt, at, at, "", 0,
                servers, within, elsewhere, shared, next);
    }

    public SubscriptionInsight failed(long at, String error) {
        return new SubscriptionInsight(title, userInfo, updateIntervalHours, firstSeenAt == 0 ? at : firstSeenAt, at, lastSuccessAt,
                error, consecutiveFailures + 1, serverCount, duplicatesWithin, duplicatesElsewhere, sharedEndpoints, history);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("title", title);
        json.put("upload", userInfo.uploadBytes());
        json.put("download", userInfo.downloadBytes());
        json.put("total", userInfo.totalBytes());
        json.put("expire", userInfo.expireAtMillis());
        json.put("interval", updateIntervalHours);
        json.put("firstSeenAt", firstSeenAt);
        json.put("lastAttemptAt", lastAttemptAt);
        json.put("lastSuccessAt", lastSuccessAt);
        json.put("lastError", lastError);
        json.put("failures", consecutiveFailures);
        json.put("servers", serverCount);
        json.put("duplicatesWithin", duplicatesWithin);
        json.put("duplicatesElsewhere", duplicatesElsewhere);
        json.put("sharedEndpoints", sharedEndpoints);
        List<Object> changes = new ArrayList<>();
        for (SubscriptionChange change : history) {
            changes.add(Map.of("at", change.at(), "before", change.before(), "after", change.after(), "added", change.added(),
                    "removed", change.removed(), "changed", change.changed(), "unexpected", change.unexpected()));
        }
        json.put("history", changes);
        return json;
    }

    public static SubscriptionInsight fromJson(Map<?, ?> json) {
        List<SubscriptionChange> changes = new ArrayList<>();
        if (json.get("history") instanceof List<?> list) {
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> change)) continue;
                changes.add(new SubscriptionChange(number(change.get("at")), (int) number(change.get("before")), (int) number(change.get("after")),
                        (int) number(change.get("added")), (int) number(change.get("removed")), (int) number(change.get("changed")),
                        Boolean.TRUE.equals(change.get("unexpected"))));
            }
        }
        return new SubscriptionInsight(text(json.get("title")),
                new SubscriptionUserInfo(number(json.get("upload"), -1), number(json.get("download"), -1), number(json.get("total"), -1), number(json.get("expire"), -1)),
                (int) number(json.get("interval")), number(json.get("firstSeenAt")), number(json.get("lastAttemptAt")), number(json.get("lastSuccessAt")),
                text(json.get("lastError")), (int) number(json.get("failures")), (int) number(json.get("servers")),
                (int) number(json.get("duplicatesWithin")), (int) number(json.get("duplicatesElsewhere")), (int) number(json.get("sharedEndpoints")), changes);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static long number(Object value) { return number(value, 0); }
    private static long number(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (RuntimeException invalid) { return fallback; }
    }
}
