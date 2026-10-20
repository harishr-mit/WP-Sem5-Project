package com.mitpass.common.messaging;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The HTTP side of the dead-letter tooling; who may call it is in each service's SecurityMatrixTest. */
class DeadLetterControllerTest {

    DeadLetters deadLetters = mock(DeadLetters.class);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new DeadLetterController(deadLetters))
            .build();

    @Test
    void unknownMessageIsA404AndAReplayA204() throws Exception {
        given(deadLetters.replay("m-1")).willReturn(true);
        mvc.perform(post("/api/dead-letters/m-1/replay")).andExpect(status().isNoContent());
        mvc.perform(post("/api/dead-letters/m-2/replay")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/dead-letters/m-2")).andExpect(status().isNotFound());
    }

    @Test
    void anUnreachableBrokerIsA503() throws Exception {
        given(deadLetters.depths()).willReturn(List.of());
        given(deadLetters.list(anyInt())).willThrow(new AmqpConnectException(new java.net.ConnectException("refused")));
        mvc.perform(get("/api/dead-letters"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("RabbitMQ is not reachable"));
    }
}
