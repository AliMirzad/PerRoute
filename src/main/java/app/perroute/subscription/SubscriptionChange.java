package app.perroute.subscription;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * How one update changed a subscription's servers. Servers are compared by their effective configuration;
 * a server whose name stayed but whose configuration changed counts as changed, not as one removed and one added.
 *
 * @param unexpected the update removed most of the servers, or replaced every one of them: worth a look
 */
public record SubscriptionChange(long at, int before, int after, int added, int removed, int changed, boolean unexpected) {

    /** One server as the comparison sees it. */
    public record Entry(String identity, String name) {}

    public static SubscriptionChange between(Collection<Entry> previous, Collection<Entry> next, long at) {
        Set<String> oldIdentities = new HashSet<>();
        Map<String, String> oldByName = new HashMap<>();
        for (Entry entry : previous) { oldIdentities.add(entry.identity()); oldByName.putIfAbsent(entry.name(), entry.identity()); }
        Set<String> newIdentities = new HashSet<>();
        for (Entry entry : next) newIdentities.add(entry.identity());
        int added = 0, changed = 0;
        Set<String> renamedAway = new HashSet<>();
        for (Entry entry : next) {
            if (oldIdentities.contains(entry.identity())) continue;
            String sameName = oldByName.get(entry.name());
            if (sameName != null && !newIdentities.contains(sameName) && renamedAway.add(sameName)) changed++;
            else added++;
        }
        int removed = 0;
        for (String identity : oldIdentities) if (!newIdentities.contains(identity) && !renamedAway.contains(identity)) removed++;
        int before = oldIdentities.size();
        int after = newIdentities.size();
        boolean unexpected = before >= 4 && (removed * 2 >= before || (added + changed >= before && removed + changed >= before));
        return new SubscriptionChange(at, before, after, added, removed, changed, unexpected);
    }

    public boolean empty() { return added == 0 && removed == 0 && changed == 0; }

    /** "3 added, 1 removed" or "No changes". */
    public String summary() {
        if (empty()) return "No changes";
        StringBuilder text = new StringBuilder();
        append(text, added, "added");
        append(text, removed, "removed");
        append(text, changed, "changed");
        return text.toString();
    }

    private static void append(StringBuilder text, int count, String word) {
        if (count == 0) return;
        if (!text.isEmpty()) text.append(", ");
        text.append(count).append(' ').append(word);
    }
}
