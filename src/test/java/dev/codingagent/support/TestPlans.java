package dev.codingagent.support;

public final class TestPlans {
    public static final String JSON = """
            {"goal":"Fix the order calculation","steps":[
              {"id":"main","description":"Execute and verify the requested operation","tools":["read_file","write_file","Bash"],
               "depends_on":[],"expected_result":{"description":"Actual successful tool evidence","checks":[{"tool":"any"}]}}
            ],"risks":["Invalid discount inputs"],"assumptions":["Tests describe the required behavior"],
            "done_when":"Both original unittest cases pass without changing tests"}
            """;

    private TestPlans() {
    }
}
