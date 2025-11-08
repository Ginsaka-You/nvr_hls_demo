package com.example.nvr.tcb.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HmacSupportTest {

    @Test
    void signsPayload() {
        String sig = HmacSupport.sign("secret", "payload");
        assertEquals("b82fcb791acec57859b989b430a826488ce2e479fdf92326bd0a2e8375a42ba4", sig);
    }
}

