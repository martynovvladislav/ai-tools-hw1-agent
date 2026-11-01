package dev.codingagent.journal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.codingagent.journal.diff.PathChange;
import dev.codingagent.journal.rollback.RollbackState;
import dev.codingagent.journal.snapshot.TreeSnapshot;

public final class JournalState {
    public TreeSnapshot expectedTree;
    public List<JournalAction> actions = new ArrayList<>();
    public Map<String, PathChange> paths = new LinkedHashMap<>();
    public RollbackState rollback;
}
