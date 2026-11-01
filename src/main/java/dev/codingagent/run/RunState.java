package dev.codingagent.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.codingagent.budget.BudgetState;
import dev.codingagent.journal.JournalState;
import dev.codingagent.model.ChatMessage;
import dev.codingagent.plan.WorkPlan;
import dev.codingagent.task.TaskState;
import dev.codingagent.tools.execution.ToolCallState;

/** A persisted snapshot. Services change it inside a StateStore transaction. */
public final class RunState {
    public static final int CURRENT_FORMAT_VERSION = 2;

    public int formatVersion = CURRENT_FORMAT_VERSION;
    public String runId;
    public String goal;
    public String workspace;
    public RunStatus status;
    public RunPhase phase;
    public boolean planDraftRequested;
    public String createdAt;
    public String updatedAt;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public WorkPlan plan;
    public BudgetState budget;
    public List<TaskState> tasks;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String currentTaskId;
    public JournalState journal;

    public List<ChatMessage> messages = new ArrayList<>();
    public Map<String, ToolCallState> calls = new LinkedHashMap<>();
    public List<UserDecision> decisions = new ArrayList<>();
}
