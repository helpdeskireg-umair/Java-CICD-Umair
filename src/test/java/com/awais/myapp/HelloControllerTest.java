package com.awais.myapp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HelloController.class)
class HelloControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET / returns HTTP 200")
    void rootEndpoint_returns200() throws Exception {
        mockMvc.perform(get("/"))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET / returns greeting with name")
    void rootEndpoint_returnsGreetingWithName() throws Exception {
        mockMvc.perform(get("/"))
               .andExpect(content().string(
                   org.hamcrest.Matchers.containsString("Hello, Umair!")
               ));
    }

    @Test
    @DisplayName("GET / response contains CI/CD message")
    void rootEndpoint_containsCiCdMessage() throws Exception {
        mockMvc.perform(get("/"))
               .andExpect(content().string(
                   org.hamcrest.Matchers.containsString("CI/CD pipeline")
               ));
    }
}
