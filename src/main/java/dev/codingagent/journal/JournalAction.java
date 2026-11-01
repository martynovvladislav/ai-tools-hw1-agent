package dev.codingagent.journal;

import java.util.ArrayList;
import java.util.List;

import dev.codingagent.journal.diff.PathChange;
import dev.codingagent.journal.snapshot.TreeSnapshot;

public final class JournalAction {
    public String toolCallId;
    public int attempt;
    public JournalActionStatus status = JournalActionStatus.STARTED;
    public String startedAt;
    public String finishedAt;
    public TreeSnapshot beforeTree;
    public TreeSnapshot afterTree;
    public String observation;
    public ChangeAttribution attribution;
    public List<PathChange> changes = new ArrayList<>();
}
