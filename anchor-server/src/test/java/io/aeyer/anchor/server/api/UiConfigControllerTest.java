package io.aeyer.anchor.server.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UiConfigControllerTest {

    @Test
    void reports_auth_required_when_token_set() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UiConfigController("s3cret", true)).build();

        mvc.perform(get("/anchor/ui/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth_required").value(true))
                .andExpect(jsonPath("$.ui_enabled").value(true));
    }

    @Test
    void reports_auth_not_required_when_token_blank() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UiConfigController("", true)).build();

        mvc.perform(get("/anchor/ui/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth_required").value(false));
    }

    @Test
    void reports_ui_disabled_when_toggle_off() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UiConfigController("", false)).build();

        // Endpoint itself stays reachable so callers can detect the disabled
        // state cleanly — the static asset handler is what actually 404s.
        mvc.perform(get("/anchor/ui/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ui_enabled").value(false));
    }
}
