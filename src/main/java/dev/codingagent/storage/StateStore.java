package dev.codingagent.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.sqlite.SQLiteConfig;

import dev.codingagent.config.AgentConfig;
import dev.codingagent.json.Json;
import dev.codingagent.run.RunState;

/** Stores complete run snapshots in one SQLite table. Business rules belong to services. */
public final class StateStore {
    private final Path workspace;
    private final Path databaseFile;

    public StateStore(AgentConfig config) {
        StatePaths paths = StatePaths.prepare(config);
        workspace = paths.workspace();
        databaseFile = paths.databaseFile();
        initializeDatabase();
    }

    public Path workspace() {
        return workspace;
    }

    public Path databaseFile() {
        return databaseFile;
    }

    public void create(RunState state) {
        validate(state, state.runId);
        String sql = "INSERT INTO runs(run_id, state_json) VALUES (?, ?)";
        try (Connection connection = connect(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.runId);
            statement.setString(2, Json.write(state));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot create run", exception);
        }
    }

    public Optional<RunState> find(String runId) {
        try (Connection connection = connect()) {
            return select(connection, runId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read run", exception);
        }
    }

    public RunState load(String runId) {
        return find(runId).orElseThrow(() -> new IllegalArgumentException("Unknown run: " + runId));
    }

    /** Only change state here. Execute network requests and external tools after the commit. */
    public <T> T update(String runId, Function<RunState, T> change) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                RunState state = select(connection, runId).orElseThrow(
                        () -> new IllegalArgumentException("Unknown run: " + runId));
                T result = change.apply(state);
                state.updatedAt = Instant.now().toString();
                validate(state, runId);
                save(connection, state);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot save run", exception);
        }
    }

    private void initializeDatabase() {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS runs (run_id TEXT PRIMARY KEY, state_json TEXT NOT NULL)");

            Set<String> columns = new HashSet<>();
            try (var result = statement.executeQuery("PRAGMA table_info(runs)")) {
                while (result.next()) {
                    columns.add(result.getString("name"));
                }
            }
            if (!columns.equals(Set.of("run_id", "state_json"))) {
                throw new IllegalStateException("Unsupported database schema; use a new state directory");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot open state store", exception);
        }
    }

    private Optional<RunState> select(Connection connection, String runId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT state_json FROM runs WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                RunState state = Json.readState(result.getString(1));
                validate(state, runId);
                return Optional.of(state);
            }
        }
    }

    private static void save(Connection connection, RunState state) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE runs SET state_json = ? WHERE run_id = ?")) {
            statement.setString(1, Json.write(state));
            statement.setString(2, state.runId);
            statement.executeUpdate();
        }
    }

    private void validate(RunState state, String runId) {
        if (state.formatVersion != RunState.CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported run state format");
        }
        if (runId == null || runId.isBlank() || !runId.equals(state.runId)) {
            throw new IllegalArgumentException("Run ID does not match the stored row");
        }
        if (!workspace.toString().equals(state.workspace)) {
            throw new IllegalArgumentException("Run belongs to a different workspace");
        }
        if (state.goal == null || state.goal.isBlank()) {
            throw new IllegalArgumentException("Run goal is missing");
        }
        if (state.status == null || state.phase == null) {
            throw new IllegalArgumentException("Run status or phase is missing");
        }
        if (state.createdAt == null || state.updatedAt == null) {
            throw new IllegalArgumentException("Run timestamps are missing");
        }
        if (state.messages == null || state.calls == null || state.decisions == null) {
            throw new IllegalArgumentException("Run history, calls or decisions are missing");
        }
        if (state.budget == null || state.budget.limits == null || state.tasks == null || state.journal == null
                || state.journal.expectedTree == null) {
            throw new IllegalArgumentException("Run requires budget, tasks and journal");
        }
        for (var entry : state.calls.entrySet()) {
            if (entry.getValue() == null || !entry.getKey().equals(entry.getValue().definition().toolCallId())) {
                throw new IllegalArgumentException("Tool call ID does not match its map key");
            }
        }
    }

    private Connection connect() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5_000);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile, config.toProperties());
    }

    private static void rollback(Connection connection, Exception originalFailure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
        }
    }
}
