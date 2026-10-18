package com.opsflow;

import com.opsflow.incidents.IncidentStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IncidentStateTests {
    @Test
    void stateMachineAllowsOnlyTheFourForwardEdges() {
        var states = IncidentStatus.values();
        for (var from : states) for (var to : states)
            assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(to.ordinal() == from.ordinal() + 1);
    }
}
