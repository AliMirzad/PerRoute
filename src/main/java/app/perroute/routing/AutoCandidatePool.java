package app.perroute.routing;

import java.util.Locale;

/**
 * Which servers Auto may choose from for one application: every server, the favorites, or the servers
 * with one tag. Stored as a short text so it survives in the state file and in backups.
 */
public record AutoCandidatePool(Kind kind, String tag) {
    public enum Kind { ALL, FAVORITES, TAG }

    public static final AutoCandidatePool ALL = new AutoCandidatePool(Kind.ALL, "");

    public AutoCandidatePool {
        kind = kind == null ? Kind.ALL : kind;
        tag = tag == null ? "" : tag.trim();
        if (kind == Kind.TAG && tag.isBlank()) kind = Kind.ALL;
        if (kind != Kind.TAG) tag = "";
    }

    public boolean includes(String serverKey, boolean favorite, String serverTag) {
        return switch (kind) {
            case ALL -> true;
            case FAVORITES -> favorite;
            case TAG -> serverTag != null && serverTag.equalsIgnoreCase(tag);
        };
    }

    public String encode() {
        return kind == Kind.TAG ? "tag:" + tag : kind.name().toLowerCase(Locale.ROOT);
    }

    public static AutoCandidatePool decode(String text) {
        if (text == null || text.isBlank()) return ALL;
        String value = text.trim();
        if (value.regionMatches(true, 0, "tag:", 0, 4)) return new AutoCandidatePool(Kind.TAG, value.substring(4));
        return value.equalsIgnoreCase("favorites") ? new AutoCandidatePool(Kind.FAVORITES, "") : ALL;
    }

    public String describe() {
        return switch (kind) {
            case ALL -> "All servers";
            case FAVORITES -> "Favorites";
            case TAG -> "#" + tag;
        };
    }
}
